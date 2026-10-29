# Performance

> **No numbers yet.** The results below are placeholders until real k6 runs are recorded. Nothing
> on this page is estimated or invented. Fill in each table from the k6 summary
> (`loadtest/results/<scenario>.json`) and the Grafana dashboard.

## Test setup

| | |
|---|---|
| Machine | _TODO: model, CPU (cores), RAM_ |
| OS / Docker | _TODO: OS version, Docker Desktop version, CPUs and memory given to Docker_ |
| Stack | `docker compose up` from commit _TODO_, all services on the same machine |
| App | 1 instance, Java 21, virtual threads, HikariCP default pool (10) |
| Supplier | mock-supplier: immediate answers; timeout behaviour answers after 15s, the switch's read timeout is 5s |
| Load generator | k6 1.8.1 in Docker, on the same machine and network (`./scripts/k6.sh`) |

Load and system under test share one machine, so the numbers describe this setup, not a
production capacity.

## What is measured

- **Throughput (TPS)**: accepted transactions per second (`POST /v1/transactions` → 202).
- **Latency**: p95/p99 of `POST /v1/transactions` (time to accept). Supplier calls happen
  afterwards and show separately as supplier latency.
- **Error rate**: non-2xx responses (`http_req_failed`).
- **End-to-end**: during and after the run, check on the dashboard that open PENDING/PROCESSING
  transactions return to zero, how long UNKNOWN takes to drain, and the outbox lag.

## Scenarios

### Steady load

```bash
RATE=50 DURATION=2m ./scripts/k6.sh steady
```

| Rate (req/s) | Achieved TPS | p95 | p99 | Error rate | Max outbox lag | Notes |
|---|---|---|---|---|---|---|
| _TODO_ | | | | | | |

### Burst

```bash
PEAK=300 ./scripts/k6.sh burst
```

| Peak (req/s) | Achieved TPS at peak | p95 | p99 | Error rate | Max outbox lag | Recovery time | Notes |
|---|---|---|---|---|---|---|---|
| _TODO_ | | | | | | | |

### Supplier timeout storm

```bash
RATE=30 STORM_SHARE=0.6 ./scripts/k6.sh timeout-storm
```

| Rate | Timeout share | p95 (accept) | Error rate | Peak UNKNOWN | Time until UNKNOWN drained | Circuit opened? | Notes |
|---|---|---|---|---|---|---|---|
| _TODO_ | | | | | | | |

Expected behaviour, to confirm or refute with the run:

- acceptance stays fast, because supplier slowness never blocks `POST /v1/transactions`;
- UNKNOWN grows and then drains through inquiries without any refund on a guess;
- the circuit breaker opens for MOCKA, and routing moves new work to MOCKB;
- no failover happens from UNKNOWN (`paymentswitch_failovers_total` shows only definitive codes).

## Observations

_TODO: bottlenecks found (e.g. connection pool, ledger lock contention on the single demo merchant,
outbox batch size), what was changed and the effect._
