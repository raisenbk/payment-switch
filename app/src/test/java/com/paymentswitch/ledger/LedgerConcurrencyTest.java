package com.paymentswitch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestMerchants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerConcurrencyTest extends AbstractIntegrationTest {

    private static final String IDR = "IDR";
    private static final int THREADS = 32;

    @Autowired
    LedgerService ledger;

    @Autowired
    TestMerchants merchants;

    @Autowired
    LedgerAssertions invariants;

    @Test
    void thousandParallelHoldsNeverOverdrawTheBalance() throws Exception {
        UUID merchant = merchants.create().id();
        ledger.topUp(merchant, idr(600_000), "seed");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Throwable> unexpected = runInParallel(1000, i -> {
            try {
                ledger.hold(merchant, UUID.randomUUID(), idr(1_000));
                succeeded.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(succeeded).hasValue(600);
        assertThat(rejected).hasValue(400);
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(new Balance(idr(0), idr(600_000)));
        invariants.assertGlobalInvariants();
    }

    @Test
    void parallelHoldThenReleaseOrCaptureKeepsBooksExact() throws Exception {
        UUID merchant = merchants.create().id();
        ledger.topUp(merchant, idr(1_000_000), "seed");

        List<Throwable> unexpected = runInParallel(500, i -> {
            UUID transactionId = UUID.randomUUID();
            ledger.hold(merchant, transactionId, idr(2_000));
            if (i % 2 == 0) {
                ledger.capture(transactionId, 1, idr(1_500));
            } else {
                ledger.release(transactionId);
            }
        });

        assertThat(unexpected).isEmpty();
        // 250 captures of 2,000 leave the merchant; releases return everything else.
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(new Balance(idr(500_000), idr(0)));
        invariants.assertGlobalInvariants();
    }

    @Test
    void racingReleaseAndCaptureOfOneHoldHaveExactlyOneWinner() throws Exception {
        UUID merchant = merchants.create().id();
        ledger.topUp(merchant, idr(100_000), "seed");

        for (int round = 0; round < 20; round++) {
            UUID transactionId = UUID.randomUUID();
            ledger.hold(merchant, transactionId, idr(1_000));
            AtomicInteger closedByOther = new AtomicInteger();

            List<Throwable> unexpected = runInParallel(2, i -> {
                try {
                    if (i == 0) {
                        ledger.release(transactionId);
                    } else {
                        ledger.capture(transactionId, 1, idr(900));
                    }
                } catch (ApiException e) {
                    if (!"HOLD_ALREADY_CLOSED".equals(e.code())) {
                        throw e;
                    }
                    closedByOther.incrementAndGet();
                }
            });

            assertThat(unexpected).isEmpty();
            assertThat(closedByOther).hasValue(1);
        }
        assertThat(ledger.getBalance(merchant, IDR).held()).isEqualTo(idr(0));
        invariants.assertGlobalInvariants();
    }

    private static Money idr(long amount) {
        return Money.of(amount, IDR);
    }

    /** Runs {@code tasks} copies of the action, released at the same instant; returns unexpected errors. */
    private static List<Throwable> runInParallel(int tasks, IndexedAction action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, THREADS))) {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    try {
                        action.run(index);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        return List.copyOf(errors);
    }

    @FunctionalInterface
    private interface IndexedAction {
        void run(int index);
    }
}
