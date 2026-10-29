package com.paymentswitch.supplier;

import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.supplier.SupplierResult.Unknown;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeUnit;

/**
 * Puts a supplier's submits behind its circuit breaker. With the circuit open nothing is sent and
 * the result is a definitive {@code Failed("CIRCUIT_OPEN")}, which is safe to fail over from.
 */
class CircuitBreakingSupplierGateway implements SupplierGateway {

    static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final SupplierGateway delegate;
    private final CircuitBreaker breaker;

    CircuitBreakingSupplierGateway(SupplierGateway delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public SupplierResult submit(SupplierRequest request) {
        if (!breaker.tryAcquirePermission()) {
            return new Failed(CIRCUIT_OPEN);
        }
        long started = System.nanoTime();
        SupplierResult result = delegate.submit(request);
        long elapsed = System.nanoTime() - started;
        if (unhealthy(result)) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, new SupplierUnhealthyException(result));
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        }
        return result;
    }

    /** Inquiries are read-only and already spaced out by backoff; they bypass the breaker. */
    @Override
    public SupplierResult inquiry(String reference) {
        return delegate.inquiry(reference);
    }

    /** Ambiguous outcomes (timeout, 5xx, garbage) and unreachable suppliers count against health. */
    static boolean unhealthy(SupplierResult result) {
        return result instanceof Unknown
                || (result instanceof Failed failed && HttpSupplierGateway.UNREACHABLE.equals(failed.code()));
    }

    static class SupplierUnhealthyException extends RuntimeException {

        SupplierUnhealthyException(SupplierResult result) {
            super(result.toString(), null, false, false);
        }
    }
}
