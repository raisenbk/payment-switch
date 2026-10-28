-- Baseline schema: merchants, their API credentials, and the product/supplier catalog.
-- Money is stored as BIGINT minor units with an explicit ISO-4217 currency column.

CREATE TABLE merchants (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(120) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    callback_url    VARCHAR(2048),
    -- Needed in clear form to sign outgoing webhooks, so it is encrypted (not hashed) by the app.
    webhook_secret_ciphertext TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_credentials (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id       UUID         NOT NULL REFERENCES merchants (id),
    api_key           VARCHAR(64)  NOT NULL UNIQUE,
    -- HMAC verification needs the shared secret itself, so it is encrypted (not hashed) by the app.
    secret_ciphertext TEXT         NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_credentials_merchant ON api_credentials (merchant_id);

CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    category    VARCHAR(20)  NOT NULL
                CHECK (category IN ('PREPAID', 'DATA', 'VOUCHER')),
    base_price  BIGINT       NOT NULL CHECK (base_price > 0),
    currency    CHAR(3)      NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE suppliers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE supplier_products (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id   BIGINT        NOT NULL REFERENCES suppliers (id),
    product_id    BIGINT        NOT NULL REFERENCES products (id),
    supplier_sku  VARCHAR(60)   NOT NULL,
    cost_price    BIGINT        NOT NULL CHECK (cost_price > 0),
    currency      CHAR(3)       NOT NULL,
    -- Observed success ratio in [0, 1], used by routing (Phase 7).
    success_rate  NUMERIC(5, 4) NOT NULL DEFAULT 1.0 CHECK (success_rate BETWEEN 0 AND 1),
    priority      INT           NOT NULL DEFAULT 100,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    UNIQUE (supplier_id, product_id),
    UNIQUE (supplier_id, supplier_sku)
);

CREATE INDEX idx_supplier_products_product ON supplier_products (product_id);
