package com.paymentswitch.transaction;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TransactionRepository {

    private static final String SELECT = """
            SELECT t.id, t.merchant_id, t.merchant_ref, t.product_id, p.code AS product_code, t.destination,
                   t.amount, t.currency, t.status, t.supplier_id, s.code AS supplier_code, t.supplier_sku,
                   t.supplier_cost, t.supplier_ref, t.failure_code, t.created_at, t.updated_at
              FROM transactions t
              JOIN products p ON p.id = t.product_id
              LEFT JOIN suppliers s ON s.id = t.supplier_id
            """;

    private final JdbcClient jdbc;

    TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if the merchant already used this merchant_ref */
    boolean insertPending(UUID id, UUID merchantId, String merchantRef, long productId, String destination,
            long amount, String currency) {
        return jdbc.sql("""
                        INSERT INTO transactions
                            (id, merchant_id, merchant_ref, product_id, destination, amount, currency, status)
                        VALUES (:id, :merchantId, :merchantRef, :productId, :destination, :amount, :currency, 'PENDING')
                        ON CONFLICT (merchant_id, merchant_ref) DO NOTHING""")
                .param("id", id)
                .param("merchantId", merchantId)
                .param("merchantRef", merchantRef)
                .param("productId", productId)
                .param("destination", destination)
                .param("amount", amount)
                .param("currency", currency)
                .update() == 1;
    }

    Optional<TransactionRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id")
                .param("id", id)
                .query(TransactionRow.class)
                .optional();
    }

    Optional<TransactionRow> findForMerchant(UUID id, UUID merchantId) {
        return jdbc.sql(SELECT + " WHERE t.id = :id AND t.merchant_id = :merchantId")
                .param("id", id)
                .param("merchantId", merchantId)
                .query(TransactionRow.class)
                .optional();
    }

    /**
     * Moves the status only if it is still {@code from} (compare-and-set), so two workers can
     * never both apply a transition. Non-null fields in {@code change} are written; nulls keep
     * the current value.
     */
    boolean compareAndSetStatus(UUID id, TransactionStatus from, TransactionStatus to, StatusChange change) {
        return jdbc.sql("""
                        UPDATE transactions
                           SET status = :to,
                               supplier_id = COALESCE(:supplierId, supplier_id),
                               supplier_sku = COALESCE(:supplierSku, supplier_sku),
                               supplier_cost = COALESCE(:supplierCost, supplier_cost),
                               supplier_ref = COALESCE(:supplierRef, supplier_ref),
                               failure_code = COALESCE(:failureCode, failure_code),
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = :from""")
                .param("to", to.name())
                .param("supplierId", change.supplierId())
                .param("supplierSku", change.supplierSku())
                .param("supplierCost", change.supplierCost())
                .param("supplierRef", change.supplierRef())
                .param("failureCode", change.failureCode())
                .param("id", id)
                .param("from", from.name())
                .update() == 1;
    }

    /** Starts the inquiry schedule for a transaction that just became UNKNOWN (or FAILED, to verify). */
    void scheduleInquiry(UUID id, Duration firstDelay, Duration window) {
        jdbc.sql("""
                        UPDATE transactions
                           SET next_inquiry_at = now() + interval '1 millisecond' * CAST(:delayMs AS double precision),
                               inquiry_deadline = now() + interval '1 millisecond' * CAST(:windowMs AS double precision),
                               inquiry_attempts = 0
                         WHERE id = :id""")
                .param("delayMs", firstDelay.toMillis())
                .param("windowMs", window.toMillis())
                .param("id", id)
                .update();
    }

    void clearInquiry(UUID id) {
        jdbc.sql("UPDATE transactions SET next_inquiry_at = NULL WHERE id = :id").param("id", id).update();
    }

    /**
     * Claims due inquiries like the outbox claims events: {@code FOR UPDATE SKIP LOCKED} keeps
     * concurrent schedulers apart, and moving {@code next_inquiry_at} to the next backoff step
     * (initial delay doubled per attempt, capped) is both the lease and the retry schedule.
     */
    List<DueInquiry> claimDueInquiries(int limit, Duration initialDelay, Duration maxDelay) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM transactions
                             WHERE next_inquiry_at <= now() AND status IN ('UNKNOWN', 'FAILED')
                             ORDER BY next_inquiry_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                        UPDATE transactions t
                           SET inquiry_attempts = t.inquiry_attempts + 1,
                               next_inquiry_at = now() + interval '1 millisecond' * LEAST(
                                       CAST(:initialMs AS double precision) * power(2, LEAST(t.inquiry_attempts + 1, 30)),
                                       CAST(:maxMs AS double precision))
                          FROM due
                         WHERE t.id = due.id
                        RETURNING t.id, t.merchant_id, t.status, t.inquiry_attempts AS attempts,
                                  t.inquiry_deadline <= now() AS deadline_passed,
                                  (SELECT code FROM suppliers s WHERE s.id = t.supplier_id) AS supplier_code""")
                .param("limit", limit)
                .param("initialMs", initialDelay.toMillis())
                .param("maxMs", maxDelay.toMillis())
                .query(DueInquiry.class)
                .list();
    }

    /** @return false if this discrepancy was already recorded */
    boolean insertDiscrepancy(UUID transactionId, String kind, TransactionStatus ourStatus, String supplierRef,
            String supplierCode) {
        return jdbc.sql("""
                        INSERT INTO transaction_discrepancies (transaction_id, kind, our_status, supplier_ref, supplier_code)
                        VALUES (:id, :kind, :ourStatus, :supplierRef, :supplierCode)
                        ON CONFLICT (transaction_id, kind) DO NOTHING""")
                .param("id", transactionId)
                .param("kind", kind)
                .param("ourStatus", ourStatus.name())
                .param("supplierRef", supplierRef)
                .param("supplierCode", supplierCode)
                .update() == 1;
    }

    void insertEvent(UUID transactionId, TransactionStatus from, TransactionStatus to, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO transaction_events (transaction_id, from_status, to_status, payload)
                        VALUES (:id, :from, :to, CAST(:payload AS jsonb))""")
                .param("id", transactionId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("payload", payloadJson)
                .update();
    }

    List<EventRow> events(UUID transactionId) {
        return jdbc.sql("""
                        SELECT from_status, to_status, payload::text AS payload, created_at
                          FROM transaction_events WHERE transaction_id = :id ORDER BY id""")
                .param("id", transactionId)
                .query(EventRow.class)
                .list();
    }

    record TransactionRow(UUID id, UUID merchantId, String merchantRef, long productId, String productCode,
            String destination, long amount, String currency, TransactionStatus status, Long supplierId,
            String supplierCode, String supplierSku, Long supplierCost, String supplierRef, String failureCode,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** A claimed inquiry: {@code status} is UNKNOWN, or FAILED for the late-success verification. */
    record DueInquiry(UUID id, UUID merchantId, TransactionStatus status, int attempts, boolean deadlinePassed,
            String supplierCode) {
    }

    record EventRow(String fromStatus, String toStatus, String payload, OffsetDateTime createdAt) {
    }

    /** Optional fields written together with a status change. */
    record StatusChange(Long supplierId, String supplierSku, Long supplierCost, String supplierRef,
            String failureCode) {

        static final StatusChange NONE = new StatusChange(null, null, null, null, null);

        static StatusChange routed(long supplierId, long supplierCost, String supplierSku) {
            return new StatusChange(supplierId, supplierSku, supplierCost, null, null);
        }

        static StatusChange succeeded(String supplierRef) {
            return new StatusChange(null, null, null, supplierRef, null);
        }

        static StatusChange failed(String failureCode) {
            return new StatusChange(null, null, null, null, failureCode);
        }
    }
}
