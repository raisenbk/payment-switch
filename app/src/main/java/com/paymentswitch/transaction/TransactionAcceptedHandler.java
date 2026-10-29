package com.paymentswitch.transaction;

import com.paymentswitch.common.outbox.OutboxEvent;
import com.paymentswitch.common.outbox.OutboxHandler;
import org.springframework.stereotype.Component;

/** Sends an accepted transaction to its supplier. Idempotent, see {@link TransactionProcessor}. */
@Component
class TransactionAcceptedHandler implements OutboxHandler {

    static final String EVENT_TYPE = "transaction.accepted";

    private final TransactionProcessor processor;

    TransactionAcceptedHandler(TransactionProcessor processor) {
        this.processor = processor;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        processor.process(event.aggregateId());
    }
}
