package com.paymentswitch.merchant.auth;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.sign;
import static com.paymentswitch.support.SignedRequests.signed;
import static com.paymentswitch.support.SignedRequests.withHeaders;
import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HmacAuthenticationIntegrationTest extends AbstractIntegrationTest {

    private static final String BODY = "{\"amount\":100}";

    @Autowired
    TestMerchants merchants;

    TestMerchant merchant;

    @BeforeEach
    void createMerchant() {
        merchant = merchants.create();
    }

    @Test
    void validSignatureIsAccepted() {
        HttpResponse<String> response = send(signed(baseUri(), merchant, "GET", "/v1/me", "").build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(merchant.id().toString());
    }

    @Test
    void signedRequestToUnknownRouteIsNotFound() {
        HttpResponse<String> response = send(signed(baseUri(), merchant, "GET", "/v1/does-not-exist", "").build());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void wrongSecretIsRejected() {
        long now = now();
        String signature = sign("not-the-secret", "GET", "/v1/me", now, "");

        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me", "", merchant.apiKey(), now, signature).build());
    }

    @Test
    void tamperedBodyIsRejected() {
        long now = now();
        String signature = sign(merchant.secret(), "POST", "/v1/test/probe", now, BODY);

        assertUnauthorized(withHeaders(baseUri(), "POST", "/v1/test/probe", "{\"amount\":999999}",
                merchant.apiKey(), now, signature).header("Idempotency-Key", "tamper").build());
    }

    @Test
    void tamperedQueryStringIsRejected() {
        long now = now();
        String signature = sign(merchant.secret(), "GET", "/v1/me", now, "");

        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me?page=2", "", merchant.apiKey(), now, signature)
                .build());
    }

    @Test
    void signatureForAnotherMethodIsRejected() {
        long now = now();
        String signature = sign(merchant.secret(), "GET", "/v1/me", now, "");

        assertUnauthorized(withHeaders(baseUri(), "DELETE", "/v1/me", "", merchant.apiKey(), now, signature).build());
    }

    @Test
    void expiredTimestampIsRejectedEvenWithValidSignature() {
        long tooOld = now() - 301;
        String signature = sign(merchant.secret(), "GET", "/v1/me", tooOld, "");

        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me", "", merchant.apiKey(), tooOld, signature).build());
    }

    @Test
    void futureTimestampBeyondToleranceIsRejected() {
        long tooNew = now() + 301;
        String signature = sign(merchant.secret(), "GET", "/v1/me", tooNew, "");

        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me", "", merchant.apiKey(), tooNew, signature).build());
    }

    @Test
    void unknownApiKeyIsRejected() {
        long now = now();
        String signature = sign(merchant.secret(), "GET", "/v1/me", now, "");

        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me", "", "pk_unknown", now, signature).build());
    }

    @Test
    void malformedSignatureIsRejected() {
        assertUnauthorized(withHeaders(baseUri(), "GET", "/v1/me", "", merchant.apiKey(), now(), "zz-not-hex")
                .build());
    }

    @Test
    void missingAuthHeadersAreRejected() {
        assertUnauthorized(HttpRequest.newBuilder(baseUri().resolve("/v1/me")).GET().build());
    }

    @Test
    void inactiveCredentialIsRejected() {
        merchants.deactivateCredentials(merchant);

        assertUnauthorized(signed(baseUri(), merchant, "GET", "/v1/me", "").build());
    }

    @Test
    void suspendedMerchantIsRejected() {
        merchants.suspend(merchant);

        assertUnauthorized(signed(baseUri(), merchant, "GET", "/v1/me", "").build());
    }

    @Test
    void oversizedBodyIsRejected() {
        String huge = "{\"amount\":1,\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";

        HttpResponse<String> response = send(signed(baseUri(), merchant, "POST", "/v1/test/probe", huge)
                .header("Idempotency-Key", "huge")
                .build());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void routesOutsideTheApiAreDeniedByDefault() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(baseUri().resolve("/internal/anything"))
                .method("GET", BodyPublishers.noBody())
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private void assertUnauthorized(HttpRequest request) {
        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .contains("\"code\":\"AUTHENTICATION_FAILED\"")
                .contains("\"traceId\":");
        request.headers().firstValue("X-Signature")
                .ifPresent(signature -> assertThat(response.body()).doesNotContain(signature));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
