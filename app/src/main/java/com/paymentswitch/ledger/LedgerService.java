package com.paymentswitch.ledger;

import static com.paymentswitch.ledger.AccountType.Direction.CREDIT;
import static com.paymentswitch.ledger.AccountType.Direction.DEBIT;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.AccountType.Direction;
import com.paymentswitch.ledger.LedgerRepository.HoldRow;
import com.paymentswitch.ledger.LedgerRepository.PostedEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the ledger module. Every operation is a balanced, immutable journal and is
 * idempotent: repeating it (same reference or transaction id) books nothing new.
 *
 * <p>Methods join the caller's transaction (so a hold commits together with the transaction
 * record and the idempotency key) and require READ COMMITTED isolation: after waiting for the
 * account lock, the balance query must see what the previous lock holder committed.
 *
 * <p>Account flows (D = debit, C = credit):
 * <pre>
 * topUp    D CASH             C MERCHANT_BALANCE
 * hold     D MERCHANT_BALANCE C MERCHANT_HOLD
 * release  D MERCHANT_HOLD    C MERCHANT_BALANCE
 * capture  D MERCHANT_HOLD    C SUPPLIER_PAYABLE (cost) + C REVENUE (margin)
 * </pre>
 */
@Service
public class LedgerService {

    private final LedgerRepository repository;

    LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    /** Credits external funds to a merchant. {@code reference} (e.g. a bank transfer id) makes it idempotent. */
    @Transactional
    public UUID topUp(UUID merchantId, Money amount, String reference) {
        amount.requirePositive();
        String journalReference = "topup:" + merchantId + ":" + reference;
        Optional<UUID> existing = repository.findJournalByReference(journalReference);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID cash = repository.ensureAccount(AccountType.CASH, LedgerRepository.SYSTEM_OWNER, amount.currency());
        UUID balance = merchantAccount(AccountType.MERCHANT_BALANCE, merchantId, amount.currency());
        return post("TOP_UP", journalReference, null, null, "Top-up " + reference,
                List.of(new Line(cash, DEBIT, amount.amount()), new Line(balance, CREDIT, amount.amount())))
                .orElseGet(() -> repository.findJournalByReference(journalReference).orElseThrow());
    }

    /**
     * Reserves funds for a transaction.
     *
     * @throws InsufficientBalanceException if available funds are lower than {@code amount}; nothing is written
     */
    @Transactional
    public void hold(UUID merchantId, UUID transactionId, Money amount) {
        amount.requirePositive();
        UUID balance = merchantAccount(AccountType.MERCHANT_BALANCE, merchantId, amount.currency());
        UUID held = merchantAccount(AccountType.MERCHANT_HOLD, merchantId, amount.currency());

        repository.lockAccount(balance);
        Optional<HoldRow> existing = repository.findHold(transactionId, false);
        if (existing.isPresent()) {
            requireSameHold(existing.get(), merchantId, amount);
            return;
        }
        if (repository.balanceOf(balance, CREDIT) < amount.amount()) {
            throw new InsufficientBalanceException();
        }
        if (!repository.insertHold(transactionId, merchantId, amount.amount(), amount.currency())) {
            // Same transaction id used under another merchant or currency (a different lock).
            requireSameHold(repository.findHold(transactionId, false).orElseThrow(), merchantId, amount);
            return;
        }
        post("HOLD", "hold:" + transactionId, transactionId, null, null,
                List.of(new Line(balance, DEBIT, amount.amount()), new Line(held, CREDIT, amount.amount())));
    }

    /** Returns held funds to the merchant. Releasing an already released hold is a no-op. */
    @Transactional
    public void release(UUID transactionId) {
        HoldRow hold = activeHold(transactionId, "RELEASED");
        if (hold == null) {
            return;
        }
        UUID held = merchantAccount(AccountType.MERCHANT_HOLD, hold.merchantId(), hold.currency());
        UUID balance = merchantAccount(AccountType.MERCHANT_BALANCE, hold.merchantId(), hold.currency());
        post("RELEASE", "release:" + transactionId, transactionId, null, null,
                List.of(new Line(held, DEBIT, hold.amount()), new Line(balance, CREDIT, hold.amount())));
        repository.closeHold(transactionId, "RELEASED");
    }

