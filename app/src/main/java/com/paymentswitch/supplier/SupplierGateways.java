package com.paymentswitch.supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Public API of the supplier module: the gateway for a supplier code, its submits guarded by that
 * supplier's circuit breaker, and whether the supplier is currently available for routing.
 */
@Component
@EnableConfigurationProperties(SupplierProperties.class)
public class SupplierGateways {

    private final Map<String, SupplierGateway> gateways;
    private final SupplierCircuitBreakers breakers;

    SupplierGateways(SupplierProperties properties, SupplierCircuitBreakers breakers,
            ObservationRegistry observations, MeterRegistry meters) {
        this.breakers = breakers;
        // Breaker outside, metrics inside: only calls that really reach the supplier are timed.
        this.gateways = properties.suppliers().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> normalize(entry.getKey()),
                        entry -> new CircuitBreakingSupplierGateway(
                                new MeteredSupplierGateway(normalize(entry.getKey()),
                                        new HttpSupplierGateway(normalize(entry.getKey()), entry.getValue(),
                                                observations),
                                        meters),
                                breakers.forSupplier(entry.getKey()))));
    }

    /** Configured and its circuit is not open. */
    public boolean isAvailable(String supplierCode) {
        return gateways.containsKey(normalize(supplierCode)) && breakers.isAvailable(supplierCode);
    }

    public Optional<SupplierGateway> forSupplier(String supplierCode) {
        return Optional.ofNullable(gateways.get(normalize(supplierCode)));
    }

    private static String normalize(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}
