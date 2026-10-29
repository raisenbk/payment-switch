-- Merchant webhooks (Phase 6, ADR-007): one row per notification, one row per delivery attempt.

CREATE TABLE webhook_events (
    -- Sent to the merchant as the event id: the same on every retry, so receivers can deduplicate.
    id               UUID         PRIMARY KEY,
    -- The outbox event this notification came from; makes creating it idempotent.
    outbox_event_id  BIGINT       NOT NULL UNIQUE REFERENCES outbox_events (id),
    merchant_id      UUID         NOT NULL REFERENCES merchants (id),
    event_type       VARCHAR(60)  NOT NULL,
    -- The exact JSON body that is signed and sent.
    payload          JSON         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX idx_webhook_events_due ON webhook_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_webhook_events_status ON webhook_events (status, created_at);

CREATE TABLE webhook_deliveries (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    webhook_event_id  UUID          NOT NULL REFERENCES webhook_events (id),
    attempt           INT           NOT NULL,
    url               VARCHAR(2048),
    -- NULL when no HTTP response arrived (connection refused, timeout, no URL configured).
    status_code       INT,
    duration_ms       BIGINT        NOT NULL,
    error             VARCHAR(500),
    attempted_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_deliveries_event ON webhook_deliveries (webhook_event_id, id);

CREATE TRIGGER trg_webhook_deliveries_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON webhook_deliveries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_modification();
