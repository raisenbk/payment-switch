package com.paymentswitch.transaction;

import com.paymentswitch.transaction.TransactionRepository.TransactionRow;
import java.time.OffsetDateTime;
import java.util.UUID;

/** What merchants see of a transaction. Supplier choice and cost are internal and not exposed. */
public record TransactionView(UUID id, String merchantRef, String productCode, String destination, long amount,
        String currency, TransactionStatus status, String supplierRef, String failureCode,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    static TransactionView of(TransactionRow row) {
        return new TransactionView(row.id(), row.merchantRef(), row.productCode(), row.destination(), row.amount(),
                row.currency(), row.status(), row.supplierRef(), row.failureCode(), row.createdAt(),
                row.updatedAt());
    }
}
