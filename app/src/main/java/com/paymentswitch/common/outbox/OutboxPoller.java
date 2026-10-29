package com.paymentswitch.common.outbox;

import com.paymentswitch.common.tracing.TraceContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims due outbox events and hands each to the handler for its type, on its own virtual thread.
 * Safe to run on any number of instances: claims never overlap while a lease is held.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository repository;
    private final OutboxProperties properties;
    private final Map<String, OutboxHandler> handlers;
    private final TraceContexts traces;
    private final MeterRegistry meters;

    OutboxPoller(OutboxRepository repository, OutboxProperties properties, List<OutboxHandler> handlers,
            TraceContexts traces, MeterRegistry meters) {
        this.repository = repository;
        this.properties = properties;
        this.traces = traces;
        this.meters = meters;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::eventType,
                Function.identity()));
    }

    /** @return how many events were claimed */
    @Scheduled(fixedDelayString = "${payment-switch.outbox.poll-interval:250ms}")
    public int poll() {
        List<OutboxEvent> events = repository.claimDue(properties.batchSize(), properties.lease());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            events.forEach(event -> executor.execute(() -> dispatch(event)));
        }
        return events.size();
    }

    private void dispatch(OutboxEvent event) {
        OutboxHandler handler = handlers.get(event.eventType());
        try {
            if (handler == null) {
                throw new IllegalStateException("No handler for event type " + event.eventType());
            }
            // Continues the trace of the request that wrote the event (ADR-009).
            traces.continueTrace(event.traceHeaders(), "outbox " + event.eventType(), Span.Kind.CONSUMER,
                    Map.of("outbox.event_id", Long.toString(event.id()),
                            "outbox.aggregate_id", event.aggregateId().toString()),
                    () -> handler.handle(event));
            repository.markProcessed(event.id());
            count(event, "processed");
        } catch (RuntimeException e) {
            count(event, "retry");
            Duration delay = retryDelay(event.attempts());
            log.warn("Outbox event {} ({}) failed on attempt {}, retrying in {}",
                    event.id(), event.eventType(), event.attempts(), delay, e);
            repository.scheduleRetry(event.id(), delay, e.toString());
        }
    }

    private void count(OutboxEvent event, String outcome) {
        meters.counter("paymentswitch.outbox.events", "type", event.eventType(), "outcome", outcome).increment();
    }

    private Duration retryDelay(int attempts) {
        Duration delay = properties.retryInitialDelay().multipliedBy(1L << Math.min(attempts - 1, 20));
        return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
    }
}
