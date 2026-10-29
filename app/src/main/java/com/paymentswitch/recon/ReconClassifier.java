package com.paymentswitch.recon;

import java.util.UUID;

/**
 * Classifies one settlement line against what we booked. A pure function, tested as a decision
 * table. Rules, first match wins:
 * <ol>
 *   <li>reference seen earlier in the same file → {@link MatchStatus#DUPLICATE};</li>
 *   <li>no transaction with that id → {@link MatchStatus#MISSING_INTERNAL};</li>
 *   <li>our transaction is not SUCCESS, or succeeded at another supplier → {@link MatchStatus#MISSING_INTERNAL};</li>
 *   <li>currency or amount differs from the supplier cost we booked → {@link MatchStatus#AMOUNT_DIFF};</li>
 *   <li>otherwise → {@link MatchStatus#MATCHED}.</li>
 * </ol>
 * Our successes that are not in the file are found separately (MISSING_SUPPLIER).
 */
final class ReconClassifier {

    private ReconClassifier() {
    }

    /**
     * A settlement line joined with our transaction (internal fields are null when none exists).
     *
     * @param occurrence 1 for the first line with this reference in the file, 2 for the next, …
     */
    record LineToMatch(int lineNo, String reference, String supplierRef, long amount, String currency, int occurrence,
            UUID transactionId, String internalStatus, Long internalSupplierId, Long internalAmount,
            String internalCurrency) {
    }

    record Result(MatchStatus status, UUID transactionId, Integer lineNo, String reference, String supplierRef,
            String internalStatus, Long internalAmount, Long supplierAmount, Long diffAmount, String currency) {
    }

    static Result classify(LineToMatch line, long fileSupplierId) {
        MatchStatus status;
        if (line.occurrence() > 1) {
            status = MatchStatus.DUPLICATE;
        } else if (line.transactionId() == null) {
            status = MatchStatus.MISSING_INTERNAL;
        } else if (!"SUCCESS".equals(line.internalStatus()) || line.internalSupplierId() == null
                || line.internalSupplierId() != fileSupplierId) {
            status = MatchStatus.MISSING_INTERNAL;
        } else if (!line.currency().equals(line.internalCurrency()) || line.amount() != line.internalAmount()) {
            status = MatchStatus.AMOUNT_DIFF;
        } else {
            status = MatchStatus.MATCHED;
        }
        Long diff = line.internalAmount() != null && line.currency().equals(line.internalCurrency())
                ? line.amount() - line.internalAmount() : null;
        return new Result(status, line.transactionId(), line.lineNo(), line.reference(), line.supplierRef(),
                line.internalStatus(), line.internalAmount(), line.amount(), diff, line.currency());
    }
}
