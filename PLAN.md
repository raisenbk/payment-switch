# Payment Switch — Project Plan & Checklist

> Put this file in the repo root. Tell Claude Code: *"Read PLAN.md. Work only on the phase I name. Tick the checklist items you finish."*

---

## 0. How to use this file with Claude Code

1. Save this file as `PLAN.md` in the repo root.
2. Copy section **1 (Project Brief)** and **2 (Ground Rules)** into a `CLAUDE.md` so every session loads them automatically.
3. Work **one phase at a time**. Start a session with the prompt in that phase's "Claude Code prompt" block.
4. After each phase: run all tests, review the diff yourself, tick the checkboxes, commit.
5. Never move to the next phase while the **Definition of Done (DoD)** of the current phase is unmet.

---

## 1. Project Brief

**Name:** `payment-switch` (working title)

**What it is:** A mini payment switch / biller aggregator. Merchants send transaction requests (prepaid top-up, data, vouchers). The system debits merchant balance via a ledger, routes the request to one of several suppliers, handles ambiguous results (timeouts), and notifies the merchant by webhook.

**Why it exists:** Portfolio project demonstrating backend engineering on real payment problems: idempotency, consistency, concurrency, failure handling, reconciliation. Target audience: backend recruiters (including in Germany).

**Non-goals:** No real money, no real supplier credentials, no frontend beyond Swagger UI (optional simple admin page later). Use only generic/fictional data.

**Stack:** Java 21, Spring Boot 4.1 (Maven; 3.5 is out of OSS support), PostgreSQL, Redis, Flyway, Spring Security, Spring Batch, Spring Data JPA (or jOOQ for ledger queries), Testcontainers, JUnit 5, AssertJ, Docker Compose, GitHub Actions, Micrometer + Prometheus + Grafana, OpenTelemetry, k6 (load test). Kafka is optional (Phase 9).

---

## 2. Ground Rules (for Claude Code)

