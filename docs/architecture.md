# Architecture

A **modular monolith** (ADR-001): one Spring Boot application, one PostgreSQL database, with modules
that talk to each other only through public services and outbox events. A separate small app,
`mock-supplier`, plays the role of the external suppliers.

## Context

```mermaid
flowchart LR
    M[Merchant backend] -- "HTTPS + HMAC signature<br/>POST /v1/transactions" --> PS
    PS -- "signed webhook<br/>transaction.succeeded / failed" --> M
    OP[Operator] -- "X-Admin-Token<br/>/admin/**" --> PS
    PS[Payment Switch] -- "submit / inquiry<br/>(reference = transaction id)" --> S1[Supplier A]
    PS -- failover --> S2[Supplier B]
    S1 -. "daily settlement CSV" .-> OP
    PS --- DB[(PostgreSQL)]
    PS -- "OTLP traces" --> J[Jaeger]
    P[Prometheus] -- "scrape /actuator/prometheus" --> PS
    G[Grafana] --> P
```

## Modules

```mermaid
flowchart TB
    subgraph app [payment-switch app]
        merchant["merchant<br/>HMAC auth, admin token,<br/>webhook targets, top-ups"]
        transaction["transaction<br/>state machine, processor,<br/>inquiries, outcomes"]
        ledger["ledger<br/>double-entry, holds"]
        catalog["catalog<br/>products"]
        routing["routing<br/>RoutingPolicy, failover rules"]
        supplier["supplier<br/>HTTP gateways, circuit breakers,<br/>metrics"]
        webhook["webhook<br/>dispatcher, retry, DLQ"]
        recon["recon<br/>Spring Batch settlement job"]
        common["common<br/>outbox, idempotency, errors,<br/>money, tracing"]
    end
    transaction --> ledger
    transaction --> catalog
    transaction --> routing
    transaction --> supplier
    routing --> supplier
    transaction -- "transaction.accepted / completed<br/>(outbox)" --> common
    common -- "outbox handler" --> webhook
    webhook --> merchant
    recon -. "reads transactions" .-> transaction
```

Module rules: no module reads another module's repository. Money moves only through
`LedgerService`. Status changes only through `TransactionStateMachine`. Work that must happen after
a commit goes through the outbox.

## Transaction flow

```mermaid
sequenceDiagram
    autonumber
    participant M as Merchant
    participant API as API (HMAC + idempotency)
    participant DB as PostgreSQL
    participant W as Outbox poller
    participant S as Supplier
    participant WH as Webhook dispatcher

    M->>API: POST /v1/transactions (Idempotency-Key)
    API->>DB: one DB transaction: PENDING row + event, ledger hold, outbox event
    API-->>M: 202 PENDING
    W->>DB: claim event (FOR UPDATE SKIP LOCKED, lease)
    W->>DB: route, PENDING → PROCESSING (commit before calling out)
    W->>S: submit (reference = transaction id)
    alt definitive success
        S-->>W: SUCCESS
        W->>DB: PROCESSING → SUCCESS, capture hold, outbox transaction.completed
    else definitive failure, another supplier eligible
        S-->>W: FAILED (e.g. OUT_OF_STOCK)
        W->>DB: failover: PROCESSING → PROCESSING at supplier B
        W->>S: submit to supplier B
    else ambiguous (timeout, 5xx, pending)
        S--xW: no definitive answer
        W->>DB: PROCESSING → UNKNOWN, hold kept, schedule inquiry
        Note over W,S: inquiries with backoff until SUCCESS / FAILED,<br/>MANUAL_REVIEW after the max window
    end
    WH->>DB: claim webhook (SKIP LOCKED)
    WH->>M: signed POST (retry with backoff + jitter, DLQ after 10)
```

