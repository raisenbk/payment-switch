package com.paymentswitch.transaction;

import static com.paymentswitch.transaction.TransactionStatus.FAILED;
import static com.paymentswitch.transaction.TransactionStatus.MANUAL_REVIEW;
import static com.paymentswitch.transaction.TransactionStatus.PENDING;
import static com.paymentswitch.transaction.TransactionStatus.PROCESSING;
import static com.paymentswitch.transaction.TransactionStatus.SUCCESS;
import static com.paymentswitch.transaction.TransactionStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionStatusTest {

    /** The complete transition table; any pair not listed here must be illegal. */
    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(PROCESSING),
            PROCESSING, EnumSet.of(SUCCESS, FAILED, UNKNOWN),
            UNKNOWN, EnumSet.of(SUCCESS, FAILED, MANUAL_REVIEW),
            MANUAL_REVIEW, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.noneOf(TransactionStatus.class),
            FAILED, EnumSet.noneOf(TransactionStatus.class));

    @Test
    void everyPairMatchesTheTransitionTable() {
        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void timeoutPathCanNeverJumpStraightToFailedFromPending() {
        assertThat(PENDING.canTransitionTo(FAILED)).isFalse();
        assertThat(PENDING.canTransitionTo(UNKNOWN)).isFalse();
    }

    @Test
    void finalStatesAreTerminal() {
        assertThat(SUCCESS.isFinal()).isTrue();
        assertThat(FAILED.isFinal()).isTrue();
        assertThat(UNKNOWN.isFinal()).isFalse();
        assertThat(MANUAL_REVIEW.isFinal()).isFalse();
    }
}
