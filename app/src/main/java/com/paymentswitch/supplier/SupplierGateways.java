package com.paymentswitch.supplier;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Public API of the supplier module: the gateway for a supplier code. */
@Component
@EnableConfigurationProperties(SupplierProperties.class)
public class SupplierGateways {

    private final Map<String, SupplierGateway> gateways;

    SupplierGateways(SupplierProperties properties) {
        this.gateways = properties.suppliers().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> normalize(entry.getKey()),
                        entry -> new HttpSupplierGateway(normalize(entry.getKey()), entry.getValue())));
    }

    public Optional<SupplierGateway> forSupplier(String supplierCode) {
        return Optional.ofNullable(gateways.get(normalize(supplierCode)));
    }

    private static String normalize(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}
