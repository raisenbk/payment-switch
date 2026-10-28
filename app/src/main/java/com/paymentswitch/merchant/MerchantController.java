package com.paymentswitch.merchant;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.merchant.auth.MerchantPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MerchantController {

    private final JdbcClient jdbc;

    MerchantController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record MerchantView(UUID id, String name, String status) {
    }

    /** Returns the calling merchant; handy for checking that request signing works. */
    @GetMapping("/v1/me")
    MerchantView me(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return jdbc.sql("SELECT id, name, status FROM merchants WHERE id = :id")
                .param("id", merchant.merchantId())
                .query(MerchantView.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
    }
}
