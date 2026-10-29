package com.paymentswitch.common.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String eventType, UUID aggregateId, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox_events (event_type, aggregate_id, payload)
                        VALUES (:type, :aggregateId, CAST(:payload AS jsonb))""")
                .param("type", eventType)
                .param("aggregateId", aggregateId)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims up to {@code limit} due events in one statement. {@code FOR UPDATE SKIP LOCKED} makes
     * concurrent pollers take disjoint rows instead of waiting for each other; pushing
     * {@code available_at} forward is the lease that hides the rows after this statement commits.
     */
    List<OutboxEvent> claimDue(int limit, Duration lease) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM outbox_events
                             WHERE processed_at IS NULL AND available_at <= now()
                             ORDER BY available_at, id
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                        UPDATE outbox_events o
                           SET available_at = now() + interval '1 millisecond' * CAST(:leaseMs AS double precision),
                               attempts = o.attempts + 1
                          FROM due
                         WHERE o.id = due.id
                        RETURNING o.id, o.event_type, o.aggregate_id, o.payload::text AS payload, o.attempts""")
                .param("limit", limit)
                .param("leaseMs", lease.toMillis())
                .query(OutboxEvent.class)
                .list();
    }

    void markProcessed(long id) {
        jdbc.sql("UPDATE outbox_events SET processed_at = now() WHERE id = :id").param("id", id).update();
    }

    void scheduleRetry(long id, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE outbox_events
                           SET available_at = now() + interval '1 millisecond' * CAST(:delayMs AS double precision),
                               last_error = :error
                         WHERE id = :id""")
                .param("delayMs", delay.toMillis())
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .param("id", id)
                .update();
    }
}
