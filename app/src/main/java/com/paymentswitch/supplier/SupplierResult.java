package com.paymentswitch.supplier;

/**
 * Typed supplier outcome. Only {@link Success} and {@link Failed} are definitive; money may move
 * only on those. {@link Unknown} means "we cannot tell", and the hold must stay in place.
 */
public sealed interface SupplierResult {

    record Success(String supplierRef) implements SupplierResult {
    }

    /** The supplier definitely did not and will not fulfil the request. */
    record Failed(String code) implements SupplierResult {
    }

    /** Timeout, 5xx, pending, unreadable response...: the request may or may not have been fulfilled. */
    record Unknown(String reason) implements SupplierResult {
    }
}
