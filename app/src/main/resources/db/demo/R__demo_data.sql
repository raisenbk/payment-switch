-- Demo data for the `local` profile only (never loaded in tests or other environments).
-- Repeatable and idempotent: safe to re-run.
--
-- Demo merchant credentials (fictional, local only; the secret is encrypted with the local dev key
-- from docker-compose.yml):
--   X-Api-Key: pk_demo_local
--   secret:    sk_demo_local_only_not_secret

INSERT INTO suppliers (code, name) VALUES
    ('MOCKA', 'Mock Supplier A')
ON CONFLICT (code) DO NOTHING;

INSERT INTO products (code, name, category, base_price, currency) VALUES
    ('PULSA10', 'Prepaid credit 10,000', 'PREPAID', 10500, 'IDR'),
    ('PULSA25', 'Prepaid credit 25,000', 'PREPAID', 25500, 'IDR'),
    ('DATA5GB', 'Data package 5 GB',     'DATA',    52000, 'IDR')
ON CONFLICT (code) DO NOTHING;

INSERT INTO supplier_products (supplier_id, product_id, supplier_sku, cost_price, currency, priority)
SELECT s.id, p.id, 'A-' || p.code, p.base_price - 500, 'IDR', 1
  FROM suppliers s CROSS JOIN products p
 WHERE s.code = 'MOCKA' AND p.code IN ('PULSA10', 'PULSA25', 'DATA5GB')
ON CONFLICT (supplier_id, product_id) DO NOTHING;

INSERT INTO merchants (id, name) VALUES
    ('00000000-0000-0000-0000-00000000d3e0', 'Demo Merchant')
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_credentials (merchant_id, api_key, secret_ciphertext) VALUES
    ('00000000-0000-0000-0000-00000000d3e0', 'pk_demo_local',
     'v1:KPP2cbMGRg7gNdWQjHrIJY1WIYEAF4JGZXNbb3307+6U3FjxEUDWePr915VyDI/1J4urWZhy1mG2')
ON CONFLICT (api_key) DO NOTHING;

-- Opening balance of 1,000,000 IDR, booked as a normal top-up journal.
INSERT INTO ledger_accounts (owner_type, owner_id, type, currency) VALUES
    ('SYSTEM', 'switch', 'CASH', 'IDR'),
    ('MERCHANT', '00000000-0000-0000-0000-00000000d3e0', 'MERCHANT_BALANCE', 'IDR')
ON CONFLICT (owner_type, owner_id, type, currency) DO NOTHING;

DO $$
DECLARE
    journal UUID;
BEGIN
    INSERT INTO ledger_journals (kind, reference, description)
    VALUES ('TOP_UP', 'topup:00000000-0000-0000-0000-00000000d3e0:demo-opening-balance', 'Demo opening balance')
    ON CONFLICT (reference) DO NOTHING
    RETURNING id INTO journal;

    IF journal IS NOT NULL THEN
        INSERT INTO ledger_entries (journal_id, account_id, direction, amount)
        SELECT journal, id, 'DEBIT', 1000000 FROM ledger_accounts
         WHERE owner_type = 'SYSTEM' AND type = 'CASH' AND currency = 'IDR';
        INSERT INTO ledger_entries (journal_id, account_id, direction, amount)
        SELECT journal, id, 'CREDIT', 1000000 FROM ledger_accounts
         WHERE owner_id = '00000000-0000-0000-0000-00000000d3e0' AND type = 'MERCHANT_BALANCE';
    END IF;
END;
$$;
