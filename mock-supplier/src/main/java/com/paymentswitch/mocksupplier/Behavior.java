package com.paymentswitch.mocksupplier;

/** How the simulated supplier reacts to a top-up request. */
public enum Behavior {
    /** 200 SUCCESS immediately. */
    SUCCESS,
    /** 200 FAILED immediately: a definitive business failure (e.g. invalid number). */
    FAIL,
    /** Processes successfully but answers only after the configured delay (caller times out). */
    TIMEOUT_THEN_SUCCESS,
    /** Fails but answers only after the configured delay (caller times out). */
    TIMEOUT_THEN_FAIL,
    /** 200 PENDING now; inquiries return SUCCESS once the delay has passed. */
    PENDING_THEN_SUCCESS,
    /** Processes successfully, then answers 500: the classic ambiguous result. */
    SERVER_ERROR_AFTER_SUCCESS,
    /** 400 without processing: the request itself was rejected. */
    REJECT
}
