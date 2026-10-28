package com.paymentswitch.transaction;

/**
 * Transaction lifecycle.
 *
 * <pre>
 * PENDING ─▶ PROCESSING ─▶ SUCCESS
 *                 ├──────▶ FAILED
 *                 └──────▶ UNKNOWN ─(inquiry)─▶ SUCCESS | FAILED
 *                              └─(max window)─▶ MANUAL_REVIEW ─(operator)─▶ SUCCESS | FAILED
 * </pre>
 */
public enum TransactionStatus {
    PENDING, PROCESSING, SUCCESS, FAILED, UNKNOWN, MANUAL_REVIEW;

    public boolean canTransitionTo(TransactionStatus next) {
        return switch (this) {
            case PENDING -> next == PROCESSING;
            case PROCESSING -> next == SUCCESS || next == FAILED || next == UNKNOWN;
            case UNKNOWN -> next == SUCCESS || next == FAILED || next == MANUAL_REVIEW;
            case MANUAL_REVIEW -> next == SUCCESS || next == FAILED;
            case SUCCESS, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }
}
