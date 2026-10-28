package com.paymentswitch.transaction;

import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way a transaction's status changes. Each transition is validated against
 * {@link TransactionStatus#canTransitionTo}, applied with compare-and-set, and recorded in
 * {@code transaction_events} within the caller's database transaction.
 */
@Component
class TransactionStateMachine {

    private final TransactionRepository repository;
    private final JsonMapper jsonMapper;

    TransactionStateMachine(TransactionRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordCreated(UUID transactionId, Map<String, Object> payload) {
        repository.insertEvent(transactionId, null, TransactionStatus.PENDING, jsonMapper.writeValueAsString(payload));
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
