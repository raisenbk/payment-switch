package com.paymentswitch.common.idempotency;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestMerchants merchants;

    @Autowired
    JdbcClient jdbc;

    @Test
    void replayedRequestReturnsStoredResponseWithoutExecutingAgain() {
        TestMerchant merchant = merchants.create();
        HttpRequest request = probe(merchant, "key-replay", "{\"amount\":100}");

        HttpResponse<String> first = send(request);
        HttpResponse<String> second = send(request);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(effectCount(merchant)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        TestMerchant merchant = merchants.create();
        send(probe(merchant, "key-mismatch", "{\"amount\":100}"));

        HttpResponse<String> response = send(probe(merchant, "key-mismatch", "{\"amount\":999}"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"");
        assertThat(effectCount(merchant)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerMerchant() {
        TestMerchant first = merchants.create();
        TestMerchant second = merchants.create();

        HttpResponse<String> a = send(probe(first, "shared-key", "{\"amount\":100}"));
        HttpResponse<String> b = send(probe(second, "shared-key", "{\"amount\":100}"));

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        assertThat(b.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(effectCount(first)).isEqualTo(1);
        assertThat(effectCount(second)).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotConsumeTheKey() {
        TestMerchant merchant = merchants.create();

        HttpResponse<String> failed = send(probe(merchant, "key-retry", "{\"amount\":5000000}"));
        HttpResponse<String> retried = send(probe(merchant, "key-retry", "{\"amount\":100}"));

        assertThat(failed.statusCode()).isEqualTo(422);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(effectCount(merchant)).isEqualTo(1);
    }

    @Test
    void malformedKeyIsRejected() {
        TestMerchant merchant = merchants.create();

        HttpResponse<String> response = send(probe(merchant, "not a valid key!", "{\"amount\":100}"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_IDEMPOTENCY_KEY\"");
        assertThat(effectCount(merchant)).isZero();
    }

    @Test
    void missingKeyIsRejected() {
        TestMerchant merchant = merchants.create();

        HttpResponse<String> response = send(
                signed(baseUri(), merchant, "POST", "/v1/test/probe", "{\"amount\":100}").build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(effectCount(merchant)).isZero();
    }

    private HttpRequest probe(TestMerchant merchant, String key, String body) {
        return signed(baseUri(), merchant, "POST", "/v1/test/probe", body).header("Idempotency-Key", key).build();
    }

    private long effectCount(TestMerchant merchant) {
        return jdbc.sql("SELECT count(*) FROM test_probe_effects WHERE merchant_id = :m")
                .param("m", merchant.id())
                .query(Long.class)
                .single();
    }
}
