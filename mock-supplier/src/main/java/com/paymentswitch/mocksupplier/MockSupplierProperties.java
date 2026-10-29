package com.paymentswitch.mocksupplier;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param delay how long slow behaviours take
 * @param rules first rule whose supplier, suffix and exact amount (each optional) match decides the behaviour
 */
@ConfigurationProperties("mock-supplier")
public record MockSupplierProperties(@DefaultValue("3s") Duration delay, @DefaultValue List<Rule> rules) {

    public record Rule(String supplier, String destinationSuffix, Long amount, Behavior behavior) {

        boolean matches(String supplierCode, String destination, long requestAmount) {
            return (supplier == null || supplier.equalsIgnoreCase(supplierCode))
                    && (destinationSuffix == null || destination.endsWith(destinationSuffix))
                    && (amount == null || amount == requestAmount);
        }
    }

    public Behavior behaviorFor(String supplierCode, String destination, long amount) {
        return rules.stream()
                .filter(rule -> rule.matches(supplierCode, destination, amount))
                .map(Rule::behavior)
                .findFirst()
                .orElse(Behavior.SUCCESS);
    }
}
