# ADR-001: Modular monolith over microservices

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The payment switch has several distinct concerns: merchant authentication, catalog, transaction
lifecycle, a double-entry ledger, supplier routing, webhooks and reconciliation. The hardest
requirement is consistency: creating a transaction, placing a ledger hold and recording an outbox
event must succeed or fail together.

The project is built and operated by one person, runs on a laptop via Docker Compose, and its
purpose is to demonstrate correctness under concurrency and failure, not organisational scaling.

## Decision

Build a single deployable Spring Boot application, split into modules by package
(`merchant`, `catalog`, `transaction`, `ledger`, `routing`, `supplier`, `webhook`, `recon`,
`common`).

Module rules:

- A module exposes a small public API (service interfaces, DTOs, events). Other modules use only
  that API, never another module's repositories or entities.
- Each module owns its tables. Cross-module reads go through the owning module's API.
- Asynchronous hand-offs use the transactional outbox, so the boundaries already behave like
  message-based ones.

The supplier simulator (`mock-supplier`) is a separate application because it stands in for an
external system.

## Consequences

**Positive**

- Local ACID transactions cover "create transaction + hold + outbox" without distributed
  transactions or sagas.
- One build, one deploy, one database: simple to run with `docker compose up` and to test with
  Testcontainers.
- Clear module boundaries keep the option to extract a module (e.g. webhooks or recon,
  see Phase 9) later.

**Negative**

- Boundaries are enforced by convention and review, not by the network. An architecture test
  (e.g. ArchUnit or Spring Modulith) can be added if violations appear.
- All modules scale together. Acceptable at this scale; the outbox poller is designed to run
  safely with multiple instances (`FOR UPDATE SKIP LOCKED`).
