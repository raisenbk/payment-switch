package com.paymentswitch.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.supplier.SupplierResult.Success;
import com.paymentswitch.supplier.SupplierResult.Unknown;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakingSupplierGatewayTest {

    private static final SupplierRequest REQUEST = new SupplierRequest("ref", "SKU", "081200000000", 10_000);

    /** Opens after 4 calls with at least 50% unhealthy; trial calls after 10 minutes (never, in a test). */
    private final SupplierCircuitBreakers breakers =
            new SupplierCircuitBreakers(new CircuitBreakerProperties(50, 4, 4, Duration.ofMinutes(10), 1));

    @Test
    void unreachableAndAmbiguousResultsOpenTheCircuit() {
        CountingGateway supplier = new CountingGateway(new Failed(HttpSupplierGateway.UNREACHABLE));
        SupplierGateway gateway = new CircuitBreakingSupplierGateway(supplier, breakers.forSupplier("DOWN"));

        gateway.submit(REQUEST);
        gateway.submit(REQUEST);
        supplier.next = new Unknown("READ_TIMEOUT");
        gateway.submit(REQUEST);
        gateway.submit(REQUEST);

        assertThat(breakers.forSupplier("DOWN").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.isAvailable("down")).isFalse();
        // Open: nothing is sent, and the result is a definitive failure that is safe to fail over from.
        assertThat(gateway.submit(REQUEST)).isEqualTo(new Failed(CircuitBreakingSupplierGateway.CIRCUIT_OPEN));
        assertThat(supplier.submits.get()).isEqualTo(4);
    }

    @Test
    void definitiveBusinessFailuresProveTheSupplierIsHealthy() {
        CountingGateway supplier = new CountingGateway(new Failed("INVALID_DESTINATION"));
        SupplierGateway gateway = new CircuitBreakingSupplierGateway(supplier, breakers.forSupplier("PICKY"));

        for (int i = 0; i < 10; i++) {
            gateway.submit(REQUEST);
        }
        supplier.next = new Success("MS-1");
        gateway.submit(REQUEST);

        assertThat(breakers.forSupplier("PICKY").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(supplier.submits.get()).isEqualTo(11);
    }

    @Test
    void inquiriesBypassTheBreaker() {
        CountingGateway supplier = new CountingGateway(new Success("MS-1"));
        CircuitBreaker breaker = breakers.forSupplier("OPENED");
        breaker.transitionToOpenState();
        SupplierGateway gateway = new CircuitBreakingSupplierGateway(supplier, breaker);

        assertThat(gateway.inquiry("ref")).isEqualTo(new Success("MS-1"));
        assertThat(gateway.submit(REQUEST)).isEqualTo(new Failed(CircuitBreakingSupplierGateway.CIRCUIT_OPEN));
    }

    private static final class CountingGateway implements SupplierGateway {

        final AtomicInteger submits = new AtomicInteger();
        SupplierResult next;

        CountingGateway(SupplierResult next) {
            this.next = next;
        }

        @Override
        public SupplierResult submit(SupplierRequest request) {
            submits.incrementAndGet();
            return next;
        }

        @Override
        public SupplierResult inquiry(String reference) {
            return next;
        }
    }
}
