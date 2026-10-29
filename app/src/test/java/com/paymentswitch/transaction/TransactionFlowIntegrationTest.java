package com.paymentswitch.transaction;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.support.TestCatalog.TestProduct;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import com.paymentswitch.transaction.TransactionStateMachine.ConcurrentTransitionException;
import com.paymentswitch.transaction.TransactionStateMachine.IllegalTransitionException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End to end: signed HTTP request → transaction + hold + outbox event → outbox poller → real
 * mock-supplier (in a container) → final status, ledger effect and complete event history.
 * Ambiguous results are covered by {@link UnknownResolutionIntegrationTest}.
 */
class TransactionFlowIntegrationTest extends AbstractTransactionIntegrationTest {

    @Autowired
    TransactionStateMachine stateMachine;

    @Test
    void successCapturesTheHold() {
        HttpResponse<String> accepted = create("081234560000");

        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(JsonPath.<String>read(accepted.body(), "$.status")).isEqualTo("PENDING");
        UUID id = idOf(accepted);

        String done = awaitStatus(id, "SUCCESS");

        assertThat(JsonPath.<String>read(done, "$.supplierRef")).startsWith("MS-");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(0)));
        assertThat(ledgerEntries(id, "CAPTURE")).containsExactlyInAnyOrder(
                "MERCHANT_HOLD DEBIT 10500", "SUPPLIER_PAYABLE CREDIT 10000", "REVENUE CREDIT 500");
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->SUCCESS");
    }

    @ParameterizedTest(name = "destination {0} -> FAILED {1}")
    @CsvSource({
            "081234560011, INVALID_DESTINATION",
            "081234560066, INVALID_SKU"})
    void definitiveFailureReleasesTheHold(String destination, String failureCode) {
        UUID id = idOf(create(destination));

        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo(failureCode);
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->FAILED");
    }

    @Test
    void productWithoutSupplierFailsAndReleasesTheHold() {
        TestProduct orphan = catalog.productWithoutSupplier(PRICE);
        UUID id = idOf(create("081234560000", orphan.code(), "ref-" + UUID.randomUUID(), UUID.randomUUID().toString()));

        String done = awaitStatus(id, "FAILED");

        assertThat(JsonPath.<String>read(done, "$.failureCode")).isEqualTo("NO_ROUTE");
        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE), idr(0)));
    }

    @Test
    void insufficientBalanceRejectsWithoutCreatingAnything() {
        TestMerchant poor = merchants.create();
        ledger.topUp(poor.id(), idr(PRICE - 1), "seed");
        String merchantRef = "ref-" + UUID.randomUUID();

        HttpResponse<String> response = send(signed(baseUri(), poor, "POST", "/v1/transactions",
                body(merchantRef, product.code(), "081234560000"))
                .header("Idempotency-Key", "k1").build());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE merchant_id = :m")
                .param("m", poor.id()).query(Long.class).single()).isZero();
    }

    @Test
    void unknownProductIsRejected() {
        HttpResponse<String> response = create("081234560000", "NO-SUCH-PRODUCT", "ref-x", "k-x");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("PRODUCT_NOT_AVAILABLE");
    }

    @Test
    void invalidDestinationFailsValidation() {
        HttpResponse<String> response = create("12ab", product.code(), "ref-y", "k-y");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.details[0].field")).isEqualTo("destination");
    }

    @Test
    void sameMerchantRefUnderAnotherKeyIsAConflict() {
        create("081234560000", product.code(), "order-1", "key-a");

        HttpResponse<String> second = create("081234560000", product.code(), "order-1", "key-b");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(second.body(), "$.code")).isEqualTo("DUPLICATE_MERCHANT_REF");
    }

    @Test
    void retriedRequestReturnsTheSameTransaction() {
        HttpResponse<String> first = create("081234560000", product.code(), "order-2", "key-c");
        HttpResponse<String> retry = create("081234560000", product.code(), "order-2", "key-c");

        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        awaitStatus(idOf(first), "SUCCESS");
        assertThat(balance().available()).isEqualTo(idr(STARTING_BALANCE - PRICE));
    }

    @Test
    void merchantsCannotSeeEachOthersTransactions() {
        UUID id = idOf(create("081234560000"));
        TestMerchant other = merchants.create();

        HttpResponse<String> response = send(signed(baseUri(), other, "GET", "/v1/transactions/" + id, "").build());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void illegalOrStaleTransitionsAreRejected() {
        UUID id = idOf(create("081234560000"));
        awaitStatus(id, "SUCCESS");
        var tx = transaction();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> stateMachine.transition(
                id, TransactionStatus.SUCCESS, TransactionStatus.FAILED, StatusChange.NONE, Map.of())))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> stateMachine.transition(
                id, TransactionStatus.PROCESSING, TransactionStatus.FAILED, StatusChange.NONE, Map.of())))
                .isInstanceOf(ConcurrentTransitionException.class);
        assertThat(transitions(id)).hasSize(3);
    }
}
