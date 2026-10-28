package com.paymentswitch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestMerchants;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String IDR = "IDR";
    private static final long SUPPLIER_ID = 7;

    @Autowired
    LedgerService ledger;

    @Autowired
    TestMerchants merchants;

    @Autowired
    LedgerAssertions invariants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    UUID merchant;

    @BeforeEach
    void createMerchant() {
        merchant = merchants.create().id();
    }

    @AfterEach
    void invariantsStillHold() {
        invariants.assertGlobalInvariants();
    }

    @Test
    void newMerchantHasZeroBalance() {
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(0, 0));
    }

    @Test
    void topUpIncreasesAvailableBalance() {
        ledger.topUp(merchant, idr(50_000), "bank-ref-1");

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(50_000, 0));
    }

    @Test
    void topUpIsIdempotentPerReference() {
        UUID first = ledger.topUp(merchant, idr(50_000), "bank-ref-1");
        UUID again = ledger.topUp(merchant, idr(50_000), "bank-ref-1");

        assertThat(again).isEqualTo(first);
        assertThat(ledger.getBalance(merchant, IDR).available()).isEqualTo(idr(50_000));
    }

    @Test
    void holdMovesFundsFromAvailableToHeld() {
        ledger.topUp(merchant, idr(50_000), "ref");

        ledger.hold(merchant, UUID.randomUUID(), idr(20_000));

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(30_000, 20_000));
    }

    @Test
    void holdOfEntireBalanceIsAllowed() {
        ledger.topUp(merchant, idr(20_000), "ref");

        ledger.hold(merchant, UUID.randomUUID(), idr(20_000));

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(0, 20_000));
    }

    @Test
    void insufficientBalanceIsRejectedWithoutPartialWrites() {
        ledger.topUp(merchant, idr(10_000), "ref");
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.hold(merchant, transactionId, idr(10_001)))
                .isInstanceOf(InsufficientBalanceException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INSUFFICIENT_BALANCE");

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(10_000, 0));
        assertThat(count("SELECT count(*) FROM ledger_holds WHERE transaction_id = :id", transactionId)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_journals WHERE transaction_id = :id", transactionId)).isZero();
    }

    @Test
    void holdIsIdempotentPerTransaction() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();

        ledger.hold(merchant, transactionId, idr(20_000));
        ledger.hold(merchant, transactionId, idr(20_000));

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(30_000, 20_000));
    }

    @Test
    void repeatedHoldWithDifferentAmountIsAConflict() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();
        ledger.hold(merchant, transactionId, idr(20_000));

        assertThatThrownBy(() -> ledger.hold(merchant, transactionId, idr(25_000)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("HOLD_MISMATCH");
    }

    @Test
    void releaseReturnsHeldFunds() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();
        ledger.hold(merchant, transactionId, idr(20_000));

        ledger.release(transactionId);
        ledger.release(transactionId);

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(50_000, 0));
    }

    @Test
    void captureConsumesHoldAndBooksSupplierPayableAndRevenue() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();
        ledger.hold(merchant, transactionId, idr(20_000));

        ledger.capture(transactionId, SUPPLIER_ID, idr(19_000));
        ledger.capture(transactionId, SUPPLIER_ID, idr(19_000));

        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(30_000, 0));
        assertThat(entriesFor(transactionId, "CAPTURE")).containsExactlyInAnyOrder(
                "MERCHANT_HOLD DEBIT 20000",
                "SUPPLIER_PAYABLE CREDIT 19000",
                "REVENUE CREDIT 1000");
    }

    @Test
    void captureWithoutMarginBooksNoRevenueEntry() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();
        ledger.hold(merchant, transactionId, idr(20_000));

        ledger.capture(transactionId, SUPPLIER_ID, idr(20_000));

        assertThat(entriesFor(transactionId, "CAPTURE"))
                .containsExactlyInAnyOrder("MERCHANT_HOLD DEBIT 20000", "SUPPLIER_PAYABLE CREDIT 20000");
    }

    @Test
    void supplierCostAboveHeldAmountIsRejected() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID transactionId = UUID.randomUUID();
        ledger.hold(merchant, transactionId, idr(20_000));

        assertThatThrownBy(() -> ledger.capture(transactionId, SUPPLIER_ID, idr(20_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(30_000, 20_000));
    }

    @Test
    void capturedHoldCannotBeReleasedAndViceVersa() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID captured = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        ledger.hold(merchant, captured, idr(10_000));
        ledger.hold(merchant, released, idr(10_000));
        ledger.capture(captured, SUPPLIER_ID, idr(9_000));
        ledger.release(released);

        assertThatThrownBy(() -> ledger.release(captured))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("HOLD_ALREADY_CLOSED");
        assertThatThrownBy(() -> ledger.capture(released, SUPPLIER_ID, idr(9_000)))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("HOLD_ALREADY_CLOSED");
    }

    @Test
    void unknownHoldIsReported() {
        assertThatThrownBy(() -> ledger.release(UUID.randomUUID()))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("HOLD_NOT_FOUND");
    }

    @Test
    void holdInAnotherCurrencyDoesNotSeeIdrFunds() {
        ledger.topUp(merchant, idr(50_000), "ref");

        assertThatThrownBy(() -> ledger.hold(merchant, UUID.randomUUID(), Money.of(1, "USD")))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void topUpCanBeCorrectedWithAReversingJournal() {
        UUID topUp = ledger.topUp(merchant, idr(50_000), "wrong-amount");

        UUID reversal = ledger.reverseTopUp(topUp, "operator typo");
        UUID again = ledger.reverseTopUp(topUp, "operator typo");

        assertThat(again).isEqualTo(reversal);
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(0, 0));
        assertThat(count("SELECT count(*) FROM ledger_journals WHERE id = :id", topUp)).isOne();
    }

    @Test
    void reversalThatWouldMakeBalanceNegativeIsRejected() {
        UUID topUp = ledger.topUp(merchant, idr(50_000), "ref");
        ledger.hold(merchant, UUID.randomUUID(), idr(40_000));

        assertThatThrownBy(() -> ledger.reverseTopUp(topUp, "too late"))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(ledger.getBalance(merchant, IDR)).isEqualTo(balance(10_000, 40_000));
    }

    @Test
    void databaseRejectsUpdatesAndDeletesOfPostedEntries() {
        ledger.topUp(merchant, idr(50_000), "ref");

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = amount + 1").update())
                .rootCause().hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_journals").update())
                .rootCause().hasMessageContaining("immutable");
    }

    @Test
    void databaseRejectsUnbalancedJournalsAtCommit() {
        ledger.topUp(merchant, idr(50_000), "ref");
        UUID account = jdbc.sql("""
                        SELECT id FROM ledger_accounts
                         WHERE owner_id = :m AND type = 'MERCHANT_BALANCE'""")
                .param("m", merchant.toString())
                .query(UUID.class)
                .single();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID journal = jdbc.sql("""
                            INSERT INTO ledger_journals (kind, reference, description)
                            VALUES ('TOP_UP', :ref, 'forged') RETURNING id""")
                    .param("ref", "forged:" + UUID.randomUUID())
                    .query(UUID.class)
                    .single();
            jdbc.sql("""
                            INSERT INTO ledger_entries (journal_id, account_id, direction, amount)
                            VALUES (:j, :a, 'CREDIT', 1000000)""")
                    .param("j", journal)
                    .param("a", account)
                    .update();
        })).rootCause().hasMessageContaining("unbalanced");

        assertThat(ledger.getBalance(merchant, IDR).available()).isEqualTo(idr(50_000));
    }

    private static Money idr(long amount) {
        return Money.of(amount, IDR);
    }

    private static Balance balance(long available, long held) {
        return new Balance(idr(available), idr(held));
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private java.util.List<String> entriesFor(UUID transactionId, String kind) {
        return jdbc.sql("""
                        SELECT a.type || ' ' || e.direction || ' ' || e.amount
                          FROM ledger_entries e
                          JOIN ledger_journals j ON j.id = e.journal_id
                          JOIN ledger_accounts a ON a.id = e.account_id
                         WHERE j.transaction_id = :tx AND j.kind = :kind""")
                .param("tx", transactionId)
                .param("kind", kind)
                .query(String.class)
                .list();
    }
}
