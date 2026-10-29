package com.paymentswitch.transaction;

import com.paymentswitch.common.outbox.OutboxPublisher;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way a transaction's status changes. Each transition is validated against
 * {@link TransactionStatus#canTransitionTo}, applied with compare-and-set, and recorded in
 * {@code transaction_events} within the caller's database transaction. Reaching a final status
 * also publishes {@value #COMPLETED_EVENT} to the outbox, in that same transaction.
 */
@Component
class TransactionStateMachine {

    /** Outbox event announcing a final status (SUCCESS or FAILED); drives merchant webhooks. */
    static final String COMPLETED_EVENT = "transaction.completed";

    private final TransactionRepository repository;
    private final OutboxPublisher outbox;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;

    TransactionStateMachine(TransactionRepository repository, OutboxPublisher outbox, JsonMapper jsonMapper,
            MeterRegistry meters) {
        this.repository = repository;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordCreated(UUID transactionId, Map<String, Object> payload) {
        repository.insertEvent(transactionId, null, TransactionStatus.PENDING, jsonMapper.writeValueAsString(payload));
        countAfterCommit("paymentswitch.transactions", "status", TransactionStatus.PENDING.name());
    }

    /**
     * @throws IllegalTransitionException  if {@code from -> to} is not allowed
     * @throws ConcurrentTransitionException if the status is no longer {@code from}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void transition(UUID transactionId, TransactionStatus from, TransactionStatus to, StatusChange change,
            Map<String, Object> payload) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalTransitionException(transactionId, from, to);
        }
        if (!repository.compareAndSetStatus(transactionId, from, to, change)) {
            throw new ConcurrentTransitionException(transactionId, from);
        }
        repository.insertEvent(transactionId, from, to, jsonMapper.writeValueAsString(payload));
        countAfterCommit("paymentswitch.transactions", "status", to.name());
        if (to.isFinal()) {
            TransactionRepository.TransactionRow row = repository.findById(transactionId).orElseThrow();
            outbox.publish(COMPLETED_EVENT, transactionId,
                    Map.of("merchantId", row.merchantId(), "transaction", TransactionView.of(row)));
        }
    }

    /**
     * Failover: the transaction stays PROCESSING but moves from one supplier to the next. Recorded as
     * a PROCESSING → PROCESSING event so the history shows every supplier that was tried.
     *
     * @throws ConcurrentTransitionException if it is no longer PROCESSING at {@code fromSupplierId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void failOver(UUID transactionId, long fromSupplierId, long toSupplierId, String supplierSku, long supplierCost,
            Map<String, Object> payload) {
        if (!repository.reroute(transactionId, fromSupplierId, toSupplierId, supplierSku, supplierCost)) {
            throw new ConcurrentTransitionException(transactionId, TransactionStatus.PROCESSING);
        }
        repository.insertEvent(transactionId, TransactionStatus.PROCESSING, TransactionStatus.PROCESSING,
                jsonMapper.writeValueAsString(payload));
        countAfterCommit("paymentswitch.failovers", "code", String.valueOf(payload.get("code")));
    }

    /** Counts only what was committed: a rolled-back transition never happened. */
    private void countAfterCommit(String name, String tagKey, String tagValue) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter(name, tagKey, tagValue).increment();
            }
        });
    }

    static class IllegalTransitionException extends IllegalStateException {

        IllegalTransitionException(UUID id, TransactionStatus from, TransactionStatus to) {
            super("Illegal transition " + from + " -> " + to + " for transaction " + id);
        }
    }

    static class ConcurrentTransitionException extends IllegalStateException {

        ConcurrentTransitionException(UUID id, TransactionStatus expected) {
            super("Transaction " + id + " is no longer " + expected);
        }
    }
}
