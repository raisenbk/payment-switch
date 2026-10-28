package com.paymentswitch.supplier;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Supplier endpoints keyed by supplier code (case-insensitive), e.g.
 * {@code payment-switch.suppliers.mocka.base-url}.
 */
@ConfigurationProperties("payment-switch")
public record SupplierProperties(@DefaultValue Map<String, Endpoint> suppliers) {

    /**
     * @param connectTimeout how long to wait for a TCP connection; failing here means nothing was sent
     * @param readTimeout    how long to wait for the answer; failing here makes the outcome unknown
     */
    public record Endpoint(URI baseUrl, @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {
    }
}
