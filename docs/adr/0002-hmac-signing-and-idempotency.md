# ADR-002: HMAC request signing and idempotency

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Merchants call the API server-to-server to move money. We need to know who is calling, that the
request was not altered in transit or replayed later, and that a retried request (after a network
timeout, for example) never executes twice.

## Decision 1: HMAC-SHA256 request signing

Each merchant has an API key (public identifier) and a shared secret. The client signs:

```
METHOD \n PATH_WITH_QUERY \n UNIX_TIMESTAMP_SECONDS \n hex(SHA-256(raw body))
```

and sends `X-Api-Key`, `X-Timestamp` and `X-Signature` (lowercase hex HMAC-SHA256).

- **Integrity:** method, raw path, query string and body are all covered. Changing any of them
  breaks the signature.
- **Replay window:** `X-Timestamp` must be within ±5 minutes of server time (configurable).
- **Constant-time comparison** (`MessageDigest.isEqual`) avoids leaking the signature byte by byte
  through response timing.
- **Uniform failure:** every authentication failure returns the same 401 `AUTHENTICATION_FAILED`,
  so callers cannot tell an unknown key from a bad signature or an expired timestamp. The reason is
  logged at debug level. Keys, secrets and signatures are never logged.
- **Secrets are encrypted at rest, not hashed** (AES-256-GCM, key from the
  `SECRET_ENCRYPTION_KEY` environment variable). Verifying an HMAC requires the clear secret, so a
  one-way hash cannot work. The stored value is versioned (`v1:`) to allow key rotation later.
- **Body size limit** (64 KB) because the body is buffered for hashing before it is parsed.
- **Deny by default:** `/v1/**` requires a valid signature. Outside `/v1`, only health and info are
  public, and every other route returns 401 until it gets its own security chain.

*Alternatives considered:* OAuth2 client credentials / JWT (needs a token service and does not sign
the body); mTLS (strong, but heavy to operate for a demo and not portable through every proxy).
HMAC signing is what many payment and biller APIs actually use.

*Residual risk:* within the 5-minute window, a captured request can be re-sent. For `POST`
requests this is harmless because of idempotency (below). `GET` requests are read-only. A nonce
store (e.g. Redis) can close the gap later if needed.

## Decision 2: idempotency claimed in the business transaction

Mutating endpoints require an `Idempotency-Key` header (1–100 chars, `[A-Za-z0-9_-]`), scoped per
merchant. The request fingerprint is SHA-256 over method, path with query, and raw body.

Inside **one database transaction**:

1. `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` claims the key.
2. If the insert succeeded, run the business action and store its response in the same row.
3. If the key already existed, compare fingerprints:
   - different fingerprint → `409 IDEMPOTENCY_KEY_REUSED`;
   - same fingerprint → replay the stored status and body, with the header
     `Idempotent-Replayed: true`.

Why this works under concurrency: PostgreSQL makes a second `INSERT` with the same primary key
**wait** until the first transaction finishes. If the first one commits, the waiter sees the
conflict and replays the stored response. If it rolls back, the waiter claims the key and runs
the action itself. The `IdempotencyConcurrencyTest` sends 50 identical requests in parallel and
asserts that exactly one side effect happens and all 50 get the same response. As a check on the
test itself, a naive "check, then act, then insert" version fails it.

Properties:

- **No stuck "in progress" state.** The claim, the effect and the stored response commit together.
  A crash mid-request rolls all three back.
- **Failed requests do not consume the key.** If the action throws (e.g. a 4xx business error),
  everything rolls back and the client may retry with the same key. Only committed outcomes are
  replayed.
- **Byte-identical replays.** The response body is stored as `json` (not `jsonb`, which would
  re-order keys and whitespace).

*Alternatives considered:*

- A separate `IN_PROGRESS` row committed before the action, with concurrent requests getting
  `409 in progress`. This needs crash recovery for rows left in progress, and the effect and the
  stored response can diverge if the process dies between the two commits.
- A Redis lock. This adds a second system whose failure modes must agree with the database, and
  it still needs the database row to replay responses.

## Consequences

- The idempotent action must be **short, local database work**. Slow or external calls (the
  supplier) happen after commit via the outbox (Phase 5). Otherwise waiting duplicates would hold
  connections for the length of that call.
- Concurrent duplicates each hold a pooled connection while they wait. That is fine for retries
  and bursts, and it is bounded by the connection pool timeout.
- Keys are kept indefinitely for now (`created_at` is indexed). A retention job (e.g. 24 h) will
  be added with the scheduler work.
- Plan deviations: the column is `idempotency_key` (not `key`), there is no `status` column
  (not needed when everything commits atomically), and `response_body` is `json`.
