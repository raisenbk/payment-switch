# ADR-007: At-least-once, signed webhook delivery

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Merchants need to learn the outcome of a transaction without polling `GET /v1/transactions/{id}`.
Their endpoints are outside our control: they can be slow, down for hours, return errors, or
receive forged requests from someone else. A notification must never be lost because of a crash
on our side. It must also never be slow enough to block the payment flow.

Exactly-once delivery over HTTP is impossible. If the merchant processed the webhook and the
response was lost, we cannot tell that apart from a webhook that never arrived. So the only choice
is between at-most-once (risks losing notifications) and at-least-once (may send duplicates).

## Decision

**At-least-once, with a stable event id so merchants can deduplicate.**

1. **Driven by the outbox.** Reaching a final status (`SUCCESS`/`FAILED`) publishes
   `transaction.completed` to the outbox, in the same database transaction as the status change
   (ADR-006). Its handler creates one `webhook_events` row, idempotently (unique on the outbox event
   id). That row holds the merchant-facing **event id** and the **exact JSON body** to send.
   Merchants without a callback URL get no row.
2. **Payload.** `{"id", "type": "transaction.succeeded" | "transaction.failed", "createdAt", "data": <transaction as returned by GET>}`.
   Every retry sends the same body and the same id (also in `X-Webhook-Id`).
3. **Signature.** `X-Webhook-Signature = hex(HMAC_SHA256(webhook_secret, X-Webhook-Timestamp + "." + body))`,
   using the merchant's own webhook secret (stored encrypted, ADR-002). The timestamp is set per
   attempt. Receivers should verify over the raw body, compare in constant time, and reject
   timestamps older than about 5 minutes.
4. **Dispatcher.** `WebhookDispatcher` claims due rows with `FOR UPDATE SKIP LOCKED` and a lease,
   like the outbox, and POSTs each one on a virtual thread. It uses a 2s connect timeout, a 5s read
   timeout and no redirects. Only **2xx** counts as delivered. Any other status, a timeout or a
   connection error is a failed attempt.
5. **Retry.** Exponential backoff with **jitter**: the delay after attempt *n* is drawn uniformly
   from `[d/2, d]`, where `d = min(30s · 2^(n-1), 6h)`. Without jitter, every webhook that failed
   during a merchant outage would retry at the same moment, and the recovering endpoint would be
   hit with synchronized waves.
6. **Dead letter.** After 10 attempts (roughly 3 to 4 hours with the default schedule: nine delays of 30s up to 2h8m, minus jitter) the row becomes
   `DEAD`. Nothing is deleted.
7. **Tracking.** Each attempt appends a `webhook_deliveries` row (attempt number, URL, status code
   or none, duration, error). The table is append-only, enforced by a DB trigger.
8. **Operator API**, authenticated with `X-Admin-Token` (constant-time compare; the admin API is
   disabled when no token is configured):
   - `GET /admin/webhooks?status=DEAD` lists webhooks with their attempts.
   - `GET /admin/webhooks/{id}` shows one webhook.
   - `POST /admin/webhooks/{id}/redrive` puts a `DEAD` webhook back to `PENDING` with a fresh set
     of attempts. It is the same event id and body, so merchants still deduplicate. Attempt numbers
     keep counting.

### Merchant contract (to be documented in the API docs)

- Deduplicate on `id`. The same event can arrive more than once.
- Ordering is not guaranteed. Use `data.status` and `data.updatedAt`. A transaction's final
  status never changes, so at most one final event exists per transaction.
- Answer 2xx quickly and do the work asynchronously. The read timeout is 5s.

## Consequences

- No lost notifications: from the commit of the final status up to `DEAD`, every step is persisted
  and resumable after a crash.
- Duplicates are possible: a lease can expire during a slow attempt, or an instance can die after
  sending but before recording. That is why the contract asks merchants to deduplicate on `id`.
- The webhook body is frozen when the event is created, so it reflects the transaction at
  completion, not its current state. That is intended.
- Callback URLs are set by operators (seed data), not by merchants. If merchants can set them
  later, URL validation against SSRF (no private/link-local addresses, https only) must come with
  that feature.
- The admin token is one shared secret without roles or an audit trail. This is enough for a
  portfolio project, and it would need replacing with real operator identity (SSO) in production.
