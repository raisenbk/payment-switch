package com.paymentswitch.mocksupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
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
    void unknownReferenceIsNotFound() {
        assertThat(inquiry("nope")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult submit(String reference, String destination) {
        return mvc.post().uri("/suppliers/MOCKA/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","sku":"SKU-10","destination":"%s","amount":10000}"""
                        .formatted(reference, destination))
                .exchange();
    }

    private MvcTestResult inquiry(String reference) {
        return mvc.get().uri("/suppliers/MOCKA/transactions/" + reference).exchange();
    }
}
