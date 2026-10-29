package com.paymentswitch.common.outbox;

import com.paymentswitch.common.outbox.OutboxRepository.Backlog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog gauges, refreshed on a schedule rather than on every scrape (one cheap query):
 * {@code paymentswitch.outbox.pending} (unprocessed events) and {@code paymentswitch.outbox.lag}
 * (seconds the oldest due event has been waiting: the delay before work starts).
 */
@Component
class OutboxMetrics {

    private final OutboxRepository repository;
    private final AtomicReference<Backlog> backlog = new AtomicReference<>(new Backlog(0, 0));

    OutboxMetrics(OutboxRepository repository, MeterRegistry meters) {
        this.repository = repository;
        Gauge.builder("paymentswitch.outbox.pending", backlog, b -> b.get().pending())
                .description("Outbox events not processed yet")
                .register(meters);
        Gauge.builder("paymentswitch.outbox.lag", backlog, b -> b.get().oldestDueSeconds())
                .description("Age of the oldest due, unprocessed outbox event")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payment-switch.metrics.refresh-interval:10s}")
    void refresh() {
        backlog.set(repository.backlog());
    }
}
