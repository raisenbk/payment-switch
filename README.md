# payment-switch

[![CI](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml/badge.svg)](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml)

A mini payment switch / biller aggregator. Merchants submit top-up, data and voucher requests;
the switch debits their balance through a double-entry ledger, routes the request to a supplier,
resolves ambiguous (timed-out) results safely and notifies the merchant by webhook.

> Portfolio project. No real money, suppliers, credentials or customer data.

**Status:** Phase 4 (transactions + mock supplier) — see [PLAN.md](PLAN.md) for the roadmap.

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
| `22` / `33` | answers after the read timeout (then succeeds / fails) | `UNKNOWN` (hold kept) |
| `44` | answers `PENDING` | `UNKNOWN` |
| `55` | processes, then returns HTTP 500 | `UNKNOWN` |
| `66` | rejects the request (HTTP 400) | `FAILED` |

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
                 └──────▶ UNKNOWN   (hold kept until an inquiry gives a definitive answer)
```

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
