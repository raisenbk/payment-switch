package com.paymentswitch.common.idempotency;

import com.paymentswitch.common.error.ApiException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes an action at most once per (merchant, idempotency key).
 *
 * <p>The key is claimed with {@code INSERT ... ON CONFLICT DO NOTHING} in the same database
 * transaction as the action and the stored response. Consequences:
 * <ul>
 *   <li>A concurrent request with the same key blocks on the primary key until the first one
 *       commits, then replays its response. Exactly one execution, no "in progress" state.</li>
 *   <li>If the action throws, everything rolls back, including the claim, so the client can retry
 *       with the same key.</li>
 *   <li>The same key with a different request fingerprint is rejected with 409.</li>
 * </ul>
 * The action must therefore only do local database work; slow external calls belong after commit
 * (via the outbox, Phase 5).
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    public IdempotencyService(JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
    }

    public IdempotentResult execute(UUID merchantId, String key, String requestHash,
            Supplier<IdempotentResponse> action) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-100 characters of A-Z, a-z, 0-9, '_' or '-'");
        }
        return transaction.execute(status -> {
            if (!claim(merchantId, key, requestHash)) {
                return replay(merchantId, key, requestHash);
            }
            IdempotentResponse response = action.get();
            String json = jsonMapper.writeValueAsString(response.body());
            jdbc.sql("""
                            UPDATE idempotency_keys
                               SET response_status = :status, response_body = CAST(:body AS json)
                             WHERE merchant_id = :merchantId AND idempotency_key = :key""")
                    .param("status", response.status().value())
                    .param("body", json)
                    .param("merchantId", merchantId)
                    .param("key", key)
                    .update();
            return new IdempotentResult(response.status().value(), json, false);
        });
    }

    /** @return true if this transaction now owns the key; blocks while another transaction holds it */
    private boolean claim(UUID merchantId, String key, String requestHash) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash)
                        VALUES (:merchantId, :key, :hash)
                        ON CONFLICT DO NOTHING""")
                .param("merchantId", merchantId)
                .param("key", key)
                .param("hash", requestHash)
                .update() == 1;
    }

    private IdempotentResult replay(UUID merchantId, String key, String requestHash) {
        StoredResponse stored = jdbc.sql("""
                        SELECT request_hash, response_status, response_body::text AS response_body
                          FROM idempotency_keys
                         WHERE merchant_id = :merchantId AND idempotency_key = :key""")
                .param("merchantId", merchantId)
                .param("key", key)
                .query(StoredResponse.class)
                .single();
        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different request");
        }
        return new IdempotentResult(stored.responseStatus(), stored.responseBody(), true);
    }

    private record StoredResponse(String requestHash, int responseStatus, String responseBody) {
    }
}
