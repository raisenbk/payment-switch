package com.paymentswitch.mocksupplier;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param delay how long slow behaviours take
 * @param rules first rule whose suffix (and optional exact amount) matches decides the behaviour
 */
@ConfigurationProperties("mock-supplier")
public record MockSupplierProperties(@DefaultValue("3s") Duration delay, @DefaultValue List<Rule> rules) {

    public record Rule(String destinationSuffix, Long amount, Behavior behavior) {

        boolean matches(String destination, long requestAmount) {
            return (destinationSuffix == null || destination.endsWith(destinationSuffix))
                    && (amount == null || amount == requestAmount);
        }
    }

    public Behavior behaviorFor(String destination, long amount) {
        return rules.stream()
                .filter(rule -> rule.matches(destination, amount))
                .map(Rule::behavior)
                .findFirst()
                .orElse(Behavior.SUCCESS);
    }
}
