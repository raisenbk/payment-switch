package com.paymentswitch.merchant;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.ledger.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Operator API (X-Admin-Token): credit a merchant's balance after their deposit arrived. */
@RestController
class MerchantAdminController {

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    MerchantAdminController(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * @param reference the deposit's reference (e.g. bank transfer id); the same reference books the
     *                  top-up once, so retries are safe
     */
    record TopUpRequest(@Positive long amount, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String reference) {
    }

    record TopUpResponse(UUID journalId, Balance balance) {
    }

    @PostMapping("/admin/merchants/{merchantId}/top-ups")
    @Transactional
    ResponseEntity<TopUpResponse> topUp(@PathVariable UUID merchantId, @Valid @RequestBody TopUpRequest body) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM merchants WHERE id = :id)")
                .param("id", merchantId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found");
        }
        UUID journalId = ledger.topUp(merchantId, Money.of(body.amount(), body.currency()), body.reference());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TopUpResponse(journalId, ledger.getBalance(merchantId, body.currency())));
    }
}
