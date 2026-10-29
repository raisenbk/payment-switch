package com.paymentswitch.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Crash recovery through the transactional outbox. The PENDING row, the hold and the
 * {@code transaction.accepted} event commit together, so whatever happens to the instance after
 * that commit, another poll picks the work up.
 *
 * <p>A crash is simulated by leaving the event leased, as if claimed by an instance that died,
 * and then letting the lease expire.
 */
class TransactionRecoveryIntegrationTest extends AbstractTransactionIntegrationTest {

    private static final String DESTINATION = "081234560000";

    @Autowired
    TransactionService service;

    @Autowired
    TransactionStateMachine stateMachine;

    @Test
    void crashAfterAcceptingIsRecoveredFromTheOutbox() {
        UUID id = acceptClaimedByCrashedInstance();

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .until(() -> status(id).equals("PENDING"));
        expireLease(id);

        awaitStatus(id, "SUCCESS");
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->SUCCESS");
        assertThat(outboxProcessed(id)).isTrue();
    }

    @Test
    void crashBeforeTheSupplierCallSubmitsWithTheSameReference() {
        UUID id = acceptClaimedByCrashedInstance();
        markProcessing(id);
        expireLease(id);

        String done = awaitStatus(id, "SUCCESS");

        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->SUCCESS");
        assertThat(eventPayload(id, "SUCCESS")).contains("\"via\": \"resubmit\"");
        assertThat(supplierInquiry(id)).isEqualTo(JsonPath.read(done, "$.supplierRef"));
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(0)));
    }

    @Test
    void crashAfterTheSupplierCallDoesNotTopUpTwice() throws Exception {
        UUID id = acceptClaimedByCrashedInstance();
        markProcessing(id);
        String supplierRef = submitToSupplier(id); // the call the crashed instance made before dying
        expireLease(id);

        String done = awaitStatus(id, "SUCCESS");

        // The supplier deduplicated our reference (409 + stored outcome): same top-up, no second one.
        assertThat(JsonPath.<String>read(done, "$.supplierRef")).isEqualTo(supplierRef);
        assertThat(ledgerEntries(id, "CAPTURE")).hasSize(3);
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(0)));
    }

    /** Accepts a transaction whose outbox event is already claimed by an instance that will never finish. */
    private UUID acceptClaimedByCrashedInstance() {
        return transaction().execute(s -> {
            UUID id = service.create(merchant.id(), "ref-" + UUID.randomUUID(), product.code(), DESTINATION).id();
            jdbc.sql("""
                            UPDATE outbox_events SET available_at = now() + interval '1 hour', attempts = 1
                             WHERE aggregate_id = :id""")
                    .param("id", id)
                    .update();
            return id;
        });
    }

    /** The crashed instance had routed the transaction and committed PENDING -> PROCESSING. */
    private void markProcessing(UUID id) {
        transaction().executeWithoutResult(s -> stateMachine.transition(id, TransactionStatus.PENDING,
                TransactionStatus.PROCESSING,
                StatusChange.routed(catalog.supplierId("MOCKA"), SUPPLIER_COST, "SKU-" + product.code()),
                Map.of("supplier", "MOCKA")));
    }

    private void expireLease(UUID id) {
        jdbc.sql("UPDATE outbox_events SET available_at = now() WHERE aggregate_id = :id").param("id", id).update();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM transactions WHERE id = :id").param("id", id).query(String.class).single();
    }

    private boolean outboxProcessed(UUID id) {
        return jdbc.sql("SELECT processed_at IS NOT NULL FROM outbox_events WHERE aggregate_id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String submitToSupplier(UUID id) throws Exception {
        String body = """
                {"reference":"%s","sku":"SKU-%s","destination":"%s","amount":%d}"""
                .formatted(id, product.code(), DESTINATION, PRICE);
        HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create(mockSupplierUrl("MOCKA") + "/transactions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.supplierRef");
    }

    private String supplierInquiry(UUID id) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                    HttpRequest.newBuilder(URI.create(mockSupplierUrl("MOCKA") + "/transactions/" + id)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return JsonPath.read(response.body(), "$.supplierRef");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
