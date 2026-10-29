package com.paymentswitch.recon;

import com.paymentswitch.recon.ReconClassifier.Result;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReconRepository {

    private static final String SELECT_FILE = """
            SELECT f.id, s.code AS supplier_code, f.supplier_id, f.business_date, f.file_name, f.checksum, f.status,
                   f.line_count, f.imported_at, f.completed_at
              FROM settlement_files f JOIN suppliers s ON s.id = f.supplier_id
            """;

    private final JdbcClient jdbc;

    ReconRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> supplierId(String code) {
        return jdbc.sql("SELECT id FROM suppliers WHERE upper(code) = upper(:code)")
                .param("code", code)
                .query(Long.class)
                .optional();
    }

    /** @return the new file id, or empty if a file with this checksum was already imported */
    Optional<Long> insertFile(long supplierId, LocalDate businessDate, String fileName, String checksum,
            String content) {
        return jdbc.sql("""
                        INSERT INTO settlement_files (supplier_id, business_date, file_name, checksum, content)
                        VALUES (:supplierId, :businessDate, :fileName, :checksum, :content)
                        ON CONFLICT (checksum) DO NOTHING
                        RETURNING id""")
                .param("supplierId", supplierId)
                .param("businessDate", businessDate)
                .param("fileName", fileName)
                .param("checksum", checksum)
                .param("content", content)
                .query(Long.class)
                .optional();
    }

    Optional<FileRow> findFile(long id) {
        return jdbc.sql(SELECT_FILE + " WHERE f.id = :id").param("id", id).query(FileRow.class).optional();
    }

    Optional<FileRow> findFileByChecksum(String checksum) {
        return jdbc.sql(SELECT_FILE + " WHERE f.checksum = :checksum")
                .param("checksum", checksum)
                .query(FileRow.class)
                .optional();
    }

    List<FileRow> listFiles(int limit) {
        return jdbc.sql(SELECT_FILE + " ORDER BY f.imported_at DESC, f.id DESC LIMIT :limit")
                .param("limit", limit)
                .query(FileRow.class)
                .list();
    }

    String content(long fileId) {
        return jdbc.sql("SELECT content FROM settlement_files WHERE id = :id")
                .param("id", fileId)
                .query(String.class)
                .single();
    }

    void insertLine(long fileId, SettlementLine line) {
        jdbc.sql("""
                        INSERT INTO settlement_lines (settlement_file_id, line_no, reference, transaction_id, supplier_ref,
                                                      sku, destination, amount, currency, completed_at)
                        VALUES (:fileId, :lineNo, :reference, :transactionId, :supplierRef, :sku, :destination, :amount,
                                :currency, :completedAt)""")
                .param("fileId", fileId)
                .param("lineNo", line.lineNo())
                .param("reference", line.reference())
                .param("transactionId", line.transactionId())
                .param("supplierRef", line.supplierRef())
                .param("sku", line.sku())
                .param("destination", line.destination())
                .param("amount", line.amount())
                .param("currency", line.currency())
                .param("completedAt", line.completedAt() == null ? null
                        : line.completedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    void insertResult(long fileId, Result result) {
        jdbc.sql("""
                        INSERT INTO recon_results (settlement_file_id, match_status, transaction_id, line_no, reference,
                                                   supplier_ref, internal_status, internal_amount, supplier_amount,
                                                   diff_amount, currency)
                        VALUES (:fileId, :status, :transactionId, :lineNo, :reference, :supplierRef, :internalStatus,
                                :internalAmount, :supplierAmount, :diffAmount, :currency)""")
                .param("fileId", fileId)
                .param("status", result.status().name())
                .param("transactionId", result.transactionId())
                .param("lineNo", result.lineNo())
                .param("reference", result.reference())
                .param("supplierRef", result.supplierRef())
                .param("internalStatus", result.internalStatus())
                .param("internalAmount", result.internalAmount())
                .param("supplierAmount", result.supplierAmount())
                .param("diffAmount", result.diffAmount())
                .param("currency", result.currency())
                .update();
    }

    /**
     * Our successful transactions with this supplier that completed in {@code [from, to)} but are not in
     * the file: the supplier does not bill them although we booked them as delivered.
     */
    int insertMissingSupplier(long fileId, long supplierId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        INSERT INTO recon_results (settlement_file_id, match_status, transaction_id, reference,
                                                   supplier_ref, internal_status, internal_amount, currency)
                        SELECT :fileId, 'MISSING_SUPPLIER', t.id, t.id::text, t.supplier_ref, t.status,
                               t.supplier_cost, t.currency
                          FROM transactions t
                         WHERE t.supplier_id = :supplierId AND t.status = 'SUCCESS'
                           AND t.updated_at >= :from AND t.updated_at < :to
                           AND NOT EXISTS (SELECT 1 FROM settlement_lines l
                                            WHERE l.settlement_file_id = :fileId AND l.transaction_id = t.id)""")
                .param("fileId", fileId)
                .param("supplierId", supplierId)
                .param("from", from)
                .param("to", to)
                .update();
    }

    void markCompleted(long fileId) {
        jdbc.sql("""
                        UPDATE settlement_files
                           SET status = 'COMPLETED', completed_at = now(),
                               line_count = (SELECT count(*) FROM settlement_lines WHERE settlement_file_id = :id)
                         WHERE id = :id""")
                .param("id", fileId)
                .update();
    }

    /** Removes a file whose import failed, with its lines and results, so it can be corrected and sent again. */
    void deleteFile(long fileId) {
        jdbc.sql("DELETE FROM settlement_files WHERE id = :id").param("id", fileId).update();
    }

    Map<String, Long> summary(long fileId) {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (MatchStatus status : MatchStatus.values()) {
            summary.put(status.name(), 0L);
        }
        jdbc.sql("SELECT match_status, count(*) FROM recon_results WHERE settlement_file_id = :id GROUP BY match_status")
                .param("id", fileId)
                .query((rs, row) -> summary.put(rs.getString(1), rs.getLong(2)))
                .list();
        return summary;
    }

    List<ResultRow> discrepancies(long fileId) {
        return jdbc.sql("""
                        SELECT match_status, transaction_id, line_no, reference, supplier_ref, internal_status,
                               internal_amount, supplier_amount, diff_amount, currency
                          FROM recon_results
                         WHERE settlement_file_id = :id AND match_status <> 'MATCHED'
                         ORDER BY match_status, line_no NULLS LAST, id""")
                .param("id", fileId)
                .query(ResultRow.class)
                .list();
    }

    record FileRow(long id, String supplierCode, long supplierId, LocalDate businessDate, String fileName,
            String checksum, String status, Integer lineCount, OffsetDateTime importedAt,
            OffsetDateTime completedAt) {
    }

    record ResultRow(String matchStatus, UUID transactionId, Integer lineNo, String reference, String supplierRef,
            String internalStatus, Long internalAmount, Long supplierAmount, Long diffAmount, String currency) {
    }
}
