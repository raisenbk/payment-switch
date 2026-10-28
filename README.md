# payment-switch

[![CI](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml/badge.svg)](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml)

A mini payment switch / biller aggregator. Merchants submit top-up, data and voucher requests;
the switch debits their balance through a double-entry ledger, routes the request to a supplier,
resolves ambiguous (timed-out) results safely and notifies the merchant by webhook.

> Portfolio project. No real money, suppliers, credentials or customer data.

**Status:** Phase 2 (merchant auth & idempotency) — see [PLAN.md](PLAN.md) for the roadmap.

## Quick start

Requires Docker only.

```bash
git clone <this-repo> && cd payment-switch
docker compose up --build
curl http://localhost:8080/actuator/health
```

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

## Calling the API

Every `/v1/**` request is signed with the merchant's API secret (HMAC-SHA256):

```
X-Api-Key:   <api key>
X-Timestamp: <unix seconds, within ±5 min of server time>
X-Signature: hex(HMAC_SHA256(secret, METHOD \n PATH_WITH_QUERY \n X-Timestamp \n hex(SHA256(body))))
```

Example with `curl` and `openssl`:

```bash
API_KEY=pk_test_xxx SECRET=sk_test_xxx TS=$(date +%s)
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
