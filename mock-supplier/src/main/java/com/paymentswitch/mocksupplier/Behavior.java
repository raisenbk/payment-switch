package com.paymentswitch.mocksupplier;

/** How the simulated supplier reacts to a top-up request. */
public enum Behavior {
    /** 200 SUCCESS immediately. */
    SUCCESS,
    /** 200 FAILED immediately: a definitive business failure (e.g. invalid number). */
    FAIL,
    /** 200 FAILED immediately with OUT_OF_STOCK: definitive, but another supplier may have stock. */
    OUT_OF_STOCK,
    /** Processes successfully but answers only after the configured delay (caller times out). */
    TIMEOUT_THEN_SUCCESS,
    /** Fails but answers only after the configured delay (caller times out). */
    TIMEOUT_THEN_FAIL,
    /** 200 PENDING now; inquiries return SUCCESS once the delay has passed. */
    PENDING_THEN_SUCCESS,
    /** Processes successfully, then answers 500: the classic ambiguous result. */
    SERVER_ERROR_AFTER_SUCCESS,
    /** 400 without processing: the request itself was rejected. */
    REJECT,
    /** 200 PENDING, and inquiries keep answering PENDING: a supplier that never resolves. */
    STUCK_PENDING,
    /**
     * Processes slowly: the caller times out, and inquiries answer 404 until the delay has passed,
     * then SUCCESS. Breaks the "every accepted submit is queryable" contract, so the switch fails the
     * transaction first and must detect the late success.
     */
    SLOW_SUCCESS
}
