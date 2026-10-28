package com.paymentswitch.common.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The response to send: either freshly produced or replayed from storage.
 *
 * @param jsonBody the exact JSON returned the first time
 * @param replayed true when the action did not run because the key was already completed
 */
public record IdempotentResult(int status, String jsonBody, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public ResponseEntity<String> toResponseEntity() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(jsonBody);
    }
}
