package com.paymentswitch.supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;

/**
 * Times every supplier call: {@code paymentswitch.supplier.calls{supplier, operation, outcome}}
 * with a histogram, so dashboards can show p95/p99 latency per supplier and the share of
 * ambiguous (unknown) answers.
 */
class MeteredSupplierGateway implements SupplierGateway {

    private final String supplierCode;
    private final SupplierGateway delegate;
    private final MeterRegistry meters;

    MeteredSupplierGateway(String supplierCode, SupplierGateway delegate, MeterRegistry meters) {
        this.supplierCode = supplierCode;
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public SupplierResult submit(SupplierRequest request) {
        return timed("submit", () -> delegate.submit(request));
    }

    @Override
    public SupplierResult inquiry(String reference) {
        return timed("inquiry", () -> delegate.inquiry(reference));
    }

    private SupplierResult timed(String operation, Supplier<SupplierResult> call) {
        Timer.Sample sample = Timer.start(meters);
        SupplierResult result = call.get();
        sample.stop(Timer.builder("paymentswitch.supplier.calls")
                .description("Calls to suppliers")
                .tag("supplier", supplierCode)
                .tag("operation", operation)
                .tag("outcome", switch (result) {
                    case SupplierResult.Success s -> "success";
                    case SupplierResult.Failed f -> "failed";
                    case SupplierResult.Unknown u -> "unknown";
                })
                .publishPercentileHistogram()
                .register(meters));
        return result;
    }
}
