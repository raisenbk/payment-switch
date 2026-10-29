package com.paymentswitch.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.support.RecordingOutboxHandler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    private static final String TYPE = RecordingOutboxHandler.EVENT_TYPE;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxPoller poller;

    @Autowired
    RecordingOutboxHandler handler;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void eventExistsOnlyIfItsTransactionCommits() {
        UUID committed = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();

        transaction().executeWithoutResult(s -> publisher.publish(TYPE, committed, Map.of("n", 1)));
        assertThatThrownBy(() -> transaction().executeWithoutResult(s -> {
            publisher.publish(TYPE, rolledBack, Map.of());
            throw new IllegalStateException("business rule failed");
        })).hasMessage("business rule failed");

        await().atMost(Duration.ofSeconds(5)).until(() -> processed(committed));
        assertThat(handler.handledCount(committed)).isEqualTo(1);
        assertThat(handler.payload(committed)).isEqualTo("{\"n\": 1}");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", rolledBack).query(Long.class).single()).isZero();
    }

    @Test
    void publishingRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> publisher.publish(TYPE, UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void failingHandlerIsRetriedWithBackoffUntilItSucceeds() {
        UUID id = UUID.randomUUID();
        handler.failNext(id, 2);

        transaction().executeWithoutResult(s -> publisher.publish(TYPE, id, Map.of()));

        await().atMost(Duration.ofSeconds(10)).until(() -> processed(id));
        assertThat(handler.handledCount(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT attempts FROM outbox_events WHERE aggregate_id = :id")
                .param("id", id).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE aggregate_id = :id")
                .param("id", id).query(String.class).single()).contains("simulated failure");
    }

    /**
     * Several pollers (as if several app instances) race for the same events, together with the
     * scheduled poller. Every event must be handled exactly once.
     */
    @RepeatedTest(3)
    void concurrentPollersHandleEachEventExactlyOnce() throws Exception {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(300).toList();
        transaction().executeWithoutResult(s -> ids.forEach(id -> publisher.publish(TYPE, id, Map.of())));

        int pollers = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(pollers)) {
            List<Future<Object>> running = Stream.generate(() -> executor.<Object>submit(() -> {
                start.await();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (unprocessed(ids) > 0 && System.nanoTime() < deadline) {
                    poller.poll();
                }
                return null;
            })).limit(pollers).toList();
            start.countDown();
            for (Future<Object> future : running) {
                future.get();
            }
        }

        assertThat(unprocessed(ids)).isZero();
        assertThat(ids).allSatisfy(id -> assertThat(handler.handledCount(id)).as("handled %s", id).isEqualTo(1));
    }

    private boolean processed(UUID aggregateId) {
        return jdbc.sql("SELECT processed_at IS NOT NULL FROM outbox_events WHERE aggregate_id = :id")
                .param("id", aggregateId)
                .query(Boolean.class)
                .single();
    }

    private long unprocessed(List<UUID> ids) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (:ids) AND processed_at IS NULL")
                .param("ids", ids)
                .query(Long.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }
}
