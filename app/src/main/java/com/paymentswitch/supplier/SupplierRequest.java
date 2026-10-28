package com.paymentswitch.supplier;

/** @param reference our transaction id; the supplier's idempotency key */
public record SupplierRequest(String reference, String sku, String destination, long amount) {
}
