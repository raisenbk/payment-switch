package com.paymentswitch.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.LedgerService;
import com.paymentswitch.support.TestCatalog;
import com.paymentswitch.support.TestCatalog.TestProduct;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhooks end to end: a transaction reaches a final status → outbox → webhook event → signed POST
 * to a stub merchant endpoint (WireMock), with retries, dead-lettering and admin re-drive.
 * Test config: 4 attempts, 200ms initial delay capped at 800ms.
 */
class WebhookDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token-not-secret";
    private static final Duration MAX_WAIT = Duration.ofSeconds(20);

    static final WireMockServer RECEIVER = new WireMockServer(options().dynamicPort());

    @Autowired
    TestMerchants merchants;

    @Autowired
    TestCatalog catalog;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    PlatformTransactionManager transactionManager;

    String path;
    TestMerchant merchant;
    TestProduct product;

    @BeforeAll
    static void startReceiver() {
        RECEIVER.start();
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop();
    }

    @BeforeEach
    void setUp() {
        path = "/hooks/" + UUID.randomUUID();
        merchant = merchants.createWithWebhook(RECEIVER.baseUrl() + path);
        ledger.topUp(merchant.id(), Money.of(100_000, "IDR"), "seed");
        product = catalog.productSuppliedByMockA(10_500, 10_000);
    }

    @Test
    void successfulTransactionSendsASignedWebhook() {
        RECEIVER.stubFor(post(path).willReturn(ok()));

        UUID transactionId = createTransaction("081234560000");

        UUID eventId = awaitWebhookStatus(transactionId, "DELIVERED");
        LoggedRequest request = single(requests());
        String body = request.getBodyAsString();
        assertThat(JsonPath.<String>read(body, "$.id")).isEqualTo(eventId.toString());
        assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("transaction.succeeded");
        assertThat(JsonPath.<String>read(body, "$.data.id")).isEqualTo(transactionId.toString());
        assertThat(JsonPath.<String>read(body, "$.data.status")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<String>read(body, "$.data.supplierRef")).startsWith("MS-");
        // Same field order as GET /v1/transactions/{id} (the outbox stores payloads as exact json).
        assertThat(body).contains("\"data\":{\"id\":\"" + transactionId + "\",\"merchantRef\":");
        assertThat(request.getHeader("X-Webhook-Id")).isEqualTo(eventId.toString());
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertSignedWithMerchantSecret(request);
        assertThat(deliveries(eventId)).containsExactly("1 200");
    }

    @Test
    void failedTransactionSendsAFailedEvent() {
        RECEIVER.stubFor(post(path).willReturn(ok()));

        UUID transactionId = createTransaction("081234560011");

        awaitWebhookStatus(transactionId, "DELIVERED");
        String body = single(requests()).getBodyAsString();
        assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("transaction.failed");
        assertThat(JsonPath.<String>read(body, "$.data.failureCode")).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    void endpointDownThenBackIsRetriedWithTheSameEventId() {
        RECEIVER.stubFor(post(path).inScenario(path).whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        RECEIVER.stubFor(post(path).inScenario(path).whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        RECEIVER.stubFor(post(path).inScenario(path).whenScenarioStateIs("recovered").willReturn(ok()));

        UUID transactionId = createTransaction("081234560000");

        UUID eventId = awaitWebhookStatus(transactionId, "DELIVERED");
        List<LoggedRequest> requests = requests();
        assertThat(requests).hasSize(3);
        assertThat(requests).extracting(r -> r.getHeader("X-Webhook-Id")).containsOnly(eventId.toString());
        assertThat(requests).allSatisfy(this::assertSignedWithMerchantSecret);
        assertThat(deliveries(eventId)).containsExactly("1 503", "2 503", "3 200");
    }

    @Test
    void permanentFailureIsDeadLetteredAndCanBeRedriven() throws Exception {
        RECEIVER.stubFor(post(path).willReturn(serverError()));
        UUID transactionId = createTransaction("081234560000");

        UUID eventId = awaitWebhookStatus(transactionId, "DEAD");
        assertThat(deliveries(eventId)).containsExactly("1 500", "2 500", "3 500", "4 500");

        HttpResponse<String> dead = admin("GET", "/admin/webhooks?status=DEAD&limit=200");
        assertThat(dead.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(dead.body(), "$[*].id")).contains(eventId.toString());
        assertThat(JsonPath.<List<Integer>>read(dead.body(), "$[?(@.id == '" + eventId + "')].deliveries.length()"))
                .containsExactly(4);

        RECEIVER.stubFor(post(path).willReturn(ok()));
        HttpResponse<String> redriven = admin("POST", "/admin/webhooks/" + eventId + "/redrive");
        assertThat(redriven.statusCode()).isEqualTo(202);
        assertThat(JsonPath.<String>read(redriven.body(), "$.status")).isEqualTo("PENDING");

        awaitWebhookStatus(transactionId, "DELIVERED");
        assertThat(deliveries(eventId)).containsExactly("1 500", "2 500", "3 500", "4 500", "5 200");
        assertThat(admin("POST", "/admin/webhooks/" + eventId + "/redrive").statusCode()).isEqualTo(409);
    }

    @Test
    void unreachableEndpointIsRecordedWithoutAStatusCode() {
        merchant = merchants.createWithWebhook("http://localhost:1/unreachable");
        ledger.topUp(merchant.id(), Money.of(100_000, "IDR"), "seed");

        UUID transactionId = createTransaction("081234560000");

        UUID eventId = awaitWebhookStatus(transactionId, "DEAD");
        assertThat(deliveries(eventId)).containsExactly("1 -", "2 -", "3 -", "4 -");
        assertThat(jdbc.sql("SELECT error FROM webhook_deliveries WHERE webhook_event_id = :id ORDER BY id LIMIT 1")
                .param("id", eventId).query(String.class).single()).contains("ConnectException");
    }

    @Test
    void merchantWithoutWebhookUrlGetsNoEvent() {
        merchant = merchants.create();
        ledger.topUp(merchant.id(), Money.of(100_000, "IDR"), "seed");

        UUID transactionId = createTransaction("081234560000");

        await().atMost(MAX_WAIT).until(() -> jdbc.sql("""
                        SELECT count(*) FROM outbox_events
                         WHERE aggregate_id = :id AND event_type = 'transaction.completed' AND processed_at IS NOT NULL""")
                .param("id", transactionId).query(Long.class).single() == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_events WHERE merchant_id = :m")
                .param("m", merchant.id()).query(Long.class).single()).isZero();
    }

    @Test
    void adminEndpointsRequireTheAdminToken() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri().resolve("/admin/webhooks"));

        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(request.header("X-Admin-Token", "wrong").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        // A merchant's signed request is not an admin request either.
        assertThat(send(signed(baseUri(), merchant, "GET", "/admin/webhooks", "").build()).statusCode())
                .isEqualTo(401);
        assertThat(admin("GET", "/admin/webhooks").statusCode()).isEqualTo(200);
    }

    /**
     * Several dispatchers (as if several instances) race for the same due webhooks. More events than
     * one batch (50), so the claims really overlap.
     */
    @RepeatedTest(3)
    void concurrentDispatchersDeliverEachEventOnce() throws Exception {
        RECEIVER.stubFor(post(path).willReturn(ok()));
        // Committed together, so all due at once for the racing dispatchers (and the scheduled one).
        List<UUID> eventIds = new TransactionTemplate(transactionManager).execute(s ->
                Stream.generate(this::insertPendingWebhook).limit(200).toList());

        int dispatchers = 6;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(dispatchers)) {
            List<Future<Object>> running = Stream.generate(() -> executor.<Object>submit(() -> {
                start.await();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (pending(eventIds) > 0 && System.nanoTime() < deadline) {
                    dispatcher.poll();
                }
                return null;
            })).limit(dispatchers).toList();
            start.countDown();
            for (Future<Object> future : running) {
                future.get();
            }
        }

        assertThat(pending(eventIds)).isZero();
        List<String> sentIds = requests().stream().map(r -> r.getHeader("X-Webhook-Id")).toList();
        assertThat(sentIds).hasSize(eventIds.size()).doesNotHaveDuplicates();
    }

    private UUID createTransaction(String destination) {
        String body = """
                {"merchantRef":"ref-%s","productCode":"%s","destination":"%s"}"""
                .formatted(UUID.randomUUID(), product.code(), destination);
        HttpResponse<String> response = send(signed(baseUri(), merchant, "POST", "/v1/transactions", body)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .build());
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    /** Waits until the webhook for this transaction has {@code status}; returns its event id. */
    private UUID awaitWebhookStatus(UUID transactionId, String status) {
        UUID[] id = new UUID[1];
        await().atMost(MAX_WAIT).until(() -> {
            List<UUID> found = jdbc.sql("""
                            SELECT id FROM webhook_events
                             WHERE payload->'data'->>'id' = :tx AND status = :status""")
                    .param("tx", transactionId.toString())
                    .param("status", status)
                    .query(UUID.class)
                    .list();
            id[0] = found.isEmpty() ? null : found.getFirst();
            return id[0] != null;
        });
        return id[0];
    }

    private List<String> deliveries(UUID eventId) {
        return jdbc.sql("""
                        SELECT attempt || ' ' || COALESCE(status_code::text, '-')
                          FROM webhook_deliveries WHERE webhook_event_id = :id ORDER BY id""")
                .param("id", eventId)
                .query(String.class)
                .list();
    }

    private List<LoggedRequest> requests() {
        return RECEIVER.findAll(postRequestedFor(urlEqualTo(path)));
    }

    private void assertSignedWithMerchantSecret(LoggedRequest request) {
        long timestamp = Long.parseLong(request.getHeader("X-Webhook-Timestamp"));
        assertThat(Math.abs(Instant.now().getEpochSecond() - timestamp)).isLessThan(60);
        assertThat(request.getHeader("X-Webhook-Signature"))
                .isEqualTo(WebhookSignature.sign(merchant.webhookSecret(), timestamp, request.getBodyAsString()));
    }

    private HttpResponse<String> admin(String method, String uri) throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return client.send(HttpRequest.newBuilder(baseUri().resolve(uri))
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private UUID insertPendingWebhook() {
        UUID aggregateId = UUID.randomUUID();
        long outboxId = jdbc.sql("""
                        INSERT INTO outbox_events (event_type, aggregate_id, processed_at)
                        VALUES ('test.direct', :id, now()) RETURNING id""")
                .param("id", aggregateId)
                .query(Long.class)
                .single();
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhook_events (id, outbox_event_id, merchant_id, event_type, payload, next_attempt_at)
                        VALUES (:id, :outboxId, :merchant, 'test.direct', CAST(:payload AS json), now())""")
                .param("id", eventId)
                .param("outboxId", outboxId)
                .param("merchant", merchant.id())
                .param("payload", "{\"id\":\"" + eventId + "\"}")
                .update();
        return eventId;
    }

    private long pending(List<UUID> eventIds) {
        return jdbc.sql("SELECT count(*) FROM webhook_events WHERE id IN (:ids) AND status = 'PENDING'")
                .param("ids", eventIds)
                .query(Long.class)
                .single();
    }

    private static <T> T single(List<T> list) {
        assertThat(list).hasSize(1);
        return list.getFirst();
    }
}
