# ADR-006: Transactional outbox for work that must happen after a commit

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Accepting a transaction writes three things in one database transaction: the `PENDING` row, its
first status event and the ledger hold. Then the supplier must be called. Until Phase 4 the
hand-off was an in-memory `@TransactionalEventListener(AFTER_COMMIT)`:

- If the process stopped between the commit and the listener (deploy, crash, OOM), the merchant's
  money stayed held and the transaction stayed `PENDING` forever. Nothing remembered that work was
  owed.
- The alternatives are both wrong: calling the supplier inside the database transaction holds
  locks and a connection during a slow network call (and a rollback cannot undo the top-up), while
  publishing to a broker before or after the commit is a dual write that can lose or invent work.

## Decision

Use a **transactional outbox**. The work itself is written to the database, in the same
transaction as the state change:

- `outbox_events(event_type, aggregate_id, payload, available_at, attempts, last_error, processed_at)`.
  `OutboxPublisher.publish` requires the caller's transaction (`Propagation.MANDATORY`), so an event
  exists if and only if the business change committed.
- `OutboxPoller` claims due events with one statement:
  `UPDATE … FROM (SELECT … FOR UPDATE SKIP LOCKED LIMIT n) … RETURNING …`. Concurrent pollers on
  any number of instances take disjoint rows without waiting for each other. The claim also moves
  `available_at` forward by a **lease** (60s, longer than the slowest handler). If the instance
  dies, the lease expires and another poll takes the event over.
- Each claimed event runs on its own virtual thread. Success sets `processed_at`. An exception
  schedules a retry with exponential backoff (1s doubling, capped at 5 min) and records `last_error`.
- Handlers are looked up by `event_type`. Phase 5 has `transaction.accepted`, which runs
  `TransactionProcessor`. Phase 6 adds webhook events through the same mechanism.

Delivery is **at-least-once**, never exactly-once. A lease can expire while a slow handler is still
running, and an instance can die after the handler ran but before `processed_at` was written. So
**every handler must be idempotent**. For `transaction.accepted`:

| Status found | Meaning | Action |
|---|---|---|
| `PENDING` | normal case | route, `PENDING → PROCESSING` (compare-and-set: only one runner wins), submit |
| `PROCESSING` | an earlier run committed `PROCESSING`, then stopped before recording the answer | **resubmit with the same reference** |
| anything else | already handled | nothing |

Resubmitting is safe only because the supplier deduplicates on our reference, the transaction id:
a second submit returns `409` with the stored outcome instead of topping up again. This is a
requirement of every supplier integration. A supplier without it would need the inquiry path
(`PROCESSING → UNKNOWN`) instead.

The UNKNOWN inquiry scheduler uses the same claiming technique on `transactions.next_inquiry_at`.
There the next backoff step doubles as the lease (see the ADR-005 addendum).

## Consequences

- No accepted transaction can be lost between the commit and the supplier call. Crash recovery is
  tested by leaving an event leased "by a dead instance" and expiring the lease, both before and
  after the supplier call: one top-up, one capture.
- Processing starts within one poll interval (250 ms) instead of immediately. This is acceptable
  because the API is asynchronous (`202 PENDING`) anyway.
- Polling costs a cheap indexed query (partial index on unprocessed rows) every interval per
  instance. Processed rows accumulate. A retention job can delete old processed events later.
- A handler that keeps failing retries forever at the capped delay and stays visible through
  `attempts` and `last_error`. Dead-lettering arrives with webhooks in Phase 6, where a permanent
  failure is expected.
- No message broker is needed. If one is added later (Phase 9), the outbox becomes its reliable
  publisher (a relay) instead of being replaced.
