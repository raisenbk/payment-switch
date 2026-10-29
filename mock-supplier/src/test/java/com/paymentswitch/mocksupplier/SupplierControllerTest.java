package com.paymentswitch.mocksupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(properties = "mock-supplier.delay=200ms")
@AutoConfigureMockMvc
class SupplierControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void defaultDestinationSucceeds() {
        MvcTestResult result = submit(UUID.randomUUID().toString(), "081200000000");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS");
        assertThat(result).bodyJson().extractingPath("$.supplierRef").asString().startsWith("MS-");
    }

    @Test
    void failSuffixIsADefinitiveFailure() {
        MvcTestResult result = submit(UUID.randomUUID().toString(), "081200000011");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FAILED");
        assertThat(result).bodyJson().extractingPath("$.errorCode").isEqualTo("INVALID_DESTINATION");
    }

    @Test
    void rejectedRequestIsNotStored() {
        String reference = UUID.randomUUID().toString();

        assertThat(submit(reference, "081200000066")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(inquiry(reference)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void serverErrorStillProcessedTheRequest() {
        String reference = UUID.randomUUID().toString();

        assertThat(submit(reference, "081200000055")).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS");
    }

    @Test
    void duplicateSubmitReturnsStoredOutcomeWithConflict() throws Exception {
        String reference = UUID.randomUUID().toString();
        MvcTestResult first = submit(reference, "081200000000");

        MvcTestResult second = submit(reference, "081200000000");

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).bodyText().isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void pendingBecomesSuccessAfterTheDelay() {
        String reference = UUID.randomUUID().toString();

        assertThat(submit(reference, "081200000044")).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS"));
    }

    @Test
    void stuckPendingNeverResolves() throws Exception {
        String reference = UUID.randomUUID().toString();

        assertThat(submit(reference, "081200000077")).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        Thread.sleep(400);

        assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void slowSuccessIsInvisibleUntilTheDelayHasPassed() {
        String reference = UUID.randomUUID().toString();

        assertThat(inquiry(reference)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(submit(reference, "081200000088")).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS");

        assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS");
    }

    @Test
    void slowSuccessAnswersNotFoundWhileProcessing() throws Exception {
        String reference = UUID.randomUUID().toString();
        Thread submitting = Thread.ofVirtual().start(() -> submit(reference, "081200000088"));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(submit(reference, "081200000088")).hasStatus(HttpStatus.CONFLICT));

        assertThat(inquiry(reference)).hasStatus(HttpStatus.NOT_FOUND);

        submitting.join();
        assertThat(inquiry(reference)).bodyJson().extractingPath("$.status").isEqualTo("SUCCESS");
    }

    @Test
    void outOfStockSupplierFailsDefinitively() {
        MvcTestResult result = submit("MOCKOOS", UUID.randomUUID().toString(), "081200000000", 10_000);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FAILED");
        assertThat(result).bodyJson().extractingPath("$.errorCode").isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void settlementListsTodaysSuccessfulTopUpsForThatSupplier() {
        String supplier = "SETTLE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String ok = UUID.randomUUID().toString();
        String failed = UUID.randomUUID().toString();
        submit(supplier, ok, "081200000000", 10_000);
        submit(supplier, failed, "081200000011", 10_000);
        submit("OTHER", UUID.randomUUID().toString(), "081200000000", 10_000);

        String csv = settlement(supplier, false);

        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst()).isEqualTo(SupplierController.SETTLEMENT_HEADER);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith(ok + ",MS-").contains(",SKU-10,081200000000,10000,IDR,");
    }

    @Test
    void settlementWithDiscrepanciesContainsOneOfEachMistake() {
        String supplier = "SETTLE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        List<String> references = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        references.forEach(reference -> submit(supplier, reference, "081200000000", 10_000));

        List<String> lines = settlement(supplier, true).lines().skip(1).toList();

        assertThat(lines).hasSize(4);
        assertThat(lines).noneMatch(line -> line.startsWith(references.get(0)));
        assertThat(lines.get(0)).startsWith(references.get(1)).contains(",10100,");
        assertThat(lines.get(2)).isEqualTo(lines.get(1)).startsWith(references.get(2));
        assertThat(lines.get(3)).contains(",MS-GHOST001,");
    }

    @Test
    void unknownReferenceIsNotFound() {
        assertThat(inquiry("nope")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult submit(String reference, String destination) {
        return submit("MOCKA", reference, destination, 10_000);
    }

    private MvcTestResult submit(String supplier, String reference, String destination, long amount) {
        return mvc.post().uri("/suppliers/" + supplier + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","sku":"SKU-10","destination":"%s","amount":%d}"""
                        .formatted(reference, destination, amount))
                .exchange();
    }

    private String settlement(String supplier, boolean discrepancies) {
        MvcTestResult result = mvc.get()
                .uri("/suppliers/" + supplier + "/settlements/" + LocalDate.now(ZoneOffset.UTC)
                        + "?discrepancies=" + discrepancies)
                .exchange();
        assertThat(result).hasStatusOk();
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult inquiry(String reference) {
        return mvc.get().uri("/suppliers/MOCKA/transactions/" + reference).exchange();
    }
}
