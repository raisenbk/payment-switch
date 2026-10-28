-- Test-only table: one row per executed side effect of IdempotencyProbeController.
CREATE TABLE IF NOT EXISTS test_probe_effects (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id UUID   NOT NULL,
    amount      BIGINT NOT NULL
);
