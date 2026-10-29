package com.paymentswitch.routing;

import java.math.BigDecimal;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minSuccessRate          mappings below this observed success rate are only used when no
 *                                healthier one is eligible, whatever their priority
 * @param maxSuppliersPerTransaction how many suppliers one transaction may try (1 = no failover)
 * @param nonRetryableFailures    definitive failure codes that another supplier would answer the
 *                                same way (the customer's input is wrong), so no failover
 */
@ConfigurationProperties("payment-switch.routing")
public record RoutingProperties(
        @DefaultValue("0.80") BigDecimal minSuccessRate,
        @DefaultValue("3") int maxSuppliersPerTransaction,
        @DefaultValue("INVALID_DESTINATION") Set<String> nonRetryableFailures) {
}
