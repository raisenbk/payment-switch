package com.paymentswitch.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature: {@code hex(HMAC_SHA256(webhook_secret, timestamp + "." + body))}, sent as
 * {@code X-Webhook-Signature} with the Unix-seconds timestamp in {@code X-Webhook-Timestamp}.
 * Receivers recompute it over the raw body and reject old timestamps to stop replays.
 */
final class WebhookSignature {

    private WebhookSignature() {
    }

    static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
