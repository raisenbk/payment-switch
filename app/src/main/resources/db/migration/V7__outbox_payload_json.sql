-- Store outbox payloads as json (exact text) instead of jsonb (normalised: keys reordered,
-- whitespace changed). Payloads are only ever stored and read back whole, and webhook bodies built
-- from them should keep the field order of the API representation.
ALTER TABLE outbox_events ALTER COLUMN payload DROP DEFAULT;
ALTER TABLE outbox_events ALTER COLUMN payload TYPE json USING payload::json;
ALTER TABLE outbox_events ALTER COLUMN payload SET DEFAULT '{}'::json;
