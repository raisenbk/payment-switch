package com.paymentswitch.common.idempotency;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final int PARALLEL_REQUESTS = 50;

    @Autowired
    TestMerchants merchants;

    @Autowired
    JdbcClient jdbc;

    @RepeatedTest(3)
    void fiftyParallelIdenticalRequestsExecuteExactlyOnce() throws Exception {
        TestMerchant merchant = merchants.create();
        HttpRequest request = signed(baseUri(), merchant, "POST", "/v1/test/probe", "{\"amount\":1500}")
                .header("Idempotency-Key", "parallel-key-1")
                .build();

        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return send(request);
                }));
            }
            start.countDown();
        }

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get());
        }

        assertThat(effectCount(merchant)).isEqualTo(1);
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(responses).extracting(HttpResponse::body).containsOnly(responses.getFirst().body());
        assertThat(responses)
                .filteredOn(response -> response.headers().firstValue("Idempotent-Replayed").isEmpty())
                .hasSize(1);
    }

    private long effectCount(TestMerchant merchant) {
        return jdbc.sql("SELECT count(*) FROM test_probe_effects WHERE merchant_id = :m")
                .param("m", merchant.id())
                .query(Long.class)
                .single();
    }
}
