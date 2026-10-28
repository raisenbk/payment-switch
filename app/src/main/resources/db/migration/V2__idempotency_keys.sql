-- One row per (merchant, Idempotency-Key). The row is inserted and completed inside the same
-- database transaction as the request's business writes, so a committed row always has a response
-- and a rolled-back request leaves no row behind (the key can be retried).

CREATE TABLE idempotency_keys (
    merchant_id      UUID         NOT NULL REFERENCES merchants (id),
    idempotency_key  VARCHAR(100) NOT NULL,
    -- SHA-256 (hex) of method, path and raw body; a different hash under the same key is a conflict.
    request_hash     CHAR(64)     NOT NULL,
    -- NULL only while the creating transaction is still running (never visible to other sessions).
    response_status  SMALLINT,
    -- JSON (not JSONB) to replay the original body byte for byte.
    response_body    JSON,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idempotency_key)
);

-- Supports purging expired keys.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
