package com.paymentswitch.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    @Test
    void matchesAnIndependentlyComputedSignature() {
        // printf '%s' '1700000000.{"id":"evt_1","type":"transaction.succeeded"}' \
        //   | openssl dgst -sha256 -hmac 'whsec_test_vector' -hex
        String body = "{\"id\":\"evt_1\",\"type\":\"transaction.succeeded\"}";

        assertThat(WebhookSignature.sign("whsec_test_vector", 1_700_000_000L, body))
                .isEqualTo("802cedaaf0db673b28bc112f5b0cd60f8e7c04bc264236008a9d8e42e9eada11");
    }

    @Test
    void timestampIsPartOfTheSignature() {
        assertThat(WebhookSignature.sign("s", 1L, "{}")).isNotEqualTo(WebhookSignature.sign("s", 2L, "{}"));
    }
}
