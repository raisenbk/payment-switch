package com.paymentswitch.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database-wide ledger invariants. They must hold after any sequence of ledger operations. */
@TestComponent
public class LedgerAssertions {

    private final JdbcClient jdbc;

    public LedgerAssertions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void assertGlobalInvariants() {
        long debits = sum("DEBIT");
        long credits = sum("CREDIT");
        assertThat(debits).as("total debits = total credits").isEqualTo(credits);

        List<String> unbalanced = jdbc.sql("""
                        SELECT journal_id::text
                          FROM ledger_entries
                         GROUP BY journal_id
                        HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0""")
                .query(String.class)
                .list();
        assertThat(unbalanced).as("unbalanced journals").isEmpty();

        List<String> negativeMerchantAccounts = jdbc.sql("""
                        SELECT a.id::text
                          FROM ledger_accounts a
                          JOIN ledger_entries e ON e.account_id = a.id
                         WHERE a.type IN ('MERCHANT_BALANCE', 'MERCHANT_HOLD')
                         GROUP BY a.id
                        HAVING SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END) < 0""")
                .query(String.class)
                .list();
        assertThat(negativeMerchantAccounts).as("merchant accounts below zero").isEmpty();

        List<String> heldMismatch = jdbc.sql("""
                        SELECT a.owner_id
                          FROM ledger_accounts a
                          CROSS JOIN LATERAL (
                                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) AS bal
                                  FROM ledger_entries WHERE account_id = a.id) b
                          CROSS JOIN LATERAL (
                                SELECT COALESCE(SUM(amount), 0) AS active
                                  FROM ledger_holds
                                 WHERE merchant_id::text = a.owner_id AND currency = a.currency
                                   AND status = 'ACTIVE') h
                         WHERE a.type = 'MERCHANT_HOLD' AND b.bal <> h.active""")
                .query(String.class)
                .list();
        assertThat(heldMismatch).as("hold account balance = sum of active holds").isEmpty();
    }

    private long sum(String direction) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = :d")
                .param("d", direction)
                .query(Long.class)
                .single();
    }
}
