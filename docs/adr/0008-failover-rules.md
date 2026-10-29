# ADR-008: Failover only after a definitive failure, never from UNKNOWN

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

A product is usually available from several suppliers. When the chosen supplier cannot deliver,
trying the next one turns a failed sale into a successful one. But failover is also the easiest
way to **top up a customer twice**. If supplier A did deliver and we ask supplier B as well, the
customer gets both top-ups, both suppliers bill us, and the merchant paid once.

The deciding question is whether we *know* that A did not deliver. ADR-005 already splits supplier
outcomes into definitive (`Success`, `Failed`) and ambiguous (`Unknown`).

## Decision

### When to fail over

| Outcome at the current supplier | Fail over? | Why |
|---|---|---|
| `Success` | no | done |
| `Unknown` (timeout, 5xx, `PENDING`, unreadable) | **never** | A may have delivered; the transaction becomes `UNKNOWN` and only inquiries **at A** resolve it |
| `Failed` with a non-retryable code (default: `INVALID_DESTINATION`) | no | the customer's input is wrong; every supplier would answer the same |
| `Failed` with any other code (`OUT_OF_STOCK`, `SUPPLIER_UNREACHABLE`, `CIRCUIT_OPEN`, a 4xx rejection, …) | **yes**, while another eligible supplier remains and fewer than `max-suppliers-per-transaction` (3) were tried | A definitively did nothing |

`SUPPLIER_UNREACHABLE` means connection refused or connect timeout: not a byte was sent, so it is
definitive. `CIRCUIT_OPEN` means we did not send anything ourselves.

### How

- Failover happens inside the processing run, without leaving `PROCESSING`. One DB transaction
  does a compare-and-set on `(status = PROCESSING, supplier_id = A)`. It switches the route (supplier,
  SKU, frozen cost) to B, appends A to `attempted_supplier_ids`, and writes a
  `PROCESSING → PROCESSING` event with `{from, to, code}`. Only then is B called.
- The supplier reference stays our transaction id. Each supplier deduplicates in its own namespace,
  so crash recovery (ADR-006) resubmits to whichever supplier is current, and the stored route
  always says which one that is.
- Capture books the supplier payable to the supplier that delivered, at that supplier's cost.

### Routing (which supplier, in which order)

A pure function (`RoutingPolicy`), tested as a decision table. A supplier is eligible when the
supplier and mapping are active, the currency matches, the cost is not above the price, it has
not been tried for this transaction, and it is available (configured, circuit not open). Eligible
suppliers are ranked by:

1. healthy first (`success_rate ≥ 0.80`)
2. priority
3. success rate
4. cost
5. supplier id

### Circuit breaker

There is one Resilience4j breaker per supplier, on submits only, count-based: it opens at 50%
unhealthy calls over the last 20, after at least 10 calls, and stays open for 30s, then allows
3 trial calls. *Unhealthy* means `Unknown` or unreachable. A definitive business answer ("invalid
number", "out of stock") proves the supplier is up. An open circuit removes the supplier from
routing, so no request is sent to a supplier that is known to be down. Inquiries bypass the
breaker: they are read-only, already spaced by backoff, and must continue for `UNKNOWN`
transactions.

## Consequences

- No double top-ups from failover. An `UNKNOWN` at A costs the customer a wait (inquiry, possibly
  `MANUAL_REVIEW`) instead of a risky retry at B.
- Failover adds latency (one more supplier call per attempt) and is capped at 3 suppliers.
- During an outage of A, transactions that were already sent to A and timed out stay `UNKNOWN`.
  The breaker only protects the transactions that follow.
- `success_rate` is an input maintained by operations (or later by reconciliation statistics). It
  is not updated live on every call.
- The non-retryable list is configuration: it depends on how each supplier names its error codes.
