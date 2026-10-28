package com.paymentswitch.transaction;

import com.paymentswitch.common.idempotency.IdempotencyService;
import com.paymentswitch.common.idempotency.IdempotentResponse;
import com.paymentswitch.common.web.RequestFingerprint;
import com.paymentswitch.merchant.auth.MerchantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransactionController {

    private final TransactionService transactions;
    private final IdempotencyService idempotency;

    TransactionController(TransactionService transactions, IdempotencyService idempotency) {
        this.transactions = transactions;
        this.idempotency = idempotency;
    }

    record CreateTransactionRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String merchantRef,
            @NotBlank @Size(max = 40) String productCode,
            @NotBlank @Pattern(regexp = "[0-9]{8,20}", message = "must be 8-20 digits") String destination) {
    }

    /** Returns 202 with the PENDING transaction; poll GET (or wait for the webhook) for the outcome. */
    @PostMapping("/v1/transactions")
    ResponseEntity<String> create(@AuthenticationPrincipal MerchantPrincipal merchant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest body,
            HttpServletRequest request) {
        return idempotency.execute(merchant.merchantId(), idempotencyKey, RequestFingerprint.of(request),
                () -> new IdempotentResponse(HttpStatus.ACCEPTED, transactions.create(
                        merchant.merchantId(), body.merchantRef(), body.productCode(), body.destination())))
                .toResponseEntity();
    }

    @GetMapping("/v1/transactions/{id}")
    TransactionView get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID id) {
        return transactions.find(merchant.merchantId(), id);
    }
}
