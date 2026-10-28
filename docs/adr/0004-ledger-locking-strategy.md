# ADR-004: Ledger locking strategy

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

A hold must never take a merchant below zero. "Check the balance, then write the entry" is a
race: two concurrent holds can both see enough funds and both succeed. Because balances are
computed from entries (ADR-003), there is no single row whose update the database could guard
with `UPDATE ... WHERE balance >= x`.

## Decision

**Pessimistic, per-merchant locking on the merchant's balance account.**

Every operation that reduces available funds (`hold`, `reverseTopUp`):

1. Locks the merchant's `MERCHANT_BALANCE` account row with `SELECT ... FOR NO KEY UPDATE`.
2. Re-checks idempotency (does this hold or reversal already exist?).
3. Computes the available balance from entries.
4. Writes the hold and its journal, or throws `InsufficientBalanceException` with nothing written.

Operations on an existing hold (`release`, `capture`) lock **the hold row** (`FOR UPDATE`), so a
racing release and capture of the same hold have exactly one winner. Top-ups only add funds and
take no lock.

Details that matter:

- **READ COMMITTED is required.** After waiting for the lock, the balance query starts a new
  snapshot and sees the entries the previous lock holder committed. Under REPEATABLE READ the
  snapshot would be older than the lock, and an overdraft would be possible.
- **`FOR NO KEY UPDATE`, not `FOR UPDATE`.** Inserting an entry takes a `FOR KEY SHARE` lock on
  the referenced account through the foreign key. `FOR UPDATE` would conflict with it; `FOR NO
  KEY UPDATE` does not.
- **System accounts are never locked.** `CASH`, `REVENUE` and `SUPPLIER_PAYABLE` are shared by
  all merchants. Locking them would serialize the whole switch on a few hot rows. They are only
  appended to, never checked, so they need no lock.
- **No deadlock cycle.** A hold locks one balance account. A release or capture locks one hold
  row. No operation waits on a second exclusive lock while holding the first.

## Alternatives considered

- **Optimistic locking** (version column plus retry). Under contention on one merchant (the
  1000-parallel test, or a merchant's burst traffic) most attempts fail and retry, wasting work
  and adding latency jitter. Pessimistic waiting gives predictable throughput for hot merchants.
- **Cached balance column updated with `WHERE balance >= x`.** Fast and lock-free in appearance,
  but it is a mutable balance the ledger was designed to avoid (ADR-003), and the cache must be
  kept consistent with the entries forever.
- **SERIALIZABLE isolation.** Correct, but it turns contention into serialization failures that
  every caller must retry, which is the same cost as optimistic locking.
- **Advisory locks keyed by merchant id.** Equivalent here, but they are not tied to a row and
  are easier to misuse (session versus transaction scope).

## Consequences

- Throughput per merchant is bounded by one hold at a time, which is the correct behavior for a
  single balance. Different merchants never block each other.
- `LedgerConcurrencyTest` proves it: 1000 parallel holds of 1,000 against a balance of 600,000
  give exactly 600 successes, 400 rejections, available = 0 and held = 600,000. With the lock
  removed, the same test fails (601 holds succeed, an overdraft).
- Callers must keep ledger work inside short transactions. The supplier call happens after
  commit (outbox, Phase 5).
