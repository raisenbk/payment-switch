package com.paymentswitch.common.outbox;

/**
 * Handles one outbox event type. Delivery is at-least-once (a lease can expire while a slow
 * handler is still running, or an instance can die after handling but before marking the event
 * processed), so handlers must be idempotent. Throwing schedules a retry with backoff.
 */
public interface OutboxHandler {

    String eventType();

    void handle(OutboxEvent event);
}
