package com.paymentswitch.transaction;

import static com.paymentswitch.transaction.TransactionStatus.FAILED;
import static com.paymentswitch.transaction.TransactionStatus.PENDING;
import static com.paymentswitch.transaction.TransactionStatus.PROCESSING;

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
 * Drives one accepted transaction through the supplier:
 * <ol>
 *   <li>DB tx 1: route, PENDING → PROCESSING (commit before calling out).</li>
 *   <li>Supplier call, outside any DB transaction.</li>
 *   <li>DB tx 2: apply the result through {@link SupplierOutcomes}.</li>
 * </ol>
 * Runs from the outbox, so it may run more than once for the same transaction (ADR-006):
 * <ul>
 *   <li>PENDING: only the caller that wins PENDING → PROCESSING submits.</li>
 *   <li>PROCESSING: an earlier run committed step 1 and then stopped (crash) before step 3. We cannot
 *       know whether its request reached the supplier, so we submit again with the same reference.
 *       The supplier deduplicates on it and answers with its stored outcome, so the customer is never
 *       topped up twice.</li>
 *   <li>Anything else: already handled, nothing to do.</li>
 * </ul>
 */
@Component
class TransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final SupplierRouter router;
    private final SupplierGateways gateways;
    private final LedgerService ledger;
    private final SupplierOutcomes outcomes;
    private final TransactionTemplate transaction;

    TransactionProcessor(TransactionRepository repository, TransactionStateMachine stateMachine,
            SupplierRouter router, SupplierGateways gateways, LedgerService ledger, SupplierOutcomes outcomes,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.router = router;
        this.gateways = gateways;
        this.ledger = ledger;
        this.outcomes = outcomes;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    void process(UUID transactionId) {
        MDC.put("transactionId", transactionId.toString());
        try {
            Optional<Dispatch> dispatch = transaction.execute(status -> prepare(transactionId));
            if (dispatch.isEmpty()) {
                return;
            }
            Dispatch d = dispatch.get();
            SupplierResult result = d.gateway
                    .map(gateway -> gateway.submit(new SupplierRequest(
                            transactionId.toString(), d.supplierSku, d.destination, d.amount)))
                    .orElseGet(() -> new SupplierResult.Unknown("CANNOT_RESUBMIT"));
            outcomes.applySubmitResult(transactionId, d.supplierCode, result, d.resubmit);
        } catch (ConcurrentTransitionException e) {
            log.info("Transaction already being handled elsewhere: {}", e.getMessage());
        } finally {
            MDC.remove("transactionId");
            MDC.remove("merchantId");
        }
    }

    private Optional<Dispatch> prepare(UUID transactionId) {
        TransactionRow row = repository.findById(transactionId).orElseThrow();
        MDC.put("merchantId", row.merchantId().toString());
        return switch (row.status()) {
            case PENDING -> route(row);
            case PROCESSING -> resume(row);
            default -> Optional.empty();
        };
    }

    private Optional<Dispatch> route(TransactionRow row) {
        Optional<Route> route = router.route(row.productId(), Money.of(row.amount(), row.currency()));
        Optional<SupplierGateway> gateway = route.flatMap(r -> gateways.forSupplier(r.supplierCode()));
        if (gateway.isEmpty()) {
            // Nothing was sent anywhere, so failing (and releasing the hold) is safe.
            stateMachine.transition(row.id(), PENDING, PROCESSING, StatusChange.NONE, Map.of());
            stateMachine.transition(row.id(), PROCESSING, FAILED, StatusChange.failed("NO_ROUTE"),
                    Map.of("reason", route.isEmpty() ? "no eligible supplier" : "supplier not configured"));
            ledger.release(row.id());
            return Optional.empty();
        }
        Route r = route.get();
        stateMachine.transition(row.id(), PENDING, PROCESSING,
                StatusChange.routed(r.supplierId(), r.cost().amount(), r.supplierSku()),
                Map.of("supplier", r.supplierCode(), "sku", r.supplierSku()));
        return Optional.of(new Dispatch(r.supplierCode(), r.supplierSku(), row.destination(), row.amount(),
                gateway, false));
    }

    private Optional<Dispatch> resume(TransactionRow row) {
        log.warn("Transaction {} found in PROCESSING; resubmitting to {} with the same reference",
                row.id(), row.supplierCode());
        // Rows routed before supplier_sku existed cannot be rebuilt; they go to UNKNOWN and inquiry.
        Optional<SupplierGateway> gateway = row.supplierSku() == null
                ? Optional.empty() : gateways.forSupplier(row.supplierCode());
        return Optional.of(new Dispatch(row.supplierCode(), row.supplierSku(), row.destination(), row.amount(),
                gateway, true));
    }

    private record Dispatch(String supplierCode, String supplierSku, String destination, long amount,
            Optional<SupplierGateway> gateway, boolean resubmit) {
    }
}
