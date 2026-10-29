package com.paymentswitch.mocksupplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A fake prepaid supplier. Several suppliers are simulated by path ({@code /suppliers/{code}}).
 * Like a well-behaved real supplier it is idempotent on the caller's {@code reference}: a second
 * submit returns 409 with the stored outcome instead of processing again. It also produces the
 * daily settlement file that the switch reconciles against.
 */
@RestController
class SupplierController {

    static final String SETTLEMENT_HEADER = "reference,supplier_ref,sku,destination,amount,currency,completed_at";

    private final MockSupplierProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Record> records = new ConcurrentHashMap<>();

    SupplierController(MockSupplierProperties properties) {
        this.properties = properties;
    }

    /** @param amount what we bill the switch for this top-up (the agreed supplier price) */
    record SubmitRequest(@NotBlank String reference, @NotBlank String sku, @NotBlank String destination,
            @Positive long amount) {
    }

    record SupplierResponse(String reference, String status, String supplierRef, String errorCode) {
    }

    /**
     * @param completeAt when a PENDING record turns SUCCESS (null: never changes)
     * @param visibleAt  inquiries answer 404 before this time (null: visible immediately)
     */
    private record Record(String supplier, SubmitRequest request, String status, String supplierRef,
            String errorCode, Instant createdAt, Instant completeAt, Instant visibleAt) {

        SupplierResponse toResponse() {
            return new SupplierResponse(request.reference(), status, supplierRef, errorCode);
        }

        Record succeeded() {
            return new Record(supplier, request, "SUCCESS", supplierRef, null, createdAt, null, visibleAt);
        }
    }

    @PostMapping("/suppliers/{supplier}/transactions")
    ResponseEntity<SupplierResponse> submit(@PathVariable String supplier, @Valid @RequestBody SubmitRequest request)
            throws InterruptedException {
        String key = key(supplier, request.reference());
        Behavior behavior = properties.behaviorFor(supplier, request.destination(), request.amount());
        if (behavior == Behavior.REJECT) {
            return ResponseEntity.badRequest()
                    .body(new SupplierResponse(request.reference(), "REJECTED", null, "INVALID_SKU"));
        }

        Record created = initialRecord(supplier, request, behavior);
        Record existing = records.putIfAbsent(key, created);
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(current(key).toResponse());
        }

        return switch (behavior) {
            case TIMEOUT_THEN_SUCCESS, TIMEOUT_THEN_FAIL, SLOW_SUCCESS -> {
                Thread.sleep(properties.delay());
                yield ResponseEntity.ok(created.toResponse());
            }
            case SERVER_ERROR_AFTER_SUCCESS -> ResponseEntity.internalServerError().build();
            default -> ResponseEntity.ok(created.toResponse());
        };
    }

    @GetMapping("/suppliers/{supplier}/transactions/{reference}")
    ResponseEntity<SupplierResponse> inquiry(@PathVariable String supplier, @PathVariable String reference) {
        Record record = current(key(supplier, reference));
        if (record == null || (record.visibleAt() != null && clock.instant().isBefore(record.visibleAt()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record.toResponse());
    }

    /**
     * The settlement file for one UTC day: every successful top-up this supplier bills the switch for.
     * With {@code discrepancies=true} it contains one of each mistake reconciliation must catch
     * (given at least three successful records): a missing line, a line the switch never sent, a
     * wrong amount and a duplicate.
     */
    @GetMapping(value = "/suppliers/{supplier}/settlements/{date}", produces = "text/csv")
    String settlement(@PathVariable String supplier, @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean discrepancies) {
        List<String> lines = new ArrayList<>(records.keySet().stream()
                .filter(key -> key.startsWith(supplier.toUpperCase() + ":"))
                .map(this::current)
                .filter(record -> "SUCCESS".equals(record.status()))
                .filter(record -> record.createdAt().atZone(ZoneOffset.UTC).toLocalDate().equals(date))
                .sorted(Comparator.comparing(Record::createdAt))
                .map(SupplierController::settlementLine)
                .toList());
        if (discrepancies && lines.size() >= 3) {
            // The switch succeeded but the supplier "forgot" it: MISSING_SUPPLIER.
            lines.removeFirst();
            // Billed 100 more than agreed: AMOUNT_DIFF.
            lines.set(0, withAmountIncreasedBy(lines.getFirst(), 100));
            // Billed twice: DUPLICATE.
            lines.add(lines.get(1));
            // Billed for something the switch never sent: MISSING_INTERNAL.
            lines.add(String.join(",", UUID.randomUUID().toString(), "MS-GHOST001", "A-PULSA10", "081200000000",
                    "10000", "IDR", date.atStartOfDay().toInstant(ZoneOffset.UTC).toString()));
        }
        return SETTLEMENT_HEADER + "\n" + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    private static String settlementLine(Record record) {
        return String.join(",", record.request().reference(), record.supplierRef(), record.request().sku(),
                record.request().destination(), Long.toString(record.request().amount()), "IDR",
                record.createdAt().toString());
    }

    private static String withAmountIncreasedBy(String line, long delta) {
        String[] fields = line.split(",");
        fields[4] = Long.toString(Long.parseLong(fields[4]) + delta);
        return String.join(",", fields);
    }

    private Record initialRecord(String supplier, SubmitRequest request, Behavior behavior) {
        String supplierRef = "MS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = clock.instant();
        return switch (behavior) {
            case FAIL, TIMEOUT_THEN_FAIL ->
                    new Record(supplier, request, "FAILED", null, "INVALID_DESTINATION", now, null, null);
            case OUT_OF_STOCK -> new Record(supplier, request, "FAILED", null, "OUT_OF_STOCK", now, null, null);
            case PENDING_THEN_SUCCESS ->
                    new Record(supplier, request, "PENDING", supplierRef, null, now, now.plus(properties.delay()), null);
            case STUCK_PENDING -> new Record(supplier, request, "PENDING", supplierRef, null, now, null, null);
            case SLOW_SUCCESS ->
                    new Record(supplier, request, "SUCCESS", supplierRef, null, now, null, now.plus(properties.delay()));
            default -> new Record(supplier, request, "SUCCESS", supplierRef, null, now, null, null);
        };
    }

    /** Resolves pending records whose completion time has passed. */
    private Record current(String key) {
        return records.computeIfPresent(key, (k, record) ->
                record.completeAt() != null && !clock.instant().isBefore(record.completeAt())
                        ? record.succeeded()
                        : record);
    }

    private static String key(String supplier, String reference) {
        return supplier.toUpperCase() + ":" + reference;
    }
}
