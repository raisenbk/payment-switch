# ADR-003: Double-entry ledger instead of a mutable balance column

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The simplest way to track merchant money is a `balance` column that is incremented and
decremented. It answers "how much now?" but not "why?". A lost update, a bug or a manual fix
silently changes money with no trace, and nothing can prove the number is right.

## Decision

Money moves only through an append-only, double-entry ledger:

- **Accounts** (`ledger_accounts`), one per owner, type and currency: `MERCHANT_BALANCE`,
  `MERCHANT_HOLD`, `SUPPLIER_PAYABLE`, `REVENUE`, `CASH`.
- **Journals** (`ledger_journals`): one business event each (top-up, hold, release, capture,
  reversal), with a unique `reference` such as `hold:<transactionId>`, so an event is never
  booked twice.
- **Entries** (`ledger_entries`): positive amounts in minor units with a direction. Every journal's
  debits equal its credits.
- **Holds** (`ledger_holds`): the lifecycle of each reservation (`ACTIVE → RELEASED | CAPTURED`),
  so a hold is closed exactly once.

| Operation | Debit            | Credit                                |
|-----------|------------------|---------------------------------------|
| top-up    | CASH             | MERCHANT_BALANCE                      |
| hold      | MERCHANT_BALANCE | MERCHANT_HOLD                         |
| release   | MERCHANT_HOLD    | MERCHANT_BALANCE                      |
| capture   | MERCHANT_HOLD    | SUPPLIER_PAYABLE (cost) + REVENUE (margin) |
| reversal  | mirror of the original journal                          ||

A balance is always derived as `SUM(credits) − SUM(debits)` over an account's entries (debits
minus credits for the debit-normal `CASH`). No table stores a balance.

### Enforced by the database, not only by the code

- A deferred constraint trigger rejects, at commit, any journal whose debits and credits differ
  or that mixes currencies.
- Statement-level triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on journals and entries.
  Corrections are new reversing journals (`reverseTopUp`), so history is never rewritten.
- `CHECK (amount > 0)` and the single-direction column rule out negative-amount tricks.

The service validates the same rules before writing. The database rules are the safety net for
bugs and manual SQL.

## Consequences

**Positive**

- Every rupiah has an audit trail. "Why is this balance X?" is a query, not an investigation.
- Invariants can be tested globally: total debits = total credits, no merchant account below
  zero, hold account = sum of active holds (`LedgerAssertions`, used by the property-style
  `LedgerInvariantTest`).
- Reconciliation (Phase 7) has a reliable internal record to compare against supplier files.

**Negative**

- Reading a balance aggregates entries instead of reading one value. An index on
  `(account_id) INCLUDE (direction, amount)` keeps this an index-only scan. For accounts with very
  long histories, a periodic **balance snapshot** (the balance up to entry N, plus the entries
  after N) can be added later without changing the model.
- More rows per business event (2–3 entries plus a journal).
- Reversal is limited to top-ups for now. Holds, releases and captures are corrected by the
  transaction flow itself, not by manual reversal.
