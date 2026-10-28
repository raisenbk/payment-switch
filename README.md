# payment-switch

[![CI](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml/badge.svg)](https://github.com/raisenbk/payment-switch/actions/workflows/ci.yml)

A mini payment switch / biller aggregator. Merchants submit top-up, data and voucher requests;
the switch debits their balance through a double-entry ledger, routes the request to a supplier,
resolves ambiguous (timed-out) results safely and notifies the merchant by webhook.

> Portfolio project. No real money, suppliers, credentials or customer data.

**Status:** Phase 1 (foundation) — see [PLAN.md](PLAN.md) for the roadmap.

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

## Error format

Every error response has the same shape, and the trace id is also returned in `X-Request-Id`:

```json
{ "code": "VALIDATION_FAILED", "message": "Request validation failed", "traceId": "…", "details": [] }
```

## Design decisions

- [ADR-001: Modular monolith over microservices](docs/adr/0001-modular-monolith.md)
