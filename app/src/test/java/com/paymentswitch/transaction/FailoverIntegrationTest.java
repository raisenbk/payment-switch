package com.paymentswitch.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.supplier.SupplierCircuitBreakers;
import com.paymentswitch.support.TestCatalog.Mapping;
import com.paymentswitch.support.TestCatalog.TestProduct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Routing and failover against the real mock-supplier (ADR-008). Suppliers in the test config:
 * MOCKA and MOCKB behave normally, MOCKOOS is always out of stock, DEADSUP refuses connections.
 * Failover happens only after a definitive failure, never after an ambiguous one.
 */
class FailoverIntegrationTest extends AbstractTransactionIntegrationTest {

    @Autowired
    SupplierCircuitBreakers breakers;

    @AfterEach
    void closeCircuits() {
        breakers.forSupplier("MOCKA").reset();
    }

    @Test
    void outOfStockAtTheFirstSupplierFailsOverToTheNext() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("MOCKOOS", 10_000, 1), Mapping.of("MOCKB", 10_100, 2));

        UUID id = idOf(create("081234560000", twoSuppliers));
        String done = awaitStatus(id, "SUCCESS");

        assertThat(JsonPath.<String>read(done, "$.supplierRef")).startsWith("MS-");
        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->PROCESSING", "PROCESSING->SUCCESS");
        assertThat(eventPayload(id, "PROCESSING")).contains(
                "\"from\": \"MOCKOOS\"", "\"to\": \"MOCKB\"", "\"code\": \"OUT_OF_STOCK\"");
        // Booked against the supplier that delivered, at its cost.
        assertThat(ledgerEntries(id, "CAPTURE")).containsExactlyInAnyOrder(
                "MERCHANT_HOLD DEBIT 10500", "SUPPLIER_PAYABLE CREDIT 10100", "REVENUE CREDIT 400");
        assertThat(supplierHas("MOCKB", id)).isTrue();
    }

    @Test
    void unreachableSupplierFailsOverToTheNext() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("DEADSUP", 9_900, 1), Mapping.of("MOCKA", 10_000, 2));

        UUID id = idOf(create("081234560000", twoSuppliers));
        awaitStatus(id, "SUCCESS");

        assertThat(eventPayload(id, "PROCESSING")).contains(
                "\"from\": \"DEADSUP\"", "\"to\": \"MOCKA\"", "\"code\": \"SUPPLIER_UNREACHABLE\"");
        assertThat(supplierHas("MOCKA", id)).isTrue();
    }

    @Test
    void timeoutNeverFailsOver() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("MOCKA", 10_000, 1), Mapping.of("MOCKB", 10_000, 2));

        UUID id = idOf(create("081234560022", twoSuppliers)); // MOCKA times out (and tops up later)
        awaitStatus(id, "SUCCESS");

        // Resolved by inquiry at the same supplier; trying MOCKB could have topped up twice.
        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->UNKNOWN", "UNKNOWN->SUCCESS");
        assertThat(supplierHas("MOCKB", id)).isFalse();
    }

    @Test
    void customerErrorDoesNotFailOver() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("MOCKA", 10_000, 1), Mapping.of("MOCKB", 10_000, 2));

        UUID id = idOf(create("081234560011", twoSuppliers)); // invalid number: every supplier would say so
        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo("INVALID_DESTINATION");
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->FAILED");
        assertThat(supplierHas("MOCKB", id)).isFalse();
    }

    @Test
    void whenEverySupplierFailsDefinitivelyTheHoldIsReleased() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("MOCKOOS", 10_000, 1), Mapping.of("DEADSUP", 10_000, 2));

        UUID id = idOf(create("081234560000", twoSuppliers));
        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo("SUPPLIER_UNREACHABLE");
        assertThat(transitions(id)).containsExactly(
                "->PENDING", "PENDING->PROCESSING", "PROCESSING->PROCESSING", "PROCESSING->FAILED");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
    }

    @Test
    void openCircuitIsRoutedAround() {
        TestProduct twoSuppliers = catalog.productSuppliedBy(PRICE,
                Mapping.of("MOCKA", 10_000, 1), Mapping.of("MOCKB", 10_000, 2));
        breakers.forSupplier("MOCKA").transitionToOpenState();

        UUID id = idOf(create("081234560000", twoSuppliers));
        awaitStatus(id, "SUCCESS");

        // Routing skipped MOCKA up front: no attempt there, so no failover event either.
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->SUCCESS");
        assertThat(eventPayload(id, "PROCESSING")).contains("\"supplier\": \"MOCKB\"");
        assertThat(supplierHas("MOCKA", id)).isFalse();
    }

    @Test
    void noSupplierAvailableFailsWithoutSendingAnything() {
        breakers.forSupplier("MOCKA").transitionToOpenState();

        UUID id = idOf(create("081234560000")); // the base product is supplied by MOCKA only
        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo("NO_ROUTE");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
        assertThat(supplierHas("MOCKA", id)).isFalse();
    }

    private HttpResponse<String> create(String destination, TestProduct product) {
        return create(destination, product.code(), "ref-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    /** Whether the mock supplier ever received a submit for this transaction. */
    private boolean supplierHas(String supplierCode, UUID transactionId) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .send(HttpRequest.newBuilder(URI.create(mockSupplierUrl(supplierCode) + "/transactions/"
                            + transactionId)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
