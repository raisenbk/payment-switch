# ADR-009: Trace context travels with outbox events

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

One transaction touches several threads and moments. It starts as the HTTP request that accepts
it. Later an outbox poller calls the supplier, a scheduler may run inquiries, and a dispatcher
sends the webhook, possibly hours later after retries. With default instrumentation every one of
those is a separate trace, and the poll loops themselves produce a trace every few hundred
milliseconds. When a merchant reports "my top-up 6f1c… is stuck", nobody can follow it end to end.

## Decision

- **Capture** the W3C trace context (`traceparent`, `tracestate`) when an event is written:
  `OutboxPublisher` stores it in `outbox_events.trace_headers`, and the webhook handler stores it
  in `webhook_events.trace_headers`. It is written in the same database transaction as the event,
  so it costs no extra round trip.
- **Continue** the trace when the event is handled: `TraceContexts.continueTrace` starts a span
  whose parent is the stored context (`outbox transaction.accepted`, `webhook deliver`). The
  supplier call (RestClient with the observation registry) and the webhook request carry
  `traceparent` onwards to the supplier and to the merchant.
- **Do not trace idle work.** An `ObservationPredicate` drops the observations of scheduler ticks
  and of `/actuator/**`. A poller creates a span only when it actually handles something.
- Inquiries start their own trace per attempt, tagged with `transaction.id`. They run long after
  the request (seconds to hours), and a separate trace keeps the request trace readable.
- The trace id is returned to callers (`X-Trace-Id`, and `traceId` in error bodies) and appears in
  every log line next to `merchantId` and `transactionId`. `X-Request-Id` remains the caller's own
  correlation id.

## Consequences

- In Jaeger one trace shows: `POST /v1/transactions` → `outbox transaction.accepted` → supplier
  HTTP call → `outbox transaction.completed` → `webhook deliver`. Retries appear as more spans in
  the same trace.
- A trace can span hours (UNKNOWN → inquiry → webhook retries). Tracing backends cope with that,
  but the trace grows as long as the work does.
- Sampling is 100% in the demo. Under real load it should be lowered or moved to tail sampling.
  Because the decision is in `traceparent`, stored events keep the original decision.
