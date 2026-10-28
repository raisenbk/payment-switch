package com.paymentswitch.transaction;

import static com.paymentswitch.transaction.TransactionStatus.FAILED;
import static com.paymentswitch.transaction.TransactionStatus.PENDING;
import static com.paymentswitch.transaction.TransactionStatus.PROCESSING;
import static com.paymentswitch.transaction.TransactionStatus.SUCCESS;
import static com.paymentswitch.transaction.TransactionStatus.UNKNOWN;

import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.LedgerService;
import com.paymentswitch.routing.SupplierRouter;
import com.paymentswitch.routing.SupplierRouter.Route;
import com.paymentswitch.supplier.SupplierGateway;
import com.paymentswitch.supplier.SupplierGateways;
import com.paymentswitch.supplier.SupplierRequest;
import com.paymentswitch.supplier.SupplierResult;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import com.paymentswitch.transaction.TransactionRepository.TransactionRow;
import com.paymentswitch.transaction.TransactionStateMachine.ConcurrentTransitionException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives one PENDING transaction through the supplier:
 * <ol>
 *   <li>DB tx 1: route, PENDING → PROCESSING (commit before calling out).</li>
 *   <li>Supplier call, outside any DB transaction.</li>
 *   <li>DB tx 2: apply the result. SUCCESS captures the hold, FAILED releases it, UNKNOWN keeps it.</li>
 * </ol>
 * Safe to call more than once: only the caller that wins PENDING → PROCESSING submits.
 */
@Component
class TransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final SupplierRouter router;
    private final SupplierGateways gateways;
    private final LedgerService ledger;
    private final TransactionTemplate transaction;

    TransactionProcessor(TransactionRepository repository, TransactionStateMachine stateMachine,
            SupplierRouter router, SupplierGateways gateways, LedgerService ledger,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.router = router;
        this.gateways = gateways;
        this.ledger = ledger;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    void process(UUID transactionId) {
        MDC.put("transactionId", transactionId.toString());
        try {
            Optional<Dispatch> dispatch = transaction.execute(status -> startProcessing(transactionId));
            if (dispatch.isEmpty()) {
                return;
            }
            Dispatch d = dispatch.get();
            SupplierResult result = d.gateway.submit(new SupplierRequest(
                    transactionId.toString(), d.route.supplierSku(), d.row.destination(), d.row.amount()));
            transaction.executeWithoutResult(status -> applyResult(transactionId, d.route, result));
        } catch (ConcurrentTransitionException e) {
            log.info("Transaction already being handled elsewhere: {}", e.getMessage());
        } finally {
            MDC.remove("transactionId");
            MDC.remove("merchantId");
        }
    }

    private Optional<Dispatch> startProcessing(UUID transactionId) {
        TransactionRow row = repository.findById(transactionId).orElseThrow();
        if (row.status() != PENDING) {
            return Optional.empty();
        }
        MDC.put("merchantId", row.merchantId().toString());
        Optional<Route> route = router.route(row.productId(), Money.of(row.amount(), row.currency()));
        Optional<SupplierGateway> gateway = route.flatMap(r -> gateways.forSupplier(r.supplierCode()));
        if (gateway.isEmpty()) {
            // Nothing was sent anywhere, so failing (and releasing the hold) is safe.
            stateMachine.transition(transactionId, PENDING, PROCESSING, StatusChange.NONE, Map.of());
            stateMachine.transition(transactionId, PROCESSING, FAILED, StatusChange.failed("NO_ROUTE"),
                    Map.of("reason", route.isEmpty() ? "no eligible supplier" : "supplier not configured"));
            ledger.release(transactionId);
            return Optional.empty();
        }
        stateMachine.transition(transactionId, PENDING, PROCESSING,
                StatusChange.routed(route.get().supplierId(), route.get().cost().amount()),
                Map.of("supplier", route.get().supplierCode(), "sku", route.get().supplierSku()));
        return Optional.of(new Dispatch(row, route.get(), gateway.get()));
    }

    private void applyResult(UUID transactionId, Route route, SupplierResult result) {
        switch (result) {
            case SupplierResult.Success success -> {
                stateMachine.transition(transactionId, PROCESSING, SUCCESS,
                        StatusChange.succeeded(success.supplierRef()), Map.of("supplier", route.supplierCode()));
                ledger.capture(transactionId, route.supplierId(), route.cost());
            }
            case SupplierResult.Failed failed -> {
                stateMachine.transition(transactionId, PROCESSING, FAILED, StatusChange.failed(failed.code()),
                        Map.of("supplier", route.supplierCode(), "code", failed.code()));
                ledger.release(transactionId);
            }
            case SupplierResult.Unknown unknown ->
                    // Timeout != failure: keep the hold until an inquiry gives a definitive answer.
                    stateMachine.transition(transactionId, PROCESSING, UNKNOWN, StatusChange.NONE,
                            Map.of("supplier", route.supplierCode(), "reason", unknown.reason()));
        }
    }

    private record Dispatch(TransactionRow row, Route route, SupplierGateway gateway) {
    }
}
