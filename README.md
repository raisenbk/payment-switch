# payment-switch

[![CI](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml/badge.svg)](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml)

A mini payment switch / biller aggregator. Merchants submit top-up, data and voucher requests;
the switch debits their balance through a double-entry ledger, routes the request to a supplier,
resolves ambiguous (timed-out) results safely and notifies the merchant by webhook.

> Portfolio project. No real money, suppliers, credentials or customer data.

**Status:** Phase 7 (routing, failover, reconciliation) — see [PLAN.md](PLAN.md) for the roadmap.

## Quick start

Requires Docker (and `openssl` for request signing).

```bash
git clone https://github.com/raisenbk/payment-switch.git && cd payment-switch
docker compose up --build -d
./scripts/signed-curl.sh POST /v1/transactions '{"merchantRef":"order-1","productCode":"PULSA10","destination":"081234567890"}'
```

The `local` profile seeds a demo merchant (`pk_demo_local`, balance 1,000,000 IDR), products
`PULSA10`, `PULSA25`, `DATA5GB` and the mock supplier `MOCKA`. The POST returns `202` with a
`PENDING` transaction; fetch it with `./scripts/signed-curl.sh GET /v1/transactions/<id>`.

The mock supplier's behavior is chosen by the **last two digits of the destination**:

| Ends with | Supplier behavior | Resulting status |
|---|---|---|
| anything else | success | `SUCCESS` |
| `11` | definitive failure | `FAILED` (hold released) |
| `22` / `33` | answers after the read timeout (then succeeds / fails) | `UNKNOWN` → inquiry → `SUCCESS` / `FAILED` |
| `44` | answers `PENDING`, completes later | `UNKNOWN` → inquiry → `SUCCESS` |
| `55` | processes, then returns HTTP 500 | `UNKNOWN` → inquiry → `SUCCESS` |
| `66` | rejects the request (HTTP 400) | `FAILED` |
| `77` | answers `PENDING` forever | `UNKNOWN` → `MANUAL_REVIEW` after the max window (hold kept) |
| `88` | processes slowly, invisible to inquiries at first | `FAILED`, then flagged as a `LATE_SUCCESS` discrepancy |

Supplier `MOCKOOS` is always out of stock. The demo product `DATA5GB` is routed there first, so
buying it shows a **failover** to `MOCKA` in the transaction history.

The `local` profile uses a short inquiry schedule so you can watch this happen: first inquiry after
5s, `MANUAL_REVIEW` after 2 minutes, late-success verification after 1 minute (production defaults:
30s, 24h, 1h).

## Development

| Task | With a local JDK 21 | Without a JDK (runs Maven in Docker) |
|---|---|---|
| Build + all tests | `./mvnw verify` | `./scripts/mvn-docker.sh verify` |
| Run only Postgres/Redis | `docker compose up postgres redis` | same |
| Run the app locally | `./mvnw -pl app spring-boot:run` | `docker compose up --build app` |

