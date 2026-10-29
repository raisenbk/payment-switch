package com.paymentswitch.webhook;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WebhookRepository {

    private static final String SELECT_EVENT = """
            SELECT id, merchant_id, event_type, status, attempts, next_attempt_at, created_at, delivered_at
              FROM webhook_events""";

    private final JdbcClient jdbc;

    WebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent per outbox event: a re-handled outbox event does not create a second webhook. */
    void insertEvent(UUID id, long outboxEventId, UUID merchantId, String eventType, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO webhook_events (id, outbox_event_id, merchant_id, event_type, payload)
                        VALUES (:id, :outboxEventId, :merchantId, :eventType, CAST(:payload AS json))
                        ON CONFLICT (outbox_event_id) DO NOTHING""")
                .param("id", id)
                .param("outboxEventId", outboxEventId)
                .param("merchantId", merchantId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims due webhooks with {@code FOR UPDATE SKIP LOCKED}, counting the attempt and leasing the
     * rows (next_attempt_at pushed forward) in the same statement, like the outbox (ADR-006).
     */
    List<DueWebhook> claimDue(int limit, Duration lease) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM webhook_events
                             WHERE status = 'PENDING' AND next_attempt_at <= now()
                             ORDER BY next_attempt_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                        UPDATE webhook_events w
                           SET attempts = w.attempts + 1,
                               next_attempt_at = now() + interval '1 millisecond' * CAST(:leaseMs AS double precision)
                          FROM due
                         WHERE w.id = due.id
                        RETURNING w.id, w.merchant_id, w.payload::text AS payload, w.attempts""")
                .param("limit", limit)
                .param("leaseMs", lease.toMillis())
                .query(DueWebhook.class)
                .list();
    }

    /** Attempt numbers run on across re-drives, so the history reads 1, 2, 3, … per webhook. */
    void recordDelivery(UUID webhookId, String url, Integer statusCode, long durationMs, String error) {
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (webhook_event_id, attempt, url, status_code, duration_ms, error)
                        VALUES (:id, (SELECT count(*) + 1 FROM webhook_deliveries WHERE webhook_event_id = :id),
                                :url, :statusCode, :durationMs, :error)""")
                .param("id", webhookId)
                .param("url", url)
                .param("statusCode", statusCode)
                .param("durationMs", durationMs)
                .param("error", error == null || error.length() <= 500 ? error : error.substring(0, 500))
                .update();
    }

    void markDelivered(UUID id) {
        jdbc.sql("UPDATE webhook_events SET status = 'DELIVERED', delivered_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    void scheduleRetry(UUID id, Duration delay) {
        jdbc.sql("""
                        UPDATE webhook_events
                           SET next_attempt_at = now() + interval '1 millisecond' * CAST(:delayMs AS double precision)
                         WHERE id = :id""")
                .param("delayMs", delay.toMillis())
                .param("id", id)
                .update();
    }

    void markDead(UUID id) {
        jdbc.sql("UPDATE webhook_events SET status = 'DEAD' WHERE id = :id").param("id", id).update();
    }

    /** Gives a dead webhook a fresh set of attempts. @return false unless it was DEAD */
    boolean redrive(UUID id) {
        return jdbc.sql("""
                        UPDATE webhook_events SET status = 'PENDING', attempts = 0, next_attempt_at = now()
                         WHERE id = :id AND status = 'DEAD'""")
                .param("id", id)
                .update() == 1;
    }

    Optional<WebhookEventRow> find(UUID id) {
        return jdbc.sql(SELECT_EVENT + "\n WHERE id = :id").param("id", id).query(WebhookEventRow.class).optional();
    }

    List<WebhookEventRow> list(String status, int limit) {
        return jdbc.sql(SELECT_EVENT + "\n"
                        + " WHERE (CAST(:status AS varchar) IS NULL OR status = :status)"
                        + " ORDER BY created_at DESC, id LIMIT :limit")
                .param("status", status)
                .param("limit", limit)
                .query(WebhookEventRow.class)
                .list();
    }

    List<DeliveryRow> deliveries(UUID webhookId) {
        return jdbc.sql("""
                        SELECT attempt, url, status_code, duration_ms, error, attempted_at
                          FROM webhook_deliveries WHERE webhook_event_id = :id ORDER BY id""")
                .param("id", webhookId)
                .query(DeliveryRow.class)
                .list();
    }

    record DueWebhook(UUID id, UUID merchantId, String payload, int attempts) {
    }

    record WebhookEventRow(UUID id, UUID merchantId, String eventType, String status, int attempts,
            OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime deliveredAt) {
    }

    record DeliveryRow(int attempt, String url, Integer statusCode, long durationMs, String error,
            OffsetDateTime attemptedAt) {
    }
}
