# Performance

> Only measured numbers are on this page. Scenarios still marked _TODO_ have not been run yet.
> Sources are the k6 summary (`loadtest/results/<scenario>.json`) and the app's own metrics in
> Prometheus, queried over the run window.

## Test setup

| | |
|---|---|
| Machine | Apple M4 (10 cores), 24 GB RAM |
| OS / Docker | macOS 26.5.2, Docker 29.6.1 with 10 CPUs and 7.75 GiB memory |
| Stack | `docker compose up` from commit `f3e1839`, all services on the same machine |
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
| 50 | 50.0 accepted/s (6,000 in 2m) | 42.3 ms | 167.9 ms | 0.00% (0 of 6,000) | not representative ³ | median 5.7 ms, max 1.1 s, 2 dropped iterations |

Run on 2026-10-29, `./scripts/k6.sh steady`, 03:21–03:23 UTC.

³ This run started 30 s after the burst run, while about 8,000 burst events were still in the
outbox. The lag of up to 61 s seen during the run came from that backlog, not from the steady
load, and the API latency was measured under that extra load (a conservative number). A clean
steady run, started with an empty outbox, is still to be recorded.

### Burst

```bash
PEAK=300 ./scripts/k6.sh burst
```

| Peak (req/s) | Achieved TPS at peak | p95 | p99 | Error rate | Max outbox lag | Recovery time | Notes |
|---|---|---|---|---|---|---|---|
| 300 | 300 accepted/s (k6); ~120 completed/s (server) | 83.8 ms | ~167 ms ¹ | 0.00% (0 of 12,686) | 61 s (backlog 9,830 events) | ~2.5 min after the burst ended ² | median 3.4 ms, max 428 ms, 15 dropped iterations |

Run on 2026-10-29, `PEAK=300 ./scripts/k6.sh burst` (1m50s, 12,686 requests).

¹ k6's summary did not include p99 for this run. This value is the server-side p99 of
`http_server_requests_seconds{uri="/v1/transactions",method="POST"}` over a 15-minute window that
covers the run. The scripts now report p99 directly.
² From the end of the run (03:20:31 UTC) until the outbox was back to 25 events and 0.2 s lag
(03:22:58 UTC), sampled every 15 s. A steady run of 50 req/s was running during that time, so
recovery without extra load would be faster. The lag peaked at 61 s at 03:21:13, after the burst
had ended, as the oldest queued events aged.

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

### Burst (2026-10-29): acceptance holds, processing is capped by the connection pool

- **Acceptance stays fast at 300 req/s.** p95 was 84 ms and no request failed. This is the point of
  `202 PENDING`: the merchant waits for one database transaction (row + hold + outbox event), never
  for a supplier.
- **Processing could not keep up.** Transactions completed at about 120/s at peak, so the outbox
  backlog grew to 9,830 events and the oldest waited up to 61 s. No request failed: work queued in
  the outbox and drained about 2.5 minutes after the burst, while 50 req/s of new work kept coming
  in. "Accepted" and "completed" are decoupled, as intended, but completion lagged by up to a
  minute.
- **Measured cause:** the HikariCP pool (default 10 connections) was saturated. All 10 were
  active, up to 80 threads waited for a connection, and the slowest acquire took 0.48 s. The API
  and the outbox poller share the pool. A poller batch starts 50 virtual threads, and each event
  needs 2–3 short transactions, so they queue for connections.
- **Not measured yet:** all load came from one demo merchant, so every hold and capture contends for
  the same per-merchant ledger lock (ADR-004). That lock may become the next limit after the pool.
- **Next experiments** (one change at a time, re-run burst):
  1. larger pool;
  2. limit poller concurrency to the pool size, so it does not starve the API;
  3. spread load over several merchants to separate pool limits from lock limits.