## State machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: accepted (hold placed)
    PENDING --> PROCESSING: routed
    PROCESSING --> PROCESSING: failover (definitive failure only)
    PROCESSING --> SUCCESS: supplier SUCCESS (hold captured)
    PROCESSING --> FAILED: definitive failure (hold released)
    PROCESSING --> UNKNOWN: timeout / 5xx / pending (hold kept)
    UNKNOWN --> SUCCESS: inquiry SUCCESS
    UNKNOWN --> FAILED: inquiry FAILED
    UNKNOWN --> MANUAL_REVIEW: max window passed
    MANUAL_REVIEW --> SUCCESS: operator
    MANUAL_REVIEW --> FAILED: operator
    SUCCESS --> [*]
    FAILED --> [*]
```

Every transition is a compare-and-set on the current status plus an append-only
`transaction_events` row, in the same database transaction as its ledger effect.

## Ledger postings

| Operation | Debit | Credit |
|---|---|---|
| Top-up (deposit received) | CASH | MERCHANT_BALANCE |
| Hold (transaction accepted) | MERCHANT_BALANCE | MERCHANT_HOLD |
| Release (FAILED) | MERCHANT_HOLD | MERCHANT_BALANCE |
| Capture (SUCCESS) | MERCHANT_HOLD (price) | SUPPLIER_PAYABLE (cost) + REVENUE (margin) |

Balances are always `SUM(entries)`; there is no balance column (ADR-003). Journals must balance per
currency, and journals and entries cannot be changed once posted. Both rules are enforced by
database triggers.

## Reconciliation

```mermaid
flowchart LR
    F[Settlement CSV] -->|POST /admin/recon/settlements| I{checksum known?}
    I -- yes --> R0[return earlier report]
    I -- no --> S1[importLines<br/>parse + validate]
    S1 --> S2[matchLines<br/>join transactions,<br/>ReconClassifier]
    S2 --> S3[findMissingSupplier<br/>our successes not in file]
    S3 --> R[recon_results<br/>JSON report + CSV export]
```

## Reliability mechanisms

| Concern | Mechanism | Where |
|---|---|---|
| Duplicate API requests | Idempotency key claimed in the same DB transaction as the action | ADR-002 |
| Concurrent spending | Per-merchant row lock on the balance account, READ COMMITTED | ADR-004 |
| Crash after commit | Transactional outbox, leased claims, resubmit with the same reference | ADR-006 |
| Ambiguous supplier result | `UNKNOWN`, hold kept, inquiries, `MANUAL_REVIEW` | ADR-005 |
| Double top-up via failover | Failover only after definitive failures | ADR-008 |
| Supplier outage | Circuit breaker per supplier, routing skips open circuits | ADR-008 |
| Lost notifications | At-least-once webhooks, stable event id, DLQ + re-drive | ADR-007 |
| Late or contradicting answers | `transaction_discrepancies`, never auto-applied | ADR-005 addendum |
| Supplier billing errors | Settlement reconciliation | Phase 7 |
| Following one transaction | Trace context stored with outbox and webhook events | ADR-009 |

## Observability

- **Metrics** (`/actuator/prometheus`, dashboard *Payment Switch* in Grafana):
  - `paymentswitch_transactions_total{status}` and `paymentswitch_transactions_open{status}`
  - `paymentswitch_supplier_calls_seconds{supplier,operation,outcome}` (histogram)
  - `resilience4j_circuitbreaker_state`
  - `paymentswitch_outbox_pending` and `paymentswitch_outbox_lag_seconds`
  - `paymentswitch_webhooks_deliveries_total{outcome}` and `paymentswitch_webhooks_dead`
  - `paymentswitch_inquiries_total`, `paymentswitch_failovers_total`, `paymentswitch_discrepancies_total`
  - Spring's `http_server_requests_seconds`
- **Traces**: OpenTelemetry via Micrometer Tracing, exported over OTLP to Jaeger. A single trace
  follows a transaction across the outbox to the supplier and the webhook (ADR-009).
- **Logs**: structured JSON (ECS) in Docker, with `traceId`, `spanId`, `requestId`, `merchantId`
  and `transactionId` from the MDC. Secrets, API keys and signatures are never logged.
