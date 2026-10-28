package com.paymentswitch.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.web.util.WebUtils;

/** SHA-256 over method, path (with query) and raw body: identifies "the same request" for idempotency. */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(HttpServletRequest request) {
        CachedBodyHttpServletRequest cached = WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
        if (cached == null) {
            throw new IllegalStateException("Request body was not cached; is the request authenticated?");
        }
        MessageDigest digest = sha256();
        digest.update((request.getMethod() + "\n" + RequestPaths.pathWithQuery(request) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update(cached.body());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
