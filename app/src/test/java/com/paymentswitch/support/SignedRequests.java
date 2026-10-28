package com.paymentswitch.support;

import com.paymentswitch.merchant.auth.RequestSignature;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Builds HTTP requests signed the way a merchant client would sign them. */
public final class SignedRequests {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private SignedRequests() {
    }

    /** A correctly signed request with the current timestamp. */
    public static HttpRequest.Builder signed(URI base, TestMerchant merchant, String method, String pathWithQuery,
            String body) {
        long timestamp = Instant.now().getEpochSecond();
        return withHeaders(base, method, pathWithQuery, body, merchant.apiKey(), timestamp,
                sign(merchant.secret(), method, pathWithQuery, timestamp, body));
    }

    public static String sign(String secret, String method, String pathWithQuery, long timestamp, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return RequestSignature.sign(secret, RequestSignature.canonicalString(method, pathWithQuery, timestamp, bytes));
    }

    /** A request with explicit auth header values, for building deliberately broken requests. */
    public static HttpRequest.Builder withHeaders(URI base, String method, String pathWithQuery, String body,
            String apiKey, long timestamp, String signature) {
        return HttpRequest.newBuilder(base.resolve(pathWithQuery))
                .method(method, body.isEmpty() ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .header("X-Timestamp", Long.toString(timestamp))
                .header("X-Signature", signature);
    }

    public static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
