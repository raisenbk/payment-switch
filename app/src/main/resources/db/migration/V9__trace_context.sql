-- W3C trace context (traceparent/tracestate) captured when an event is written, so the work done
-- later by a poller continues the trace of the request that caused it (ADR-009).
ALTER TABLE outbox_events ADD COLUMN trace_headers JSON;
ALTER TABLE webhook_events ADD COLUMN trace_headers JSON;
