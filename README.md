# payment-switch

[![CI](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml/badge.svg)](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml)

A mini **payment switch / biller aggregator**: merchants buy prepaid top-ups, data packages and
vouchers through a signed API. The switch:
- debits their balance through a double-entry ledger;
- routes each request to one of several suppliers;
- resolves ambiguous (timed-out) supplier results without ever refunding on a guess;
- notifies the merchant by signed webhook;
- reconciles the suppliers' settlement files against its own books.

> Portfolio project (Java 21, Spring Boot 4, PostgreSQL). No real money, suppliers, credentials or
> customer data. Everything here is fictional.

## The problem it solves

Selling prepaid credit through third-party suppliers looks simple: take the merchant's money and
call the supplier. The hard parts are what happens when something goes wrong:

- **The supplier times out.** Did the customer get the credit or not? Refunding is wrong if they
  did, and keeping the money is wrong if they did not. Retrying at another supplier can top them up
  twice.
- **Requests are retried.** The merchant's network drops the response, so the merchant sends the
  same order again.
- **Many requests arrive at once** for the same merchant balance.
- **The process crashes** between "money held" and "supplier called".
- **The merchant's webhook endpoint is down** for hours.
- **The supplier's bill does not match** what we think happened.

This project handles each of these explicitly and proves it with tests (see
[Design decisions](#design-decisions)).

## Run it (3 commands)

Requires Docker, plus `openssl` and `curl` for signing requests.

```bash
git clone https://github.com/raisenbk/payment-switch.git && cd payment-switch
```
```bash
docker compose up --build -d
```
```bash
./scripts/signed-curl.sh POST /v1/transactions '{"merchantRef":"order-1","productCode":"PULSA10","destination":"081234567890"}'
```

The last command returns `202` with a `PENDING` transaction. Less than a second later
`./scripts/signed-curl.sh GET /v1/transactions/<id>` shows `SUCCESS`. Then open:

| What | Where |
|---|---|
| API docs (Swagger UI) | http://localhost:8081 |
| Grafana dashboard *Payment Switch* (no login) | http://localhost:3000 |
| Traces (Jaeger), service `payment-switch` | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| Webhooks the demo merchant received | http://localhost:8090/webhooks/demo |

If port 5432 is taken on your machine, start with `DB_PORT=5433 docker compose up --build -d`. Every
port binds to `127.0.0.1` only.

### Things to try

The `local` profile seeds a demo merchant (`pk_demo_local`, 1,000,000 IDR), the products `PULSA10`,
`PULSA25` and `DATA5GB`, and three mock suppliers. The mock supplier's behaviour is chosen by the
**last two digits of the destination**:

| Destination ends with | Supplier does | You will see |
|---|---|---|
| anything else | succeeds | `SUCCESS`, hold captured, webhook `transaction.succeeded` |
| `11` | rejects the number | `FAILED`, hold released, **no failover** (customer error) |
| `22` / `33` | answers after our read timeout | `UNKNOWN` (hold kept) → inquiry → `SUCCESS` / `FAILED` |
| `44` | answers `PENDING`, completes later | `UNKNOWN` → inquiry → `SUCCESS` |
| `55` | processes, then returns HTTP 500 | `UNKNOWN` → inquiry → `SUCCESS` |
| `66` | rejects the request (HTTP 400) | `FAILED` |
| `77` | answers `PENDING` forever | `UNKNOWN` → `MANUAL_REVIEW` after 2 min, hold kept |
| `88` | processes slowly, 404 to inquiries at first | `FAILED`, then flagged as a `LATE_SUCCESS` discrepancy |

The product `DATA5GB` is routed first to `MOCKOOS`, a supplier that is always out of stock, so
buying it shows a **failover** to `MOCKA`. The local inquiry schedule is short so you can watch it
happen: first inquiry after 5s, `MANUAL_REVIEW` after 2 minutes (production defaults: 30s and 24h).

## Architecture

A modular monolith with clear module boundaries. Diagrams of the context, modules, transaction
flow, state machine and ledger postings are in **[docs/architecture.md](docs/architecture.md)**.

```
PENDING ─▶ PROCESSING ─▶ SUCCESS   (hold captured)
             │  │  └───▶ FAILED    (hold released)
             │  └──────▶ PROCESSING at the next supplier (failover, definitive failures only)
             └─────────▶ UNKNOWN   (hold kept) ─inquiry─▶ SUCCESS | FAILED
                              └─max window─▶ MANUAL_REVIEW (operator decides)
```

| Module | Responsibility |
|---|---|
| `merchant` | HMAC request authentication, admin token, webhook targets, operator top-ups |
| `transaction` | state machine, processing, inquiries, late-answer detection |
| `ledger` | double-entry accounts, holds, captures, balances = sum of entries |
| `routing` | supplier selection and failover rules |
| `supplier` | HTTP gateways, per-supplier circuit breakers, latency metrics |
| `webhook` | signed at-least-once delivery, retries with jitter, dead letter, re-drive |
| `recon` | Spring Batch settlement reconciliation |
| `common` | transactional outbox, idempotency, errors, money, trace propagation |

## Calling the API

Every `/v1/**` request is signed with the merchant's API secret (HMAC-SHA256):

```
X-Api-Key:   <api key>
X-Timestamp: <unix seconds, within ±5 min of server time>
X-Signature: hex(HMAC_SHA256(secret, METHOD \n PATH_WITH_QUERY \n X-Timestamp \n hex(SHA256(body))))
```

[`scripts/signed-curl.sh`](scripts/signed-curl.sh) does this for you as the demo merchant. By hand:

```bash
API_KEY=pk_demo_local SECRET=sk_demo_local_only_not_secret TS=$(date +%s)
BODY_HASH=$(printf '' | openssl dgst -sha256 -hex | awk '{print $NF}')
SIG=$(printf 'GET\n/v1/me\n%s\n%s' "$TS" "$BODY_HASH" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
curl -H "X-Api-Key: $API_KEY" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" http://localhost:8080/v1/me
```

- **Idempotency.** `POST /v1/transactions` needs an `Idempotency-Key`. The same request with the
  same key returns the original response (`Idempotent-Replayed: true`) without executing it again.
  A different request with the same key returns `409`.
- **Errors** all have the same shape. `traceId` is the OpenTelemetry trace id, also returned as
  `X-Trace-Id`, so you can look the request up in Jaeger:
  ```json
  { "code": "VALIDATION_FAILED", "message": "Request validation failed", "traceId": "4bf92f35…", "details": [] }
  ```
- The full contract, including the webhook payload, is in [docs/openapi.yaml](docs/openapi.yaml)
  (shown by Swagger UI). A test fails if the spec and the controllers ever disagree.

## Webhooks

When a transaction reaches `SUCCESS` or `FAILED`, the merchant's callback URL receives:

```http
POST <callback_url>
X-Webhook-Id: 6f1c…                      (same on every retry: deduplicate on it)
X-Webhook-Timestamp: 1761700000
X-Webhook-Signature: hex(HMAC_SHA256(webhook_secret, X-Webhook-Timestamp + "." + raw body))

{"id":"6f1c…","type":"transaction.succeeded","createdAt":"…","data":{ …transaction as in GET… }}
```

Delivery is **at-least-once** and unordered. Failed attempts are retried with exponential backoff
and jitter. After 10 attempts the webhook is dead-lettered, and an operator can list and re-drive it:

```bash
curl -H "X-Admin-Token: local-admin-token-not-secret" "http://localhost:8080/admin/webhooks?status=DEAD"
```

## Reconciliation

Suppliers send a daily CSV of what they bill us. Importing it runs a Spring Batch job. The job
classifies every line, and every one of our successes that day, as one of:
- `MATCHED`
- `AMOUNT_DIFF`
- `MISSING_INTERNAL`: billed, but not a success on our side
- `MISSING_SUPPLIER`: our success, but not billed
- `DUPLICATE`

Re-importing the same file changes nothing (SHA-256 checksum). Try it with a file the mock supplier
generates, with one of each mistake seeded in:

```bash
curl -s "http://localhost:8090/suppliers/MOCKA/settlements/$(date -u +%F)?discrepancies=true" > mocka.csv
```
```bash
curl -s -X POST -H "X-Admin-Token: local-admin-token-not-secret" -H "Content-Type: text/csv" --data-binary @mocka.csv "http://localhost:8080/admin/recon/settlements?supplier=MOCKA&businessDate=$(date -u +%F)"
```

The response is the report. `GET /admin/recon/settlements/{id}/discrepancies.csv` exports the
discrepancies.

## Design decisions

Each important choice has a short ADR with context, decision and consequences:

| ADR | Decision |
|---|---|
| [001](docs/adr/0001-modular-monolith.md) | Modular monolith instead of microservices |
| [002](docs/adr/0002-hmac-signing-and-idempotency.md) | HMAC request signing; idempotency key claimed in the same DB transaction as the action |
| [003](docs/adr/0003-double-entry-ledger.md) | Double-entry ledger, balance = sum of entries, no mutable balance column |
| [004](docs/adr/0004-ledger-locking-strategy.md) | Per-merchant row lock on the balance account, READ COMMITTED |
| [005](docs/adr/0005-timeout-means-unknown.md) | A supplier timeout is `UNKNOWN`, not `FAILED`; inquiries, `MANUAL_REVIEW`, late answers flagged |
| [006](docs/adr/0006-transactional-outbox.md) | Transactional outbox with `SKIP LOCKED` leases; crash recovery resubmits with the same reference |
| [007](docs/adr/0007-at-least-once-webhooks.md) | At-least-once signed webhooks, backoff + jitter, dead letter, re-drive |
| [008](docs/adr/0008-failover-rules.md) | Failover only after a definitive failure, never from `UNKNOWN`; circuit breaker per supplier |
| [009](docs/adr/0009-trace-context-through-the-outbox.md) | Trace context stored with outbox and webhook events, so one trace follows a transaction |

## Test strategy

All tests run in CI on every push (`./mvnw verify`). There are about 200 of them across these layers:

- **Unit tests** for pure decision logic, each tested as a decision table: state transitions,
  routing ranking, reconciliation classification, retry backoff and jitter, circuit breaker
  health rules, signature vectors computed independently with `openssl`.
- **Integration tests** against real PostgreSQL (Testcontainers) and the real mock-supplier jar
  in a container. They send real signed HTTP requests. Every ambiguous-result, failover,
  crash-recovery, webhook-retry and reconciliation scenario is covered end to end.
- **Concurrency tests**:
  - 50 parallel identical requests execute once;
  - 1000 parallel holds never overdraw a balance;
  - competing outbox and webhook pollers handle each event exactly once.
- **Invariant checks** after every money-related test: debits = credits, every journal balances,
  no merchant account below zero, held balance = active holds.
- **Mutation checks** (done by hand, recorded in [PLAN.md](PLAN.md)): the concurrency and failover
  tests were run against deliberately broken code, to confirm they catch the bug:
  - no lock;
  - check-then-insert idempotency;
  - `SKIP LOCKED` removed;
  - failover from `UNKNOWN`.

## Observability and performance

- Grafana dashboard (provisioned from [ops/grafana](ops/grafana)): throughput, success ratio, open
  `UNKNOWN`/`MANUAL_REVIEW`, API p95/p99, supplier latency and outcomes, circuit state, outbox lag,
  webhook retries and dead letters, failovers and discrepancies.
- Traces in Jaeger follow one transaction from the API through the outbox to the supplier and the
  webhook (ADR-009). Logs are structured JSON with `traceId`, `merchantId` and `transactionId`.
- Load tests with k6 ([loadtest/](loadtest)): steady load, burst, and a supplier timeout storm.
  Results are recorded in **[docs/performance.md](docs/performance.md)**. There are no numbers yet:
  the page stays a template until real runs are measured.

```bash
./scripts/k6.sh steady
```

## Development

| Task | With a local JDK 21 | Without a JDK (Maven in Docker) |
|---|---|---|
| Build + all tests | `./mvnw verify` | `./scripts/mvn-docker.sh verify` |
| Run the stack | `docker compose up --build -d` | same |

Integration tests need Docker. Configuration comes from environment variables, listed in
[.env.example](.env.example). The demo keys, tokens and secrets in this repository are local
development values and are marked as such.

The roadmap and the decisions made along the way are in [PLAN.md](PLAN.md). Notes for AI-assisted
development are in [CLAUDE.md](CLAUDE.md).
