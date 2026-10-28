package com.paymentswitch.merchant.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The merchant request-signing scheme. A client signs:
 *
 * <pre>
 * METHOD \n PATH_WITH_QUERY \n UNIX_TIMESTAMP_SECONDS \n hex(SHA-256(body))
 * </pre>
 *
 * with HMAC-SHA256 keyed by its API secret, and sends the lowercase hex result in {@code X-Signature}.
 */
public final class RequestSignature {

    private static final HexFormat HEX = HexFormat.of();

    private RequestSignature() {
    }

    public static String canonicalString(String method, String pathWithQuery, long timestamp, byte[] body) {
        return method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + HEX.formatHex(sha256(body));
    }

    public static String sign(String secret, String canonicalString) {
        return HEX.formatHex(hmac(secret, canonicalString));
    }

    /** Constant-time comparison of the expected and presented signatures. */
    public static boolean matches(String secret, String canonicalString, String presentedHex) {
        byte[] presented;
        try {
            presented = HEX.parseHex(presentedHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, canonicalString), presented);
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
