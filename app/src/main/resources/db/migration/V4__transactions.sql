-- Merchant transactions and their append-only status history.

CREATE TABLE transactions (
    id             UUID        PRIMARY KEY,
    merchant_id    UUID        NOT NULL REFERENCES merchants (id),
    merchant_ref   VARCHAR(64) NOT NULL,
    product_id     BIGINT      NOT NULL REFERENCES products (id),
    destination    VARCHAR(20) NOT NULL,
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    currency       CHAR(3)     NOT NULL,
    status         VARCHAR(20) NOT NULL
                   CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'UNKNOWN', 'MANUAL_REVIEW')),
    -- Chosen at routing time; the cost is frozen so a later capture books what was agreed.
    supplier_id    BIGINT      REFERENCES suppliers (id),
    supplier_cost  BIGINT      CHECK (supplier_cost >= 0),
    supplier_ref   VARCHAR(64),
    failure_code   VARCHAR(40),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INT         NOT NULL DEFAULT 0,
    UNIQUE (merchant_id, merchant_ref)
);

CREATE INDEX idx_transactions_merchant_created ON transactions (merchant_id, created_at);
CREATE INDEX idx_transactions_status ON transactions (status);

CREATE TABLE transaction_events (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  UUID        NOT NULL REFERENCES transactions (id),
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    payload         JSONB       NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transaction_events_transaction ON transaction_events (transaction_id, id);

CREATE FUNCTION reject_audit_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only (% rejected)', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_transaction_events_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON transaction_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_modification();
