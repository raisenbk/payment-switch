package com.paymentswitch.supplier;

/**
 * One supplier. Implementations must never throw for supplier or network problems; every outcome
 * is mapped to a {@link SupplierResult}, and anything ambiguous is {@link SupplierResult.Unknown}.
 */
public interface SupplierGateway {

    /** Submits a top-up. {@code request.reference()} is our transaction id; suppliers dedupe on it. */
    SupplierResult submit(SupplierRequest request);

    /** Asks for the outcome of an earlier submit by our reference. */
    SupplierResult inquiry(String reference);
}
