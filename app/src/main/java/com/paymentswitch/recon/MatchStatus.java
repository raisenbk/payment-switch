package com.paymentswitch.recon;

/** Outcome of reconciling one settlement line, or one of our transactions missing from the file. */
public enum MatchStatus {
    /** Supplier billed it and we booked it as a success with them, for the same amount. */
    MATCHED,
    /** Supplier billed it, but we have no successful transaction with this supplier for it. */
    MISSING_INTERNAL,
    /** We booked a success with this supplier on that day, but it is not in their file. */
    MISSING_SUPPLIER,
    /** Both sides have it, but the billed amount differs from the cost we booked. */
    AMOUNT_DIFF,
    /** The same reference appears more than once in the file (billed twice). */
    DUPLICATE
}