    /**
     * Turns a hold into a final debit: the supplier is owed {@code supplierCost} and the rest of the
     * held amount is revenue. Capturing an already captured hold is a no-op.
     */
    @Transactional
    public void capture(UUID transactionId, long supplierId, Money supplierCost) {
        HoldRow hold = activeHold(transactionId, "CAPTURED");
        if (hold == null) {
            return;
        }
        supplierCost.requireSameCurrency(Money.of(hold.amount(), hold.currency()));
        if (supplierCost.amount() > hold.amount()) {
            throw new IllegalArgumentException("Supplier cost exceeds the held amount");
        }
        long margin = hold.amount() - supplierCost.amount();

        List<Line> lines = new ArrayList<>();
        lines.add(new Line(merchantAccount(AccountType.MERCHANT_HOLD, hold.merchantId(), hold.currency()),
                DEBIT, hold.amount()));
        if (supplierCost.amount() > 0) {
            lines.add(new Line(repository.ensureAccount(AccountType.SUPPLIER_PAYABLE, Long.toString(supplierId),
                    hold.currency()), CREDIT, supplierCost.amount()));
        }
        if (margin > 0) {
            lines.add(new Line(repository.ensureAccount(AccountType.REVENUE, LedgerRepository.SYSTEM_OWNER,
                    hold.currency()), CREDIT, margin));
        }
        post("CAPTURE", "capture:" + transactionId, transactionId, null, null, lines);
        repository.closeHold(transactionId, "CAPTURED");
    }

    /**
     * Corrects a top-up by booking its mirror image. Journals are never edited or deleted.
     *
     * @throws InsufficientBalanceException if the merchant already spent the funds
     */
    @Transactional
    public UUID reverseTopUp(UUID topUpJournalId, String reason) {
        String kind = repository.findJournalKind(topUpJournalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOURNAL_NOT_FOUND", "Journal not found"));
        if (!kind.equals("TOP_UP")) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REVERSIBLE", "Only top-ups can be reversed");
        }
        String reference = "reversal:" + topUpJournalId;
        Optional<UUID> existing = repository.findJournalByReference(reference);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<PostedEntry> original = repository.entriesOf(topUpJournalId);
        PostedEntry merchantCredit = original.stream()
                .filter(entry -> entry.accountType() == AccountType.MERCHANT_BALANCE)
                .findFirst()
                .orElseThrow();
        repository.lockAccount(merchantCredit.accountId());
        existing = repository.findJournalByReference(reference); // a concurrent reversal may have won
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.balanceOf(merchantCredit.accountId(), CREDIT) < merchantCredit.amount()) {
            throw new InsufficientBalanceException();
        }
        List<Line> mirrored = original.stream()
                .map(entry -> new Line(entry.accountId(), opposite(entry.direction()), entry.amount()))
                .toList();
        return post("REVERSAL", reference, null, topUpJournalId, reason, mirrored)
                .orElseGet(() -> repository.findJournalByReference(reference).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Balance getBalance(UUID merchantId, String currency) {
        return new Balance(
                Money.of(balanceOf(AccountType.MERCHANT_BALANCE, merchantId, currency), currency),
                Money.of(balanceOf(AccountType.MERCHANT_HOLD, merchantId, currency), currency));
    }

    private long balanceOf(AccountType type, UUID merchantId, String currency) {
        return repository.findAccount(type, merchantId.toString(), currency)
                .map(account -> repository.balanceOf(account, type.normalSide))
                .orElse(0L);
    }

    /**
     * Locks the hold row and returns it if still active; returns null if it is already in
     * {@code targetStatus} (idempotent repeat).
     */
    private HoldRow activeHold(UUID transactionId, String targetStatus) {
        HoldRow hold = repository.findHold(transactionId, true)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "No hold for transaction"));
        if (hold.status().equals(targetStatus)) {
            return null;
        }
        if (!hold.status().equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_ALREADY_CLOSED", "Hold is already " + hold.status());
        }
        return hold;
    }

    private static void requireSameHold(HoldRow existing, UUID merchantId, Money amount) {
        if (!existing.merchantId().equals(merchantId) || existing.amount() != amount.amount()
                || !existing.currency().equals(amount.currency())) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_MISMATCH",
                    "A different hold already exists for this transaction");
        }
    }

    private UUID merchantAccount(AccountType type, UUID merchantId, String currency) {
        return repository.ensureAccount(type, merchantId.toString(), currency);
    }

    /** Validates and books a journal. Returns empty if the reference was already booked. */
    private Optional<UUID> post(String kind, String reference, UUID transactionId, UUID reverses,
            String description, List<Line> lines) {
        long debits = lines.stream().filter(l -> l.direction == DEBIT).mapToLong(Line::amount).sum();
        long credits = lines.stream().filter(l -> l.direction == CREDIT).mapToLong(Line::amount).sum();
        if (lines.size() < 2 || debits != credits || lines.stream().anyMatch(l -> l.amount <= 0)) {
            throw new IllegalStateException("Refusing to post an invalid journal: " + reference);
        }
        Optional<UUID> journalId = repository.insertJournal(kind, reference, transactionId, reverses, description);
        journalId.ifPresent(id -> lines.forEach(l -> repository.insertEntry(id, l.accountId, l.direction, l.amount)));
        return journalId;
    }

    private static Direction opposite(Direction direction) {
        return direction == DEBIT ? CREDIT : DEBIT;
    }

    private record Line(UUID accountId, Direction direction, long amount) {
    }
}
