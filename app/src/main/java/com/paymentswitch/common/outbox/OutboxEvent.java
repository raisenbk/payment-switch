package com.paymentswitch.common.outbox;

import java.util.UUID;

/**
 * An event claimed from the outbox.
 *
 * @param payload  the event's JSON payload
 * @param attempts      how many times it has been claimed, including this one
 * @param traceHeaders  W3C trace context of the transaction that wrote it (JSON), or null
 */
public record OutboxEvent(long id, String eventType, UUID aggregateId, String payload, int attempts,
        String traceHeaders) {
}
