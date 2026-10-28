# CLAUDE.md

Read [PLAN.md](PLAN.md) first. Work only on the phase the user names and tick the checklist
items you finish. Sections 1 and 2 of PLAN.md are repeated here so every session loads them.

## Project brief

**payment-switch**: a mini payment switch / biller aggregator. Merchants send transaction
requests (prepaid top-up, data, vouchers). The system debits merchant balance via a ledger,
routes the request to one of several suppliers, handles ambiguous results (timeouts), and
notifies the merchant by webhook.

Portfolio project for backend roles: idempotency, consistency, concurrency, failure handling,
reconciliation. No real money, no real supplier credentials, no frontend beyond Swagger UI.
Only generic/fictional data.

**Stack:** Java 21, Spring Boot 4.1 (Maven), PostgreSQL 17, Redis, Flyway, Spring Security,
Spring Batch, Spring Data JPA, Testcontainers, JUnit 5, AssertJ, Docker Compose, GitHub
Actions, Micrometer + Prometheus + Grafana, OpenTelemetry, k6. Kafka/Kubernetes optional
(Phase 9).

## Ground rules

- **Architecture:** modular monolith. Modules communicate through public interfaces/events,
  never through each other's repositories.
- **Money:** integer minor units (`long`) or `BigDecimal`. Never `double`/`float`. Currency
  stored explicitly.
- **Ledger is the source of truth for balances.** Never a mutable `balance` column as the only truth.
- **Every schema change goes through a Flyway migration.** Never edit an applied migration.
- **Idempotency everywhere:** API requests, supplier calls, webhooks, scheduled jobs.
- **Timeout ≠ failure.** An ambiguous supplier result is `UNKNOWN`, never auto-refunded until a
  definitive answer exists.
- **No secrets in the repo.** Environment variables only; commit only `.env.example`.
- **Tests first for money-related logic.** Unit + integration (Testcontainers) + concurrency.
- **Small commits** with conventional messages (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- **Every important design choice gets a short ADR** in `docs/adr/`.
- **Logging:** structured, include `transactionId` and `merchantId` via MDC. Never log secrets,
  API keys, or full signatures.
- **Keep code boring and readable.** No premature abstraction, no unused dependencies.

## Working in this repo

- Build + test: `./mvnw verify` (needs JDK 21), or `./scripts/mvn-docker.sh verify` (Docker only).
- Integration tests extend `AbstractIntegrationTest` (shared PostgreSQL Testcontainer).
- Errors: throw `ApiException` subclasses; `GlobalExceptionHandler` renders `ErrorResponse`.
- `/v1/**` is HMAC-authenticated (`HmacAuthenticationFilter`); get the caller with
  `@AuthenticationPrincipal MerchantPrincipal`. Integration tests sign real HTTP requests via
  `support.SignedRequests` and create isolated merchants via `support.TestMerchants`.
- Mutating endpoints wrap their work in `IdempotencyService.execute(...)` with
  `RequestFingerprint.of(request)`. The action must be short local DB work (see ADR-002).
- Spring Boot 4 notes: Jackson 3 (`tools.jackson.*`); `@WebMvcTest` does not load Spring Security.
- Shared secrets (API HMAC secret, webhook secret) must be **encrypted**, not hashed: the
  server needs the clear value to compute HMACs.
