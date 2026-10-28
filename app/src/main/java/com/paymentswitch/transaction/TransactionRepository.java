package com.paymentswitch.transaction;

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
                   t.amount, t.currency, t.status, t.supplier_id, t.supplier_cost, t.supplier_ref,
                   t.failure_code, t.created_at, t.updated_at
              FROM transactions t JOIN products p ON p.id = t.product_id
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
                               supplier_cost = COALESCE(:supplierCost, supplier_cost),
                               supplier_ref = COALESCE(:supplierRef, supplier_ref),
                               failure_code = COALESCE(:failureCode, failure_code),
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = :from""")
                .param("to", to.name())
                .param("supplierId", change.supplierId())
                .param("supplierCost", change.supplierCost())
                .param("supplierRef", change.supplierRef())
                .param("failureCode", change.failureCode())
                .param("id", id)
                .param("from", from.name())
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
            Long supplierCost, String supplierRef, String failureCode, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record EventRow(String fromStatus, String toStatus, String payload, OffsetDateTime createdAt) {
    }

    /** Optional fields written together with a status change. */
    record StatusChange(Long supplierId, Long supplierCost, String supplierRef, String failureCode) {

        static final StatusChange NONE = new StatusChange(null, null, null, null);

        static StatusChange routed(long supplierId, long supplierCost) {
            return new StatusChange(supplierId, supplierCost, null, null);
        }

        static StatusChange succeeded(String supplierRef) {
            return new StatusChange(null, null, supplierRef, null);
        }

        static StatusChange failed(String failureCode) {
            return new StatusChange(null, null, null, failureCode);
        }
    }
}
