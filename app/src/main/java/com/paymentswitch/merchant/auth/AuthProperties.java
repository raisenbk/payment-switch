package com.paymentswitch.merchant.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param timestampTolerance how far {@code X-Timestamp} may differ from server time (replay window)
 * @param maxBodySize        largest request body accepted on signed endpoints
 */
@ConfigurationProperties("payment-switch.auth")
public record AuthProperties(
        @DefaultValue("5m") Duration timestampTolerance,
        @DefaultValue("64KB") DataSize maxBodySize) {
}
