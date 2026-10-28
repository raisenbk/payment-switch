package com.paymentswitch.transaction;

import com.paymentswitch.transaction.TransactionService.TransactionAccepted;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts supplier processing once the accepting transaction has committed.
 *
 * <p>Known gap, closed in Phase 5: if the app stops between the commit and this listener running,
 * the transaction stays PENDING. The transactional outbox replaces this in-memory hand-off.
 */
@Component
class TransactionDispatcher {

    private final TransactionProcessor processor;

    TransactionDispatcher(TransactionProcessor processor) {
        this.processor = processor;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccepted(TransactionAccepted event) {
        processor.process(event.transactionId());
    }
}
