package com.paymentswitch.supplier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-supplier circuit breaker (count-based sliding window).
 *
 * @param failureRateThreshold    percentage of unhealthy calls that opens the circuit
 * @param slidingWindowSize       how many recent submits are considered
 * @param minimumNumberOfCalls    calls needed before the failure rate is evaluated
 * @param waitInOpenState         how long the circuit stays open before trial calls are allowed
 * @param permittedCallsInHalfOpen trial calls that decide whether to close the circuit again
 */
@ConfigurationProperties("payment-switch.circuit-breaker")
record CircuitBreakerProperties(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumNumberOfCalls,
        @DefaultValue("30s") Duration waitInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpen) {
}