Integration tests use [Testcontainers](https://testcontainers.com/), so Docker must be running.

Configuration comes from environment variables; see [.env.example](.env.example).

## Architecture

Modular monolith (Java 21, Spring Boot 4, PostgreSQL, Flyway). Modules live under
`com.paymentswitch`: `merchant`, `catalog`, `transaction`, `ledger`, `routing`, `supplier`,
`webhook`, `recon`, `common`. Details and diagrams will land in `docs/architecture.md`.

`mock-supplier/` is a separate Spring Boot app that simulates suppliers. The app's end-to-end
tests run its packaged jar in a container.

Transaction lifecycle:

```
PENDING ─▶ PROCESSING ─▶ SUCCESS   (hold captured)
                 ├──────▶ FAILED    (hold released)
                 └──────▶ UNKNOWN   (hold kept) ─inquiry─▶ SUCCESS | FAILED
                              └─max window─▶ MANUAL_REVIEW (hold kept, operator decides)
```

Work that must happen after a commit (calling the supplier, later webhooks) goes through a
transactional outbox: the event is written in the same database transaction as the state change
and picked up by a `SKIP LOCKED` poller, so a crash never loses an accepted transaction. Supplier
answers that contradict a status we already finalised are recorded in `transaction_discrepancies`.

## Calling the API

Every `/v1/**` request is signed with the merchant's API secret (HMAC-SHA256):

```
X-Api-Key:   <api key>
X-Timestamp: <unix seconds, within ±5 min of server time>
X-Signature: hex(HMAC_SHA256(secret, METHOD \n PATH_WITH_QUERY \n X-Timestamp \n hex(SHA256(body))))
```

[`scripts/signed-curl.sh`](scripts/signed-curl.sh) does this for you. By hand with `curl` and `openssl`:

```bash
API_KEY=pk_demo_local SECRET=sk_demo_local_only_not_secret TS=$(date +%s)
BODY_HASH=$(printf '' | openssl dgst -sha256 -hex | awk '{print $NF}')
SIG=$(printf 'GET\n/v1/me\n%s\n%s' "$TS" "$BODY_HASH" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
curl -H "X-Api-Key: $API_KEY" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" http://localhost:8080/v1/me
```

Mutating requests also need an `Idempotency-Key` header. Re-sending the same request with the same
key returns the original response (with `Idempotent-Replayed: true`) without executing it again;
reusing a key for a different request returns `409`.

## Routing, failover and circuit breaking

Each product can be mapped to several suppliers. Routing takes the eligible ones, which are active,
in the right currency, not more expensive than the price, not already tried and not behind an open
circuit. It then ranks them by health, priority, success rate and cost. If a supplier answers with
a **definitive** failure (out of stock, unreachable, rejected), the transaction fails over to the
next supplier. An **ambiguous** answer (timeout) never fails over: that could top the customer up
twice ([ADR-008](docs/adr/0008-failover-rules.md)). A circuit breaker per supplier removes a failing
supplier from routing for 30 seconds.

## Reconciliation

Suppliers send a daily settlement CSV of what they bill us:

```csv
reference,supplier_ref,sku,destination,amount,currency,completed_at
5f0c3c1e-…,MS-1A2B3C4D,A-PULSA10,081234567890,10000,IDR,2026-10-29T02:06:13Z
```

Importing it runs a Spring Batch job that matches every line against our transactions and our
day's successes against the file. It classifies each one as `MATCHED`, `AMOUNT_DIFF`,
`MISSING_INTERNAL` (billed, but not a success on our side), `MISSING_SUPPLIER` (our success, not
billed) or `DUPLICATE`. Re-importing the same file is a no-op (SHA-256 checksum).

```bash
curl -s http://localhost:8090/suppliers/MOCKA/settlements/$(date -u +%F)?discrepancies=true > mocka.csv
```
```bash
curl -s -X POST -H "X-Admin-Token: local-admin-token-not-secret" -H "Content-Type: text/csv" --data-binary @mocka.csv "http://localhost:8080/admin/recon/settlements?supplier=MOCKA&businessDate=$(date -u +%F)"
```
```bash
curl -s -H "X-Admin-Token: local-admin-token-not-secret" http://localhost:8080/admin/recon/settlements/1/discrepancies.csv
```

The mock supplier generates the file from what it really processed. `discrepancies=true` seeds one
of each mistake: a line dropped, an amount raised by 100, a line duplicated and an unknown
reference added.

## Webhooks

When a transaction reaches `SUCCESS` or `FAILED`, the merchant's callback URL receives:

```http
POST <callback_url>
Content-Type: application/json
X-Webhook-Id: 6f1c…                      (same on every retry: deduplicate on it)
X-Webhook-Timestamp: 1761700000
X-Webhook-Signature: hex(HMAC_SHA256(webhook_secret, X-Webhook-Timestamp + "." + raw body))

{"id":"6f1c…","type":"transaction.succeeded","createdAt":"…","data":{ …transaction as in GET… }}
```

Delivery is **at-least-once** and unordered: answer 2xx within 5s, deduplicate on `id`, verify the
signature over the raw body and reject old timestamps. Failed attempts are retried with exponential
backoff and jitter. After 10 attempts the webhook is dead-lettered, and an operator can re-drive it:

```bash
curl -H "X-Admin-Token: local-admin-token-not-secret" "http://localhost:8080/admin/webhooks?status=DEAD"
```
```bash
curl -X POST -H "X-Admin-Token: local-admin-token-not-secret" http://localhost:8080/admin/webhooks/<id>/redrive
```

Locally, the demo merchant's webhooks go to a receiver inside mock-supplier, so you can see them:

```bash
curl http://localhost:8090/webhooks/demo
```

## Error format

Every error response has the same shape, and the trace id is also returned in `X-Request-Id`:

```json
{ "code": "VALIDATION_FAILED", "message": "Request validation failed", "traceId": "…", "details": [] }
```

## Design decisions

- [ADR-001: Modular monolith over microservices](docs/adr/0001-modular-monolith.md)
- [ADR-002: HMAC request signing and idempotency](docs/adr/0002-hmac-signing-and-idempotency.md)
- [ADR-003: Double-entry ledger instead of a mutable balance column](docs/adr/0003-double-entry-ledger.md)
- [ADR-004: Ledger locking strategy](docs/adr/0004-ledger-locking-strategy.md)
- [ADR-005: A supplier timeout is UNKNOWN, not FAILED](docs/adr/0005-timeout-means-unknown.md)
- [ADR-006: Transactional outbox](docs/adr/0006-transactional-outbox.md)
- [ADR-007: At-least-once, signed webhook delivery](docs/adr/0007-at-least-once-webhooks.md)
- [ADR-008: Failover only after a definitive failure, never from UNKNOWN](docs/adr/0008-failover-rules.md)
