# ADR-005: A supplier timeout is UNKNOWN, not FAILED

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

When we send a top-up to a supplier and the response does not arrive in time, there are two
possibilities we cannot tell apart from our side:

1. The supplier never processed it. The customer got nothing.
2. The supplier processed it, the customer got their credit, and only the response was lost or
   slow (network issue, supplier overloaded, our read timeout too short).

If we treat a timeout as a failure, release the hold and tell the merchant "failed", then in case
2 the customer keeps the credit, the supplier bills us, and the merchant has their money back.
We lose the full amount. At scale this is a classic and expensive bug in payment and biller
systems. Retrying with another supplier makes it worse: the customer may be credited twice.

## Decision

Supplier outcomes are a closed, typed set (`SupplierResult`): `Success`, `Failed(code)`,
`Unknown(reason)`. Only `Success` and `Failed` are definitive and move money:

| Result  | Transaction status | Ledger                                      |
|---------|--------------------|---------------------------------------------|
| Success | SUCCESS            | capture hold → supplier payable + revenue   |
| Failed  | FAILED             | release hold                                |
| Unknown | UNKNOWN            | **hold stays**; resolved later by inquiry   |

The HTTP adapter maps conservatively: when in doubt, **Unknown**.

| What happened                                      | Submit                | Inquiry |
|----------------------------------------------------|-----------------------|---------|
| 2xx or 409 with body `SUCCESS` / `FAILED`          | Success / Failed      | same    |
| 2xx with `PENDING` or an unreadable body           | Unknown               | Unknown |
| 4xx (request rejected as invalid)                  | Failed                | Unknown (404 → Failed) |
| 5xx                                                | Unknown               | Unknown |
| Connection refused / connect timeout               | Failed                | Unknown |
| Read timeout, connection reset, other I/O error    | Unknown               | Unknown |

Reasoning for the less obvious rows:

- **5xx → Unknown.** A supplier can commit the top-up and then crash while building the response.
  The mock supplier's `SERVER_ERROR_AFTER_SUCCESS` behavior simulates exactly that.
- **Connection refused → Failed on submit.** If the TCP connection was never established, not a
  byte of the request left our machine, so a definitive failure is safe. On **inquiry** the same
  error says nothing about the original submit, so it stays Unknown.
- **409 → use the body.** Suppliers deduplicate on our reference (the transaction id). A duplicate
  submit returns the stored outcome, which is authoritative.
- **Inquiry 404 → Failed.** This relies on the supplier contract that every accepted submit is
  queryable. A supplier without that guarantee must map 404 to Unknown instead.

Timeouts are explicit per supplier: `connect-timeout` (short, safe to fail) and `read-timeout`
(longer, failing makes the result Unknown).

The state machine enforces this. `PENDING` can only go to `PROCESSING`, and the only way into
`UNKNOWN` is from `PROCESSING`. From `UNKNOWN` a transaction can reach `SUCCESS` or `FAILED`
only through a definitive answer, or `MANUAL_REVIEW` after the maximum window (Phase 5).

## Consequences

- Merchants may see `UNKNOWN` for a while, and their funds stay held until the inquiry resolves.
  This is the honest state, and it is documented in the API.
- Phase 5 adds the inquiry scheduler with backoff and `MANUAL_REVIEW` escalation, plus detection
  of late definitive answers that contradict an earlier final status.
- Failover to another supplier (Phase 7) is allowed only after a definitive `Failed`, never after
  `Unknown`.
- `TransactionFlowIntegrationTest` covers every row against the real mock-supplier: timeouts, 500
  and pending all end in UNKNOWN with the hold intact, and definitive failures release it.

## Addendum (Phase 5, 2026-10-29): inquiries, escalation and late answers

- **Inquiry schedule.** Entering `UNKNOWN` sets `transactions.next_inquiry_at` (first inquiry
  after 30s) and `inquiry_deadline` (max window, 24h). `InquiryScheduler` claims due rows with
  `FOR UPDATE SKIP LOCKED` and, in the same statement, moves `next_inquiry_at` to the next step of
  the exponential backoff (30s, 1m, 2m, … capped at 30 min). That is both the retry plan and the
  lease, so several instances never ask about the same transaction at the same time.
- **Escalation.** An inquiry that is still ambiguous after the deadline moves the transaction to
  `MANUAL_REVIEW`. The **hold is kept**: money is never refunded on a guess.
- **Late answers are flagged, never applied and never dropped.** A definitive supplier answer can
  contradict a final status we already set: a duplicate runner losing the compare-and-set race,
  or a supplier that answered `404` (so we failed the transaction) while it was in fact still
  processing. That answer is written to `transaction_discrepancies` (`LATE_SUCCESS` or
  `LATE_FAILURE`) and logged at WARN. The status and the ledger stay untouched, because money
  already moved and a human (or reconciliation in Phase 7) decides the correction.
- **Verification after an inquiry failure.** Inquiry `404 → Failed` depends on the supplier
  contract, which may be broken in practice. So a transaction that went `UNKNOWN → FAILED` is
  inquired once more after `verify-after` (1h). If the supplier now reports `SUCCESS`, a
  `LATE_SUCCESS` discrepancy is raised. The mock supplier's `SLOW_SUCCESS` behavior (suffix `88`)
  reproduces this end to end.
