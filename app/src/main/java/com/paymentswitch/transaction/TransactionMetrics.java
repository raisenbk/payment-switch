package com.paymentswitch.transaction;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code paymentswitch.transactions.open{status}}: how many transactions are not final yet, by
 * status. UNKNOWN and MANUAL_REVIEW are the ones to alert on (money held, outcome open).
 * Refreshed on a schedule with one grouped query, not on every scrape.
 */
@Component
class TransactionMetrics {

    private static final List<TransactionStatus> OPEN = List.of(TransactionStatus.PENDING,
            TransactionStatus.PROCESSING, TransactionStatus.UNKNOWN, TransactionStatus.MANUAL_REVIEW);

    private final JdbcClient jdbc;
    private final Map<TransactionStatus, AtomicLong> counts = new EnumMap<>(TransactionStatus.class);

    TransactionMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (TransactionStatus status : OPEN) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder("paymentswitch.transactions.open", count, AtomicLong::get)
                    .description("Transactions that are not final yet")
                    .tag("status", status.name())
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${payment-switch.metrics.refresh-interval:10s}")
    void refresh() {
        Map<String, Long> current = new HashMap<>();
        jdbc.sql("""
                        SELECT status, count(*) FROM transactions
                         WHERE status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'MANUAL_REVIEW')
                         GROUP BY status""")
                .query((rs, row) -> current.put(rs.getString(1), rs.getLong(2)))
                .list();
        counts.forEach((status, count) -> count.set(current.getOrDefault(status.name(), 0L)));
    }
}
