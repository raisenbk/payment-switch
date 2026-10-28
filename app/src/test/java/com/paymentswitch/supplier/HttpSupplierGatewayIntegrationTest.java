package com.paymentswitch.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.supplier.SupplierResult.Failed;
import com.paymentswitch.supplier.SupplierResult.Success;
import com.paymentswitch.supplier.SupplierResult.Unknown;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The adapter against the real mock-supplier: every supplier behaviour maps to the right typed result. */
class HttpSupplierGatewayIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final SupplierGateway gateway = new HttpSupplierGateway("MOCKA",
            new SupplierProperties.Endpoint(URI.create(mockSupplierUrl("MOCKA")), TIMEOUT, TIMEOUT));

    @Test
    void successIsSuccess() {
        assertThat(submit("081200000000")).isInstanceOfSatisfying(Success.class,
                success -> assertThat(success.supplierRef()).startsWith("MS-"));
    }

    @Test
    void businessFailureIsFailed() {
        assertThat(submit("081200000011")).isEqualTo(new Failed("INVALID_DESTINATION"));
    }

    @Test
    void rejectedRequestIsFailed() {
        assertThat(submit("081200000066")).isEqualTo(new Failed("INVALID_SKU"));
    }

    @Test
    void readTimeoutIsUnknownAndInquiryLaterRevealsSuccess() {
        String reference = UUID.randomUUID().toString();

        assertThat(gateway.submit(request(reference, "081200000022"))).isEqualTo(new Unknown("READ_TIMEOUT"));
        await().atMost(SUPPLIER_DELAY.multipliedBy(3))
                .untilAsserted(() -> assertThat(gateway.inquiry(reference)).isInstanceOf(Success.class));
    }

    @Test
    void readTimeoutIsUnknownEvenWhenTheSupplierFailed() {
        String reference = UUID.randomUUID().toString();

        assertThat(gateway.submit(request(reference, "081200000033"))).isEqualTo(new Unknown("READ_TIMEOUT"));
        assertThat(gateway.inquiry(reference)).isEqualTo(new Failed("INVALID_DESTINATION"));
    }

    @Test
    void pendingIsUnknownUntilTheSupplierCompletes() {
        String reference = UUID.randomUUID().toString();

        assertThat(gateway.submit(request(reference, "081200000044"))).isEqualTo(new Unknown("SUPPLIER_PENDING"));
        await().atMost(SUPPLIER_DELAY.multipliedBy(3))
                .untilAsserted(() -> assertThat(gateway.inquiry(reference)).isInstanceOf(Success.class));
    }

    @Test
    void serverErrorIsUnknownBecauseTheSupplierMayHaveProcessedIt() {
        String reference = UUID.randomUUID().toString();

        assertThat(gateway.submit(request(reference, "081200000055"))).isEqualTo(new Unknown("SUPPLIER_HTTP_500"));
        assertThat(gateway.inquiry(reference)).isInstanceOf(Success.class);
    }

    @Test
    void duplicateSubmitReturnsTheOriginalOutcome() {
        String reference = UUID.randomUUID().toString();
        SupplierResult first = gateway.submit(request(reference, "081200000000"));

        assertThat(gateway.submit(request(reference, "081200000000"))).isEqualTo(first);
    }

    @Test
    void inquiryForAReferenceTheSupplierNeverReceivedIsFailed() {
        assertThat(gateway.inquiry(UUID.randomUUID().toString())).isEqualTo(new Failed("NOT_RECEIVED_BY_SUPPLIER"));
    }

    @Test
    void unreachableSupplierFailsSubmitButLeavesInquiryUnknown() throws IOException {
        SupplierGateway unreachable = new HttpSupplierGateway("DOWN",
                new SupplierProperties.Endpoint(URI.create("http://localhost:" + closedPort()), TIMEOUT, TIMEOUT));

        assertThat(unreachable.submit(request(UUID.randomUUID().toString(), "081200000000")))
                .isEqualTo(new Failed("SUPPLIER_UNREACHABLE"));
        assertThat(unreachable.inquiry(UUID.randomUUID().toString())).isInstanceOf(Unknown.class);
    }

    private SupplierResult submit(String destination) {
        return gateway.submit(request(UUID.randomUUID().toString(), destination));
    }

    private static SupplierRequest request(String reference, String destination) {
        return new SupplierRequest(reference, "SKU-10", destination, 10_000);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