- **Architecture:** modular monolith. Modules communicate through public interfaces/events, never through each other's repositories.
- **Money:** use `BigDecimal` or integer minor units (`long`). Never `double`/`float`. Currency stored explicitly.
- **Ledger is the source of truth for balances.** Never store a mutable `balance` column as the only truth.
- **Every schema change goes through a Flyway migration.** Never edit an applied migration.
- **Idempotency everywhere:** API requests, supplier calls, webhooks, scheduled jobs.
- **Timeout ≠ failure.** An ambiguous supplier result is `UNKNOWN`, never auto-refunded until a definitive answer exists.
- **No secrets in the repo.** Use environment variables; commit only `.env.example`.
- **Tests first for money-related logic.** Unit + integration (Testcontainers) + concurrency tests.
- **Small commits** with conventional messages (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- **Every important design choice gets a short ADR** in `docs/adr/`.
- **Logging:** structured, include `transactionId` and `merchantId` via MDC. Never log secrets, API keys, or full signatures.
- **Keep code boring and readable.** No premature abstraction, no unused dependencies.

---

## 3. Architecture

```
Merchant ──HTTPS + HMAC──▶ [API Layer]
                              │
                 ┌────────────┼─────────────┐
                 ▼            ▼             ▼
           [Transaction]   [Ledger]   [Merchant/Catalog]
                 │
           [Routing Engine] ──▶ [Supplier Adapter A / B / C (mock)]
                 │                         │
           [Outbox]                 [Inquiry/Advice Scheduler]
                 │
           [Webhook Dispatcher] ──▶ Merchant callback

[Reconciliation (Spring Batch)] ◀── supplier settlement file
```

**Package layout**

```
com.yourname.paymentswitch
 ├─ merchant      auth, API key, HMAC verification
 ├─ catalog       products, prices, supplier mapping
 ├─ transaction   state machine, idempotency
 ├─ ledger        accounts, journals, double-entry
 ├─ routing       supplier selection, failover
 ├─ supplier      SupplierGateway interface + adapters
 ├─ webhook       dispatcher, retry, DLQ
 ├─ recon         Spring Batch job
 └─ common        outbox, errors, audit, config
```

**Repo layout**

```
/                  PLAN.md, CLAUDE.md, README.md, docker-compose.yml
/app               main Spring Boot application
/mock-supplier     small separate Spring Boot app simulating suppliers
/loadtest          k6 scripts
/docs              architecture.md, adr/, openapi.yaml, diagrams/
```

---

## 4. Core Transaction Flow

1. `POST /v1/transactions` with headers `Idempotency-Key`, `X-Api-Key`, `X-Timestamp`, `X-Signature`.
2. Verify signature and timestamp window; check idempotency key (same key + same body hash → return stored response; same key + different body → `409`).
3. In **one DB transaction**: create transaction `PENDING`, place **hold** on merchant balance (ledger journal), write event to **outbox**.
4. Worker consumes outbox, selects supplier (routing), calls supplier adapter.
5. Outcome handling:
   - **Definitive success** → `SUCCESS`; convert hold to final debit; book revenue/supplier payable.
   - **Definitive failure** → `FAILED`; release hold.
   - **Timeout / ambiguous** → `UNKNOWN`; keep hold; scheduler runs inquiry with backoff until definitive; after max window → `MANUAL_REVIEW`.
6. Dispatch webhook to merchant with retry/backoff; exhausted retries → DLQ.

**State machine**

```
PENDING ─▶ PROCESSING ─▶ SUCCESS
                │  └────▶ FAILED
                └───────▶ UNKNOWN ─(inquiry)─▶ SUCCESS | FAILED
                              └─(max window)─▶ MANUAL_REVIEW
```

Illegal transitions must throw. Every transition writes a row to `transaction_events`.

---

## 5. Database Schema (PostgreSQL)

```sql
merchants(id, name, status, callback_url, webhook_secret, created_at)
api_credentials(id, merchant_id, api_key, secret_hash, is_active)

products(id, code, name, category, base_price)
suppliers(id, code, name, is_active)
supplier_products(id, supplier_id, product_id, supplier_sku,
                  cost_price, success_rate, priority, is_active)

transactions(
  id UUID PK, merchant_id, merchant_ref, product_id,
  destination, amount, status, supplier_id, supplier_ref,
  failure_code, created_at, updated_at, version,
  UNIQUE (merchant_id, merchant_ref)
)
transaction_events(id, transaction_id, from_status, to_status,
                   payload JSONB, created_at)          -- append-only audit

idempotency_keys(merchant_id, key, request_hash, response_body JSONB,
                 status, created_at, PRIMARY KEY (merchant_id, key))

ledger_accounts(id, owner_type, owner_id, type, currency)
   -- types: MERCHANT_BALANCE, MERCHANT_HOLD, REVENUE, SUPPLIER_PAYABLE
ledger_journals(id, transaction_id, description, created_at)
ledger_entries(id, journal_id, account_id, direction, amount)
   -- invariant: sum(DEBIT) = sum(CREDIT) per journal

outbox_events(id, aggregate_id, type, payload JSONB,
              status, attempts, next_retry_at, created_at)

webhook_deliveries(id, transaction_id, url, attempt, http_status,
                   next_retry_at, status)

settlement_files(id, supplier_id, file_name, checksum, imported_at)
recon_results(id, settlement_file_id, transaction_id,
              match_status, diff_amount)
   -- MATCHED | MISSING_INTERNAL | MISSING_SUPPLIER | AMOUNT_DIFF
```

Technical notes:
- Balance = aggregate of `ledger_entries` (optional cached snapshot, rebuilt from entries).
- Use `SELECT ... FOR UPDATE` or optimistic locking (`@Version`) on accounts; prove it with a concurrency test.
- Add indexes for: `transactions(merchant_id, created_at)`, `transactions(status)`, `outbox_events(status, next_retry_at)`, `ledger_entries(account_id)`.

---

## 6. Phased Checklist

Legend: `[ ]` todo · `[x]` done. Each phase ends with a DoD that must be fully true.

### Phase 1 — Foundation (Week 1)

- [x] Init Spring Boot 3 / Java 21 project (Gradle or Maven), package layout from section 3
- [x] `docker-compose.yml` with PostgreSQL and Redis
- [x] Flyway configured; baseline migration with `merchants`, `api_credentials`, `products`, `suppliers`, `supplier_products`
- [x] Testcontainers base test class (PostgreSQL) reused by all integration tests
- [x] Global error handling (`@ControllerAdvice`), consistent error JSON (`code`, `message`, `traceId`)
- [x] Actuator health endpoint; profiles `local` / `test`
- [x] GitHub Actions: build + test on every push
- [x] `.env.example`, `.gitignore`, initial `README.md` skeleton
- [x] ADR-001: modular monolith over microservices

**DoD:** `docker compose up` + app starts; CI pipeline green; one integration test runs against Testcontainers.

**Claude Code prompt**
> Read PLAN.md and CLAUDE.md. Implement Phase 1 only. Create the project skeleton, Docker Compose, Flyway baseline, Testcontainers base class, global error handling, and CI workflow. Do not implement any business logic yet. Tick the Phase 1 checkboxes you complete.

---

### Phase 2 — Merchant Auth & Idempotency (Week 2)

- [x] API key lookup + HMAC-SHA256 signature verification (canonical string: method, path, timestamp, body hash)
- [x] Timestamp tolerance window (replay protection)
- [x] Constant-time signature comparison
- [x] Spring Security filter chain for `/v1/**`
- [x] `idempotency_keys` migration + service (store request hash and response)
- [x] Same key + same body → replay stored response; same key + different body → `409`
- [x] Concurrent requests with the same key → only one executes (DB unique constraint or advisory lock)
- [x] Tests: valid/invalid signature, expired timestamp, replayed request, concurrent same key
- [x] ADR-002: HMAC request signing and idempotency design

**DoD:** 50 parallel identical requests create exactly one record; invalid signatures always return `401`.

**Claude Code prompt**
> Implement Phase 2 only. Build HMAC auth and idempotency per PLAN.md section 4. Write the concurrency test first (50 parallel identical requests → exactly one effect), then make it pass. Never log secrets or signatures.

---

### Phase 3 — Ledger (Week 3)

- [x] Migrations: `ledger_accounts`, `ledger_journals`, `ledger_entries` (+ DB check/trigger or service guard for balanced journals)
- [x] Ledger service API: `topUp`, `hold`, `release`, `capture`, `getBalance`
- [x] Journals are immutable; corrections use reversing journals
- [x] Insufficient balance → domain exception, no partial writes
- [x] Locking strategy implemented (pessimistic or optimistic with retry)
- [x] Concurrency test: 1000 parallel holds on one merchant, final balance exactly correct, never negative
- [x] Property-style test: sum of all debits = sum of all credits across the DB
- [x] ADR-003: ledger over mutable balance column; ADR-004: locking strategy

**DoD:** concurrency + invariant tests pass repeatedly (run 10×, zero flakiness).

**Claude Code prompt**
> Implement Phase 3 only: the ledger module. Double-entry, immutable journals, hold/release/capture. Write the 1000-parallel-hold concurrency test and the debit=credit invariant test, then implement until they pass. Use BigDecimal/minor units only.

---

### Phase 4 — Transaction State Machine & Mock Supplier (Week 4)

- [x] `transactions` + `transaction_events` migrations
- [x] State machine with explicit allowed transitions; illegal transition throws
- [x] `POST /v1/transactions` and `GET /v1/transactions/{id}`
- [x] Creating a transaction: validate product, place hold, store `PENDING` in one DB transaction
- [x] `SupplierGateway` interface (`submit`, `inquiry`) with typed results: `SUCCESS`, `FAILED(code)`, `UNKNOWN`
- [x] `mock-supplier` app: configurable behavior per destination/amount (success, definitive fail, timeout, delayed success, duplicate response)
- [x] Supplier adapter with HTTP client, connect/read timeouts, mapping of errors → typed results
- [x] End-to-end test: success path and definitive-failure path (hold released)
- [x] ADR-005: why timeout maps to `UNKNOWN`, not `FAILED`

**DoD:** end-to-end flow works against the mock supplier; every transition is in `transaction_events`.

**Claude Code prompt**
> Implement Phase 4 only. Add the transaction state machine, the SupplierGateway interface, the separate mock-supplier app with configurable behaviors, and the end-to-end tests for success and definitive failure. Timeouts must produce UNKNOWN, not FAILED.

---

### Phase 5 — UNKNOWN Handling & Outbox (Week 5)

- [x] `outbox_events` migration; event written in the same DB transaction as the state change
- [x] Outbox poller with `FOR UPDATE SKIP LOCKED`; safe with multiple instances
- [x] Inquiry scheduler for `UNKNOWN` transactions: exponential backoff, max window, then `MANUAL_REVIEW`
- [x] Late supplier success after we already marked failed → detected and flagged (never silently ignored)
- [x] Tests: timeout → inquiry → success; timeout → inquiry → failed (hold released); timeout beyond max window → manual review
- [x] Test: app crash between DB commit and supplier call → outbox recovers, no duplicate supplier submission (supplier idempotency via our reference)
- [x] ADR-006: transactional outbox

**DoD:** all failure scenarios above covered by automated tests using the mock supplier.

**Claude Code prompt**
> Implement Phase 5 only. Add the transactional outbox with SKIP LOCKED polling, the UNKNOWN inquiry scheduler with exponential backoff, and MANUAL_REVIEW escalation. Write each failure-scenario test using the mock supplier before implementing.

---

### Phase 6 — Webhooks (Week 6)

- [ ] Webhook dispatcher driven by outbox events
- [ ] Payload signed with merchant `webhook_secret` (HMAC) + timestamp header
- [ ] Retry with exponential backoff + jitter; max attempts; then DLQ status
- [ ] `webhook_deliveries` tracking each attempt (status code, duration)
- [ ] Admin endpoint to list/re-drive failed deliveries
- [ ] Delivery is at-least-once; payload includes event id so merchants can dedupe (documented)
- [ ] Tests: merchant endpoint down → retries → success; permanent failure → DLQ
- [ ] ADR-007: at-least-once webhook delivery

**DoD:** webhook behavior verified with a stub merchant server (WireMock or similar).

**Claude Code prompt**
> Implement Phase 6 only: signed webhook dispatcher with retry/backoff/jitter, delivery tracking, DLQ, and a re-drive endpoint. Use WireMock to test failure and recovery.

---

### Phase 7 — Routing & Reconciliation (Week 7)

- [ ] Routing engine: select by priority/cost/success rate; skip inactive suppliers
- [ ] Failover: on definitive failure of supplier A, retry via supplier B only when safe (no ambiguity); never fail over from `UNKNOWN`
- [ ] Simple circuit breaker (Resilience4j) per supplier
- [ ] Settlement file format defined (CSV) and sample generator in mock-supplier
- [ ] Spring Batch job: import settlement file → match against internal transactions → write `recon_results`
- [ ] Detect `MISSING_INTERNAL`, `MISSING_SUPPLIER`, `AMOUNT_DIFF`; idempotent re-import via file checksum
- [ ] Report endpoint (JSON) + CSV export of discrepancies
- [ ] Tests: routing decision table; recon with seeded discrepancies
- [ ] ADR-008: failover rules and why UNKNOWN never fails over

**DoD:** recon job correctly classifies all seeded discrepancy types; routing tests cover failover and circuit-open cases.

**Claude Code prompt**
> Implement Phase 7 only: routing engine with safe failover and circuit breaker, then the Spring Batch reconciliation job with CSV import and discrepancy classification. Never fail over from an UNKNOWN result.

---

### Phase 8 — Observability, Load Test, Docs (Week 8)

- [ ] Micrometer metrics: transactions by status, supplier latency, outbox lag, webhook failures, UNKNOWN count
- [ ] Prometheus + Grafana in Docker Compose; provisioned dashboard JSON committed
- [ ] OpenTelemetry tracing across API → worker → supplier call
- [ ] Structured logs with `transactionId`/`merchantId` in MDC
- [ ] k6 load test scenarios: steady load, burst, supplier-timeout storm
- [ ] Record results (TPS, p95/p99, error rate) in `docs/performance.md`, including test hardware
- [ ] `openapi.yaml` complete; Swagger UI enabled
- [ ] `docs/architecture.md` with diagrams (Mermaid)
- [ ] README (English): problem, architecture, how to run in 3 commands, design decisions, test strategy, results
- [ ] All ADRs reviewed and linked from README

**DoD:** a stranger can clone the repo, run `docker compose up`, hit Swagger UI, and see the Grafana dashboard.

**Claude Code prompt**
> Implement Phase 8 only: metrics, tracing, Grafana dashboard, k6 scripts, OpenAPI polish, and docs. Do not invent performance numbers; leave placeholders in docs/performance.md until I paste real k6 results.

---

### Phase 9 — Optional Extensions (Week 9+)

- [ ] Replace in-DB outbox relay with Kafka (keep outbox pattern as the producer side)
- [ ] Split webhook and recon into separate deployable services
- [ ] Simple admin UI (transactions, UNKNOWN queue, DLQ re-drive)
- [ ] Rate limiting per merchant (Redis token bucket)
- [ ] Deploy to a free/cheap host with a public Swagger UI demo

---

## 7. Testing Strategy (applies to all phases)

| Level | Tooling | What it proves |
|---|---|---|
| Unit | JUnit 5, AssertJ | State machine, routing rules, signature logic |
| Integration | Testcontainers (PostgreSQL, Redis) | Migrations, repositories, ledger invariants |
| Concurrency | `ExecutorService` + latch | No double-spend, idempotency under parallelism |
| Contract/stub | WireMock | Webhooks and supplier behavior |
| Load | k6 | Throughput and latency numbers for the CV |

Rules: no `Thread.sleep` for synchronization (use Awaitility); tests must be deterministic; run concurrency tests repeatedly in CI.

---

## 8. Security Checklist

- [x] API secrets stored hashed/encrypted, never logged
- [x] HMAC comparison is constant-time
- [x] Timestamp window enforced (replay protection)
- [ ] Input validation on all DTOs (Bean Validation)
- [ ] No sensitive data in error messages
- [x] Dependency scan in CI (e.g., OWASP dependency-check or Dependabot)
- [ ] Only generic/fictional data in the repo; no real client names, credentials, or specs

---

## 9. CV Readiness Checklist

- [ ] Public GitHub repo with clean history and small commits
- [ ] README in English with architecture diagram and 3-command quick start
- [ ] 8+ ADRs documenting trade-offs
- [ ] CI badge green
- [ ] Load test numbers measured on real runs (not estimated)
- [ ] Demo link or short screen recording (Swagger UI + Grafana)
- [ ] CV bullets written from measured results, for example:
  - *Designed an idempotent payment switch handling duplicate requests and supplier timeouts using the transactional outbox pattern; zero double-debits across N concurrent test transactions.*
  - *Implemented double-entry ledger with hold/capture/release; verified correctness with 1,000-way concurrency tests.*
  - *Built automated supplier reconciliation (Spring Batch) detecting missing and mismatched transactions.*
  - *Load-tested with k6 at X TPS, p95 Y ms* (fill in only with real numbers)

---

## 10. Progress Log

| Date | Phase | Notes / Decisions |
|---|---|---|
| 2026-10-28 | 1 | Spring Boot 4.1 instead of 3.x. API/webhook secrets are encrypted, not hashed (HMAC needs the clear secret): columns `secret_ciphertext`, `webhook_secret_ciphertext`. Kubernetes deferred to Phase 9. |
| 2026-10-28 | 2 | Idempotency key claimed with INSERT ON CONFLICT in the same DB transaction as the action (no IN_PROGRESS status; failed requests do not consume the key). `response_body` is JSON (byte-identical replays). Unsigned routes outside /v1 are denied by default. 50-parallel test verified to fail against a naive check-then-insert implementation. See ADR-002. |
| 2026-10-28 | 3 | No stored balance at all: balances = SUM(entries), per-merchant FOR NO KEY UPDATE lock on the balance account, system accounts never locked (no hot rows). Added `ledger_holds` (hold lifecycle) and `CASH` account (counter-side of top-ups), not in the original schema. DB triggers enforce balanced single-currency journals and immutability. Ledger tests passed 10/10 runs; removing the lock makes the 1000-hold test fail (601 successes). See ADR-003/004. |
| 2026-10-28 | 4 | POST returns 202 PENDING; supplier processing starts after commit via an async AFTER_COMMIT listener (in-memory, replaced by the outbox in Phase 5; until then a crash after commit leaves the transaction PENDING). Supplier cost frozen on the transaction at routing time. Connection refused on submit = FAILED (nothing sent); read timeout/5xx/pending = UNKNOWN; inquiry 404 = FAILED (supplier contract). mock-supplier is chosen by destination suffix; e2e tests run its real jar via Testcontainers. Local profile seeds a demo merchant + products. See ADR-005. |
| 2026-10-29 | 5 | Outbox claim = one `UPDATE … FROM (SELECT … FOR UPDATE SKIP LOCKED)` statement that also leases the rows (available_at pushed forward), handlers run on virtual threads, retry with capped exponential backoff; at-least-once, so handlers are idempotent. Crash in PROCESSING → resubmit with the same reference (supplier dedupes, 409 + stored outcome); `supplier_sku` now frozen on the transaction for that. Inquiry schedule stored on `transactions` (next_inquiry_at/attempts/deadline), same claiming technique; MANUAL_REVIEW keeps the hold. Late answers (CAS lost against a contradicting final status, or the post-failure verification inquiry finding SUCCESS) go to `transaction_discrepancies`, never auto-applied. Added mock behaviours 77 (stuck pending) and 88 (slow success, 404 while processing). Processing now starts within one poll (250ms) instead of immediately. See ADR-006 and the ADR-005 addendum. |
