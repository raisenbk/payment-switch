-- Double-entry ledger. Entries are the only source of truth for balances; nothing stores a
-- mutable balance. Amounts are positive BIGINT minor units; the direction says which side.

CREATE TABLE ledger_accounts (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type  VARCHAR(20) NOT NULL CHECK (owner_type IN ('MERCHANT', 'SUPPLIER', 'SYSTEM')),
    -- Merchant UUID, supplier id or 'switch' for system accounts.
    owner_id    VARCHAR(64) NOT NULL,
    type        VARCHAR(30) NOT NULL
                CHECK (type IN ('MERCHANT_BALANCE', 'MERCHANT_HOLD', 'REVENUE', 'SUPPLIER_PAYABLE', 'CASH')),
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, type, currency)
);

CREATE TABLE ledger_journals (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                 VARCHAR(20)  NOT NULL
                         CHECK (kind IN ('TOP_UP', 'HOLD', 'RELEASE', 'CAPTURE', 'REVERSAL')),
    -- Business idempotency key, e.g. 'hold:<transactionId>'. A journal is never booked twice.
    reference            VARCHAR(200) NOT NULL UNIQUE,
    transaction_id       UUID,
    reverses_journal_id  UUID         REFERENCES ledger_journals (id),
    description          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_journals_transaction ON ledger_journals (transaction_id);

CREATE TABLE ledger_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id  UUID       NOT NULL REFERENCES ledger_journals (id),
    account_id  UUID       NOT NULL REFERENCES ledger_accounts (id),
    direction   VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount      BIGINT     NOT NULL CHECK (amount > 0)
);

-- Balance queries scan one account's entries; INCLUDE allows index-only scans.
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id) INCLUDE (direction, amount);
CREATE INDEX idx_ledger_entries_journal ON ledger_entries (journal_id);

-- One row per reserved amount; closed exactly once by RELEASE or CAPTURE.
CREATE TABLE ledger_holds (
    transaction_id  UUID        PRIMARY KEY,
    merchant_id     UUID        NOT NULL REFERENCES merchants (id),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    currency        CHAR(3)     NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED', 'CAPTURED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);

CREATE INDEX idx_ledger_holds_merchant_active ON ledger_holds (merchant_id) WHERE status = 'ACTIVE';

-- Guard 1: every journal balances (debits = credits) and uses a single currency. Checked at
-- commit (deferred) so all entries of the journal are visible.
CREATE FUNCTION ledger_assert_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    imbalance  BIGINT;
    currencies INT;
BEGIN
    SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END), 0),
           COUNT(DISTINCT a.currency)
      INTO imbalance, currencies
      FROM ledger_entries e
      JOIN ledger_accounts a ON a.id = e.account_id
     WHERE e.journal_id = NEW.journal_id;

    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'ledger journal % is unbalanced by %', NEW.journal_id, imbalance;
    END IF;
    IF currencies > 1 THEN
        RAISE EXCEPTION 'ledger journal % mixes currencies', NEW.journal_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_journal_balanced();

-- Guard 2: posted journals and entries are immutable. Corrections are new, reversing journals.
CREATE FUNCTION ledger_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger table % is immutable (% rejected); post a reversing journal instead',
        TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_ledger_journals_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_journals
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
