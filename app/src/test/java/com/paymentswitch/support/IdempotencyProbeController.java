package com.paymentswitch.support;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.idempotency.IdempotencyService;
import com.paymentswitch.common.idempotency.IdempotentResponse;
import com.paymentswitch.common.web.RequestFingerprint;
import com.paymentswitch.merchant.auth.MerchantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint with a countable side effect (a row in {@code test_probe_effects}), used to
 * prove that idempotent requests execute exactly once. Real endpoints arrive in Phase 4.
 */
@TestComponent
@RestController
public class IdempotencyProbeController {

    public static final long MAX_AMOUNT = 1_000_000;

    private final IdempotencyService idempotency;
    private final JdbcClient jdbc;

    public IdempotencyProbeController(IdempotencyService idempotency, JdbcClient jdbc) {
        this.idempotency = idempotency;
        this.jdbc = jdbc;
    }

    record ProbeRequest(@Positive long amount) {
    }

    record ProbeResponse(long effectId, long amount) {
    }

    @PostMapping("/v1/test/probe")
    ResponseEntity<String> create(@AuthenticationPrincipal MerchantPrincipal merchant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProbeRequest body,
            HttpServletRequest request) {
        return idempotency.execute(merchant.merchantId(), idempotencyKey, RequestFingerprint.of(request), () -> {
            if (body.amount() > MAX_AMOUNT) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "AMOUNT_TOO_LARGE", "Amount too large");
            }
            long effectId = jdbc.sql("""
                            INSERT INTO test_probe_effects (merchant_id, amount) VALUES (:m, :a) RETURNING id""")
                    .param("m", merchant.merchantId())
                    .param("a", body.amount())
                    .query(Long.class)
                    .single();
            return new IdempotentResponse(HttpStatus.CREATED, new ProbeResponse(effectId, body.amount()));
        }).toResponseEntity();
    }
}
