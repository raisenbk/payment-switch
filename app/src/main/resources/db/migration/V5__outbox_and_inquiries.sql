-- Transactional outbox, UNKNOWN inquiry scheduling and supplier discrepancies (Phase 5, ADR-006).

-- Events written in the same database transaction as the state change they announce.
-- A poller claims due rows with FOR UPDATE SKIP LOCKED and leases them by pushing available_at
-- forward, so several app instances never handle the same event at the same time.
CREATE TABLE outbox_events (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type    VARCHAR(60)  NOT NULL,
    aggregate_id  UUID         NOT NULL,
    payload       JSONB        NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    available_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    processed_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_due ON outbox_events (available_at, id) WHERE processed_at IS NULL;

-- Frozen at routing time (like supplier_cost) so a recovered submit or an inquiry can be rebuilt
-- from the transaction alone.
ALTER TABLE transactions ADD COLUMN supplier_sku VARCHAR(60);

-- Inquiry schedule. Set while the transaction is UNKNOWN, and for one verification inquiry after
-- an UNKNOWN transaction was failed (to catch a late supplier success).
ALTER TABLE transactions ADD COLUMN next_inquiry_at  TIMESTAMPTZ;
ALTER TABLE transactions ADD COLUMN inquiry_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE transactions ADD COLUMN inquiry_deadline TIMESTAMPTZ;

CREATE INDEX idx_transactions_next_inquiry ON transactions (next_inquiry_at) WHERE next_inquiry_at IS NOT NULL;

-- A definitive supplier answer that contradicts our final status. Never applied automatically:
-- money already moved, so an operator (or reconciliation) decides.
CREATE TABLE transaction_discrepancies (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id   UUID         NOT NULL REFERENCES transactions (id),
    kind             VARCHAR(30)  NOT NULL CHECK (kind IN ('LATE_SUCCESS', 'LATE_FAILURE')),
    our_status       VARCHAR(20)  NOT NULL,
    supplier_ref     VARCHAR(64),
    supplier_code    VARCHAR(40),
    detected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    UNIQUE (transaction_id, kind)
);

CREATE INDEX idx_transaction_discrepancies_open ON transaction_discrepancies (detected_at) WHERE resolved_at IS NULL;

-- Transactions accepted before the outbox existed had an in-memory hand-off only.
INSERT INTO outbox_events (event_type, aggregate_id)
SELECT 'transaction.accepted', id FROM transactions WHERE status IN ('PENDING', 'PROCESSING');
