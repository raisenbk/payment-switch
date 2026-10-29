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
import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import com.paymentswitch.transaction.TransactionRepository.TransactionRow;
import com.paymentswitch.transaction.TransactionStateMachine.ConcurrentTransitionException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *   <li>Definitive failure that another supplier might not share: DB tx to fail over to the next
 *       eligible supplier, then back to step 2 (ADR-008). Never after an ambiguous result.</li>
 *   <li>DB tx 2: apply the result through {@link SupplierOutcomes}.</li>
 * </ol>
 * Runs from the outbox, so it may run more than once for the same transaction (ADR-006):
 * <ul>
 *   <li>PENDING: only the caller that wins PENDING → PROCESSING submits.</li>
 *   <li>PROCESSING: an earlier run committed step 1 (or a failover) and then stopped (crash) before
 *       step 4. We cannot know whether its request reached the current supplier, so we submit again
 *       with the same reference. The supplier deduplicates on it and answers with its stored outcome,
 *       so the customer is never topped up twice.</li>
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
            SupplierResult result = submit(transactionId, d);
            while (result instanceof Failed failed && router.mayFailOver(failed.code(), d.tried().size())) {
                Dispatch current = d;
                Optional<Dispatch> next = transaction.execute(status -> failOver(transactionId, current, failed));
                if (next.isEmpty()) {
                    break;
                }
                d = next.get();
                result = submit(transactionId, d);
            }
            outcomes.applySubmitResult(transactionId, d.route().supplierCode(), result, d.resubmit());
        } catch (ConcurrentTransitionException e) {
            log.info("Transaction already being handled elsewhere: {}", e.getMessage());
        } finally {
            MDC.remove("transactionId");
            MDC.remove("merchantId");
        }
    }

    private SupplierResult submit(UUID transactionId, Dispatch d) {
        Optional<SupplierGateway> gateway = d.route().supplierSku() == null
                ? Optional.empty() : gateways.forSupplier(d.route().supplierCode());
        // The amount is what this supplier bills us (the agreed cost), as in its settlement file.
        return gateway
                .map(g -> g.submit(new SupplierRequest(transactionId.toString(), d.route().supplierSku(),
                        d.destination(), d.route().cost().amount())))
                .orElseGet(() -> new SupplierResult.Unknown("CANNOT_RESUBMIT"));
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
        if (route.isEmpty()) {
            // Nothing was sent anywhere, so failing (and releasing the hold) is safe.
            stateMachine.transition(row.id(), PENDING, PROCESSING, StatusChange.NONE, Map.of());
            stateMachine.transition(row.id(), PROCESSING, FAILED, StatusChange.failed("NO_ROUTE"),
                    Map.of("reason", "no eligible supplier"));
            ledger.release(row.id());
            return Optional.empty();
        }
        Route r = route.get();
        stateMachine.transition(row.id(), PENDING, PROCESSING,
                StatusChange.routed(r.supplierId(), r.cost().amount(), r.supplierSku()),
                Map.of("supplier", r.supplierCode(), "sku", r.supplierSku()));
        return Optional.of(new Dispatch(r, row.destination(), Set.of(r.supplierId()), false));
    }

    private Optional<Dispatch> resume(TransactionRow row) {
        log.warn("Transaction {} found in PROCESSING; resubmitting to {} with the same reference",
                row.id(), row.supplierCode());
        // Rows routed before supplier_sku existed cannot be rebuilt; they go to UNKNOWN and inquiry.
        Route current = new Route(row.supplierId(), row.supplierCode(), row.supplierSku(),
                Money.of(row.supplierCost(), row.currency()));
        Set<Long> tried = new HashSet<>(repository.attemptedSuppliers(row.id()));
        tried.add(row.supplierId());
        return Optional.of(new Dispatch(current, row.destination(), Set.copyOf(tried), true));
    }

    /**
     * Only after a definitive failure: the current supplier did not top up, so trying the next one
     * cannot cause a double top-up. Returns empty when no other supplier is eligible.
     */
    private Optional<Dispatch> failOver(UUID transactionId, Dispatch from, Failed failed) {
        TransactionRow row = repository.findById(transactionId).orElseThrow();
        Optional<Route> next = router.route(row.productId(), Money.of(row.amount(), row.currency()), from.tried());
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Route r = next.get();
        stateMachine.failOver(transactionId, from.route().supplierId(), r.supplierId(), r.supplierSku(),
                r.cost().amount(), Map.of("failover", true, "from", from.route().supplierCode(),
                        "to", r.supplierCode(), "code", failed.code()));
        log.info("Supplier {} failed definitively ({}); failing over to {}",
                from.route().supplierCode(), failed.code(), r.supplierCode());
        Set<Long> tried = new HashSet<>(from.tried());
        tried.add(r.supplierId());
        return Optional.of(new Dispatch(r, from.destination(), Set.copyOf(tried), false));
    }

    /** @param tried every supplier this transaction has been sent to, including the current one */
    private record Dispatch(Route route, String destination, Set<Long> tried, boolean resubmit) {
    }
}
