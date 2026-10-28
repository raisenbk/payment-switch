package com.paymentswitch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestMerchants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Property-style test: a seeded random sequence of ledger operations is applied both to the real
 * ledger and to a trivial in-memory model. Afterwards every merchant balance must match the model
 * and the database-wide invariants (debits = credits, no negative merchant account) must hold.
 */
class LedgerInvariantTest extends AbstractIntegrationTest {

    private static final int OPERATIONS = 300;
    private static final List<String> CURRENCIES = List.of("IDR", "USD");

    @Autowired
    LedgerService ledger;

    @Autowired
    TestMerchants merchants;

    @Autowired
    LedgerAssertions invariants;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 42, 2026})
    void randomOperationSequencesKeepTheBooksConsistent(long seed) {
        Random random = new Random(seed);
        List<Model> models = List.of(new Model(merchants.create().id()), new Model(merchants.create().id()),
                new Model(merchants.create().id()));

        for (int i = 0; i < OPERATIONS; i++) {
            Model model = models.get(random.nextInt(models.size()));
            String currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            switch (random.nextInt(7)) {
                case 0 -> topUp(model, currency, random);
                case 1, 2 -> hold(model, currency, random);
                case 3 -> release(model, random);
                case 4 -> capture(model, random);
                case 5 -> reverseTopUp(model, random);
                default -> replayLastHold(model);
            }
        }

        for (Model model : models) {
            for (String currency : CURRENCIES) {
                assertThat(ledger.getBalance(model.merchantId, currency))
                        .as("merchant %s %s (seed %d)", model.merchantId, currency, seed)
                        .isEqualTo(new Balance(Money.of(model.available(currency), currency),
                                Money.of(model.held(currency), currency)));
            }
        }
        invariants.assertGlobalInvariants();
    }

    private void topUp(Model model, String currency, Random random) {
        long amount = 1 + random.nextInt(100_000);
        UUID journal = ledger.topUp(model.merchantId, Money.of(amount, currency), "ref-" + UUID.randomUUID());
        model.topUps.add(new TopUp(journal, currency, amount));
        model.adjustAvailable(currency, amount);
    }

    private void hold(Model model, String currency, Random random) {
        long amount = 1 + random.nextInt(60_000);
        UUID transactionId = UUID.randomUUID();
        if (model.available(currency) >= amount) {
            ledger.hold(model.merchantId, transactionId, Money.of(amount, currency));
            model.activeHolds.add(new Hold(transactionId, currency, amount));
            model.adjustAvailable(currency, -amount);
        } else {
            assertThatThrownBy(() -> ledger.hold(model.merchantId, transactionId, Money.of(amount, currency)))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    private void release(Model model, Random random) {
        if (model.activeHolds.isEmpty()) {
            return;
        }
        Hold hold = model.activeHolds.remove(random.nextInt(model.activeHolds.size()));
        ledger.release(hold.transactionId);
        model.adjustAvailable(hold.currency, hold.amount);
    }

    private void capture(Model model, Random random) {
        if (model.activeHolds.isEmpty()) {
            return;
        }
        Hold hold = model.activeHolds.remove(random.nextInt(model.activeHolds.size()));
        long cost = random.nextLong(hold.amount + 1);
        ledger.capture(hold.transactionId, 1 + random.nextInt(3), Money.of(cost, hold.currency));
        // A second capture must be a no-op.
        ledger.capture(hold.transactionId, 1, Money.of(cost, hold.currency));
    }

    private void reverseTopUp(Model model, Random random) {
        if (model.topUps.isEmpty()) {
            return;
        }
        TopUp topUp = model.topUps.get(random.nextInt(model.topUps.size()));
        if (topUp.reversed) {
            ledger.reverseTopUp(topUp.journalId, "repeat");
        } else if (model.available(topUp.currency) >= topUp.amount) {
            ledger.reverseTopUp(topUp.journalId, "correction");
            topUp.reversed = true;
            model.adjustAvailable(topUp.currency, -topUp.amount);
        } else {
            assertThatThrownBy(() -> ledger.reverseTopUp(topUp.journalId, "correction"))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    private void replayLastHold(Model model) {
        if (model.activeHolds.isEmpty()) {
            return;
        }
        Hold last = model.activeHolds.getLast();
        ledger.hold(model.merchantId, last.transactionId, Money.of(last.amount, last.currency));
        assertThatThrownBy(() -> ledger.hold(model.merchantId, last.transactionId,
                Money.of(last.amount + 1, last.currency)))
                .isInstanceOf(ApiException.class);
    }

    private static final class Model {
        final UUID merchantId;
        final Map<String, Long> available = new HashMap<>();
        final List<Hold> activeHolds = new ArrayList<>();
        final List<TopUp> topUps = new ArrayList<>();

        Model(UUID merchantId) {
            this.merchantId = merchantId;
        }

        long available(String currency) {
            return available.getOrDefault(currency, 0L);
        }

        long held(String currency) {
            return activeHolds.stream().filter(h -> h.currency.equals(currency)).mapToLong(h -> h.amount).sum();
        }

        void adjustAvailable(String currency, long delta) {
            available.merge(currency, delta, Long::sum);
        }
    }

    private record Hold(UUID transactionId, String currency, long amount) {
    }

    private static final class TopUp {
        final UUID journalId;
        final String currency;
        final long amount;
        boolean reversed;

        TopUp(UUID journalId, String currency, long amount) {
            this.journalId = journalId;
            this.currency = currency;
            this.amount = amount;
        }
    }
}
