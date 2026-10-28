package com.paymentswitch.merchant.auth;

import java.util.UUID;

/** The authenticated merchant; inject with {@code @AuthenticationPrincipal MerchantPrincipal}. */
public record MerchantPrincipal(UUID merchantId) {
}
