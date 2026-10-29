package com.paymentswitch.supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * One Resilience4j circuit breaker per supplier code. Only supplier <em>health</em> counts as a
 * failure (unreachable, timeout, 5xx, unreadable answer); a definitive business answer such as
 * "invalid number" or "out of stock" proves the supplier is up. Inquiries do not go through it.
 */
@Component
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class SupplierCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(SupplierCircuitBreakers.class);

    private final CircuitBreakerRegistry registry;

    SupplierCircuitBreakers(CircuitBreakerProperties properties, MeterRegistry meters) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .waitDurationInOpenState(properties.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpen())
                // After the wait, move to half-open by itself, so routing offers the supplier trial calls.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        // resilience4j_circuitbreaker_state{name=<supplier>, state=…} and call counters per supplier.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    }

    /** The breaker for a supplier (created on first use). Public so operators and tests can inspect or force it. */
    public CircuitBreaker forSupplier(String supplierCode) {
        String code = supplierCode.toUpperCase(Locale.ROOT);
        return registry.find(code).orElseGet(() -> {
            CircuitBreaker breaker = registry.circuitBreaker(code);
            breaker.getEventPublisher().onStateTransition(event ->
                    log.warn("Circuit breaker for supplier {}: {}", code, event.getStateTransition()));
            return breaker;
        });
    }

    /** Whether routing should consider this supplier: anything but an open circuit. */
    public boolean isAvailable(String supplierCode) {
        CircuitBreaker.State state = forSupplier(supplierCode).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }
}
