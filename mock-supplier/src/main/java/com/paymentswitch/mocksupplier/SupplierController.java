package com.paymentswitch.mocksupplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A fake prepaid supplier. Several suppliers are simulated by path ({@code /suppliers/{code}}).
 * Like a well-behaved real supplier it is idempotent on the caller's {@code reference}: a second
 * submit returns 409 with the stored outcome instead of processing again.
 */
@RestController
class SupplierController {

    private final MockSupplierProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Record> records = new ConcurrentHashMap<>();

    SupplierController(MockSupplierProperties properties) {
        this.properties = properties;
    }

    record SubmitRequest(@NotBlank String reference, @NotBlank String sku, @NotBlank String destination,
            @Positive long amount) {
    }

    record SupplierResponse(String reference, String status, String supplierRef, String errorCode) {
    }

    private record Record(String reference, String status, String supplierRef, String errorCode, Instant completeAt) {

        SupplierResponse toResponse() {
            return new SupplierResponse(reference, status, supplierRef, errorCode);
        }
    }

    @PostMapping("/suppliers/{supplier}/transactions")
    ResponseEntity<SupplierResponse> submit(@PathVariable String supplier, @Valid @RequestBody SubmitRequest request)
            throws InterruptedException {
        String key = supplier + ":" + request.reference();
        Behavior behavior = properties.behaviorFor(request.destination(), request.amount());
        if (behavior == Behavior.REJECT) {
            return ResponseEntity.badRequest()
                    .body(new SupplierResponse(request.reference(), "REJECTED", null, "INVALID_SKU"));
        }

        Record created = initialRecord(request.reference(), behavior);
        Record existing = records.putIfAbsent(key, created);
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(current(key).toResponse());
        }

        return switch (behavior) {
            case TIMEOUT_THEN_SUCCESS, TIMEOUT_THEN_FAIL -> {
                Thread.sleep(properties.delay());
                yield ResponseEntity.ok(created.toResponse());
            }
            case SERVER_ERROR_AFTER_SUCCESS -> ResponseEntity.internalServerError().build();
            default -> ResponseEntity.ok(created.toResponse());
        };
    }

    @GetMapping("/suppliers/{supplier}/transactions/{reference}")
    ResponseEntity<SupplierResponse> inquiry(@PathVariable String supplier, @PathVariable String reference) {
        Record record = current(supplier + ":" + reference);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record.toResponse());
    }

    private Record initialRecord(String reference, Behavior behavior) {
        String supplierRef = "MS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return switch (behavior) {
            case FAIL, TIMEOUT_THEN_FAIL -> new Record(reference, "FAILED", null, "INVALID_DESTINATION", null);
            case PENDING_THEN_SUCCESS ->
                    new Record(reference, "PENDING", supplierRef, null, clock.instant().plus(properties.delay()));
            default -> new Record(reference, "SUCCESS", supplierRef, null, null);
        };
    }

    /** Resolves pending records whose completion time has passed. */
    private Record current(String key) {
        return records.computeIfPresent(key, (k, record) ->
                record.completeAt() != null && !clock.instant().isBefore(record.completeAt())
                        ? new Record(record.reference(), "SUCCESS", record.supplierRef(), null, null)
                        : record);
    }
}
