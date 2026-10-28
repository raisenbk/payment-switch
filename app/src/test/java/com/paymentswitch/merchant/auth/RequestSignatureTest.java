package com.paymentswitch.merchant.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestSignatureTest {

    private static final byte[] BODY = "{\"amount\":1000}".getBytes(StandardCharsets.UTF_8);

    @Test
    void canonicalStringJoinsMethodPathTimestampAndBodyHash() {
        assertThat(RequestSignature.canonicalString("POST", "/v1/transactions?dryRun=true", 1767225600, BODY))
                .isEqualTo("POST\n/v1/transactions?dryRun=true\n1767225600\n"
                        + "612612d208fb618eb2b007d2a7f8d7a1cfb511532389298f1cc33322c3094bcc");
    }

    @Test
    void emptyBodyHashesToSha256OfNothing() {
        assertThat(RequestSignature.canonicalString("GET", "/v1/me", 1, new byte[0]))
                .endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    /** Reference value computed independently with {@code openssl dgst -sha256 -hmac}. */
    @Test
    void signatureMatchesIndependentReference() {
        String canonical = RequestSignature.canonicalString("POST", "/v1/transactions?dryRun=true", 1767225600, BODY);

        assertThat(RequestSignature.sign("test-secret-123", canonical))
                .isEqualTo("e7c2a61fa3d5951088597a748773735cbcc0cf38d5e8b04aefbf150cfa40a88e");
    }

    @Test
    void matchesAcceptsCorrectSignatureCaseInsensitively() {
        String canonical = RequestSignature.canonicalString("GET", "/v1/me", 1, new byte[0]);
        String signature = RequestSignature.sign("secret", canonical);

        assertThat(RequestSignature.matches("secret", canonical, signature)).isTrue();
        assertThat(RequestSignature.matches("secret", canonical, signature.toUpperCase())).isTrue();
    }

    @Test
    void matchesRejectsWrongOrMalformedSignatures() {
        String canonical = RequestSignature.canonicalString("GET", "/v1/me", 1, new byte[0]);
        String signature = RequestSignature.sign("secret", canonical);

        assertThat(RequestSignature.matches("other-secret", canonical, signature)).isFalse();
        assertThat(RequestSignature.matches("secret", canonical, signature.substring(2))).isFalse();
        assertThat(RequestSignature.matches("secret", canonical, "not-hex")).isFalse();
        assertThat(RequestSignature.matches("secret", canonical, "")).isFalse();
    }
}
