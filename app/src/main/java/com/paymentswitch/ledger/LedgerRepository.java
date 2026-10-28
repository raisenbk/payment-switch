package com.paymentswitch.ledger;

import com.paymentswitch.ledger.AccountType.Direction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the ledger tables. Callers own the transaction. */
@Repository
class LedgerRepository {

    static final String SYSTEM_OWNER = "switch";

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the account id, creating the account on first use. Never locks existing rows. */
    UUID ensureAccount(AccountType type, String ownerId, String currency) {
        // Two statements on purpose: under READ COMMITTED the SELECT gets a fresh snapshot and sees
        // a row that a concurrent transaction committed while our INSERT waited on it.
        jdbc.sql("""
                        INSERT INTO ledger_accounts (owner_type, owner_id, type, currency)
                        VALUES (:ownerType, :ownerId, :type, :currency)
                        ON CONFLICT (owner_type, owner_id, type, currency) DO NOTHING""")
                .param("ownerType", type.ownerType.name())
                .param("ownerId", ownerId)
                .param("type", type.name())
                .param("currency", currency)
                .update();
        return findAccount(type, ownerId, currency).orElseThrow();
    }

    Optional<UUID> findAccount(AccountType type, String ownerId, String currency) {
        return jdbc.sql("""
                        SELECT id FROM ledger_accounts
                         WHERE owner_type = :ownerType AND owner_id = :ownerId
                           AND type = :type AND currency = :currency""")
                .param("ownerType", type.ownerType.name())
                .param("ownerId", ownerId)
                .param("type", type.name())
                .param("currency", currency)
                .query(UUID.class)
                .optional();
    }

    /**
     * Serializes balance-reducing operations on one account. FOR NO KEY UPDATE (not FOR UPDATE) so
     * that inserting entries, which takes a FOR KEY SHARE lock through the foreign key, is not blocked.
     */
    void lockAccount(UUID accountId) {
        jdbc.sql("SELECT id FROM ledger_accounts WHERE id = :id FOR NO KEY UPDATE")
                .param("id", accountId)
                .query(UUID.class)
                .single();
    }

    /** Balance on the account's normal side: credits minus debits for credit-normal accounts. */
    long balanceOf(UUID accountId, Direction normalSide) {
        long creditMinusDebit = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                          FROM ledger_entries WHERE account_id = :id""")
                .param("id", accountId)
                .query(Long.class)
                .single();
        return normalSide == Direction.CREDIT ? creditMinusDebit : -creditMinusDebit;
    }

    /** @return the new journal id, or empty if a journal with this reference already exists */
    Optional<UUID> insertJournal(String kind, String reference, UUID transactionId, UUID reversesJournalId,
            String description) {
        return jdbc.sql("""
                        INSERT INTO ledger_journals (kind, reference, transaction_id, reverses_journal_id, description)
                        VALUES (:kind, :reference, :transactionId, :reverses, :description)
                        ON CONFLICT (reference) DO NOTHING
                        RETURNING id""")
                .param("kind", kind)
                .param("reference", reference)
                .param("transactionId", transactionId)
                .param("reverses", reversesJournalId)
                .param("description", description)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> findJournalByReference(String reference) {
        return jdbc.sql("SELECT id FROM ledger_journals WHERE reference = :reference")
                .param("reference", reference)
                .query(UUID.class)
                .optional();
    }

    Optional<String> findJournalKind(UUID journalId) {
        return jdbc.sql("SELECT kind FROM ledger_journals WHERE id = :id")
                .param("id", journalId)
                .query(String.class)
                .optional();
    }

    void insertEntry(UUID journalId, UUID accountId, Direction direction, long amount) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (journal_id, account_id, direction, amount)
                        VALUES (:journalId, :accountId, :direction, :amount)""")
                .param("journalId", journalId)
                .param("accountId", accountId)
                .param("direction", direction.name())
                .param("amount", amount)
                .update();
    }

    List<PostedEntry> entriesOf(UUID journalId) {
        return jdbc.sql("""
                        SELECT e.account_id, a.type, e.direction, e.amount, a.currency
                          FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                         WHERE e.journal_id = :id""")
                .param("id", journalId)
                .query((rs, row) -> new PostedEntry(
                        rs.getObject("account_id", UUID.class),
                        AccountType.valueOf(rs.getString("type")),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount"),
                        rs.getString("currency")))
                .list();
    }

    /** @return false if a hold for this transaction already exists */
    boolean insertHold(UUID transactionId, UUID merchantId, long amount, String currency) {
        return jdbc.sql("""
                        INSERT INTO ledger_holds (transaction_id, merchant_id, amount, currency)
                        VALUES (:transactionId, :merchantId, :amount, :currency)
                        ON CONFLICT (transaction_id) DO NOTHING""")
                .param("transactionId", transactionId)
                .param("merchantId", merchantId)
                .param("amount", amount)
                .param("currency", currency)
                .update() == 1;
    }

    Optional<HoldRow> findHold(UUID transactionId, boolean forUpdate) {
        String sql = "SELECT transaction_id, merchant_id, amount, currency, status FROM ledger_holds"
                + " WHERE transaction_id = :id" + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.sql(sql)
                .param("id", transactionId)
                .query(HoldRow.class)
                .optional();
    }

    void closeHold(UUID transactionId, String status) {
        jdbc.sql("""
                        UPDATE ledger_holds SET status = :status, closed_at = now()
                         WHERE transaction_id = :id AND status = 'ACTIVE'""")
                .param("status", status)
                .param("id", transactionId)
                .update();
    }

    record PostedEntry(UUID accountId, AccountType accountType, Direction direction, long amount, String currency) {
    }

    record HoldRow(UUID transactionId, UUID merchantId, long amount, String currency, String status) {
    }
}
