package com.paymentswitch.support;

import com.paymentswitch.common.outbox.OutboxEvent;
import com.paymentswitch.common.outbox.OutboxHandler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;

/** Handles {@value #EVENT_TYPE} outbox events by counting them; can be told to fail first. */
@TestComponent
public class RecordingOutboxHandler implements OutboxHandler {

    public static final String EVENT_TYPE = "test.recorded";

    private final Map<UUID, AtomicInteger> handled = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final Map<UUID, String> payloads = new ConcurrentHashMap<>();

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        AtomicInteger left = failuresLeft.get(event.aggregateId());
        if (left != null && left.getAndDecrement() > 0) {
            throw new IllegalStateException("simulated failure");
        }
        handled.computeIfAbsent(event.aggregateId(), id -> new AtomicInteger()).incrementAndGet();
        payloads.put(event.aggregateId(), event.payload());
    }

    public void failNext(UUID aggregateId, int times) {
        failuresLeft.put(aggregateId, new AtomicInteger(times));
    }

    public int handledCount(UUID aggregateId) {
        AtomicInteger count = handled.get(aggregateId);
        return count == null ? 0 : count.get();
    }

    public String payload(UUID aggregateId) {
        return payloads.get(aggregateId);
    }
}
