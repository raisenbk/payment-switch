package com.paymentswitch.merchant.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param token shared operator token for {@code /admin/**} ({@code X-Admin-Token}). Empty (the
 *              default) disables the admin API entirely.
 */
@ConfigurationProperties("payment-switch.admin")
public record AdminProperties(@DefaultValue("") String token) {
}
