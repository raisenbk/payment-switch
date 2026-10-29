package com.paymentswitch.recon;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.file.transform.FieldSet;

/**
 * One line of a supplier settlement file (CSV, UTF-8, comma-separated, header required):
 *
 * <pre>
 * reference,supplier_ref,sku,destination,amount,currency,completed_at
 * 5f0c…-uuid,MS-1A2B3C4D,A-PULSA10,081234567890,10000,IDR,2026-10-29T02:06:13Z
 * </pre>
 *
 * {@code reference} is our transaction id, {@code amount} is what the supplier bills us, in minor
 * units. Only successful top-ups are listed.
 */
record SettlementLine(int lineNo, String reference, UUID transactionId, String supplierRef, String sku,
        String destination, long amount, String currency, Instant completedAt) {

    static final List<String> COLUMNS =
            List.of("reference", "supplier_ref", "sku", "destination", "amount", "currency", "completed_at");
    static final String HEADER = String.join(",", COLUMNS);

    static SettlementLine parse(FieldSet fields, int lineNo) {
        String reference = required(fields, "reference", lineNo);
        long amount;
        try {
            amount = Long.parseLong(required(fields, "amount", lineNo));
        } catch (NumberFormatException e) {
            throw invalid(lineNo, "amount must be a whole number of minor units");
        }
        if (amount <= 0) {
            throw invalid(lineNo, "amount must be positive");
        }
        String currency = required(fields, "currency", lineNo);
        if (!currency.matches("[A-Z]{3}")) {
            throw invalid(lineNo, "currency must be an ISO-4217 code");
        }
        Instant completedAt;
        try {
            String value = fields.readString("completed_at");
            completedAt = value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(lineNo, "completed_at must be an ISO-8601 instant");
        }
        return new SettlementLine(lineNo, reference, asUuid(reference), blankToNull(fields.readString("supplier_ref")),
                blankToNull(fields.readString("sku")), blankToNull(fields.readString("destination")), amount,
                currency, completedAt);
    }

    private static String required(FieldSet fields, String name, int lineNo) {
        String value = fields.readString(name);
        if (value == null || value.isBlank()) {
            throw invalid(lineNo, name + " is required");
        }
        return value.strip();
    }

    private static UUID asUuid(String reference) {
        try {
            return UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            return null; // not one of our ids: reconciles as MISSING_INTERNAL
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static InvalidSettlementFileException invalid(int lineNo, String message) {
        return new InvalidSettlementFileException("line " + lineNo + ": " + message);
    }

    static class InvalidSettlementFileException extends RuntimeException {

        InvalidSettlementFileException(String message) {
            super(message);
        }
    }
}
