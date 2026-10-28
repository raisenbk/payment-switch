package com.paymentswitch.transaction;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.Balance;
import com.paymentswitch.ledger.LedgerService;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestCatalog;
import com.paymentswitch.support.TestCatalog.TestProduct;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import com.paymentswitch.transaction.TransactionRepository.StatusChange;
import com.paymentswitch.transaction.TransactionStateMachine.ConcurrentTransitionException;
import com.paymentswitch.transaction.TransactionStateMachine.IllegalTransitionException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End to end: signed HTTP request → transaction + hold → async processing → real mock-supplier
 * (in a container) → final status, ledger effect and complete event history.
 */
class TransactionFlowIntegrationTest extends AbstractIntegrationTest {

    private static final long PRICE = 10_500;
    private static final long SUPPLIER_COST = 10_000;
    private static final long STARTING_BALANCE = 100_000;

    @Autowired
    TestMerchants merchants;

    @Autowired
    TestCatalog catalog;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerAssertions invariants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionStateMachine stateMachine;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestMerchant merchant;
    TestProduct product;

    @BeforeEach
    void setUp() {
        merchant = merchants.create();
        ledger.topUp(merchant.id(), idr(STARTING_BALANCE), "seed");
        product = catalog.productSuppliedByMockA(PRICE, SUPPLIER_COST);
    }

    @AfterEach
    void booksStayBalanced() {
        invariants.assertGlobalInvariants();
    }

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

    @ParameterizedTest(name = "destination {0} -> UNKNOWN ({1})")
    @CsvSource({
            "081234560022, READ_TIMEOUT",
            "081234560033, READ_TIMEOUT",
            "081234560044, SUPPLIER_PENDING",
            "081234560055, SUPPLIER_HTTP_500"})
    void ambiguousResultIsUnknownAndKeepsTheHold(String destination, String reason) {
        UUID id = idOf(create(destination));

        awaitStatus(id, "UNKNOWN");

        assertThat(balance()).isEqualTo(new Balance(idr(STARTING_BALANCE - PRICE), idr(PRICE)));
        assertThat(transitions(id)).containsExactly("->PENDING", "PENDING->PROCESSING", "PROCESSING->UNKNOWN");
        assertThat(lastEventPayload(id)).contains("\"reason\": \"" + reason + "\"");
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
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> stateMachine.transition(
                id, TransactionStatus.SUCCESS, TransactionStatus.FAILED, StatusChange.NONE, Map.of())))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> stateMachine.transition(
                id, TransactionStatus.PROCESSING, TransactionStatus.FAILED, StatusChange.NONE, Map.of())))
                .isInstanceOf(ConcurrentTransitionException.class);
        assertThat(transitions(id)).hasSize(3);
    }

    private HttpResponse<String> create(String destination) {
        return create(destination, product.code(), "ref-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    private HttpResponse<String> create(String destination, String productCode, String merchantRef, String key) {
        return send(signed(baseUri(), merchant, "POST", "/v1/transactions", body(merchantRef, productCode, destination))
                .header("Idempotency-Key", key)
                .build());
    }

    private static String body(String merchantRef, String productCode, String destination) {
        return """
                {"merchantRef":"%s","productCode":"%s","destination":"%s"}"""
                .formatted(merchantRef, productCode, destination);
    }

    private String awaitStatus(UUID id, String status) {
        String[] last = new String[1];
        await().atMost(SUPPLIER_DELAY.plus(Duration.ofSeconds(8))).untilAsserted(() -> {
            HttpResponse<String> response = send(signed(baseUri(), merchant, "GET", "/v1/transactions/" + id, "")
                    .build());
            last[0] = response.body();
            assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo(status);
        });
        return last[0];
    }

    private Balance balance() {
        return ledger.getBalance(merchant.id(), "IDR");
    }

    private List<String> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT COALESCE(from_status, '') || '->' || to_status
                          FROM transaction_events WHERE transaction_id = :id ORDER BY id""")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private String lastEventPayload(UUID id) {
        return jdbc.sql("""
                        SELECT payload::text FROM transaction_events
                         WHERE transaction_id = :id ORDER BY id DESC LIMIT 1""")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<String> ledgerEntries(UUID transactionId, String kind) {
        return jdbc.sql("""
                        SELECT a.type || ' ' || e.direction || ' ' || e.amount
                          FROM ledger_entries e
                          JOIN ledger_journals j ON j.id = e.journal_id
                          JOIN ledger_accounts a ON a.id = e.account_id
                         WHERE j.transaction_id = :tx AND j.kind = :kind""")
                .param("tx", transactionId)
                .param("kind", kind)
                .query(String.class)
                .list();
    }

    private static UUID idOf(HttpResponse<String> response) {
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private static Money idr(long amount) {
        return Money.of(amount, "IDR");
    }
}
