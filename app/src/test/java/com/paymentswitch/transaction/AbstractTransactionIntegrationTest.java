package com.paymentswitch.transaction;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared setup for transaction tests: a fresh merchant with {@link #STARTING_BALANCE}, a product
 * supplied by the mock-supplier container, signed API helpers and ledger/history queries.
 * Every test ends by checking the global ledger invariants.
 */
abstract class AbstractTransactionIntegrationTest extends AbstractIntegrationTest {

    static final long PRICE = 10_500;
    static final long SUPPLIER_COST = 10_000;
    static final long STARTING_BALANCE = 100_000;

    /** Long enough for the slowest scenario: inquiries until the max window, then escalation. */
    static final Duration MAX_WAIT = Duration.ofSeconds(20);

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
    PlatformTransactionManager transactionManager;

    TestMerchant merchant;
    TestProduct product;

    @BeforeEach
    void createMerchantAndProduct() {
        merchant = merchants.create();
        ledger.topUp(merchant.id(), idr(STARTING_BALANCE), "seed");
        product = catalog.productSuppliedByMockA(PRICE, SUPPLIER_COST);
    }

    @AfterEach
    void booksStayBalanced() {
        invariants.assertGlobalInvariants();
    }

    HttpResponse<String> create(String destination) {
        return create(destination, product.code(), "ref-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    HttpResponse<String> create(String destination, String productCode, String merchantRef, String key) {
        return send(signed(baseUri(), merchant, "POST", "/v1/transactions", body(merchantRef, productCode, destination))
                .header("Idempotency-Key", key)
                .build());
    }

    static String body(String merchantRef, String productCode, String destination) {
        return """
                {"merchantRef":"%s","productCode":"%s","destination":"%s"}"""
                .formatted(merchantRef, productCode, destination);
    }

    /** Polls the API until the transaction has {@code status}; returns the last response body. */
    String awaitStatus(UUID id, String status) {
        String[] last = new String[1];
        await().atMost(MAX_WAIT).untilAsserted(() -> {
            HttpResponse<String> response = send(signed(baseUri(), merchant, "GET", "/v1/transactions/" + id, "")
                    .build());
            last[0] = response.body();
            assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo(status);
        });
        return last[0];
    }

    Balance balance() {
        return ledger.getBalance(merchant.id(), "IDR");
    }

    List<String> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT COALESCE(from_status, '') || '->' || to_status
                          FROM transaction_events WHERE transaction_id = :id ORDER BY id""")
                .param("id", id)
                .query(String.class)
                .list();
    }

    String eventPayload(UUID id, String toStatus) {
        return jdbc.sql("""
                        SELECT payload::text FROM transaction_events
                         WHERE transaction_id = :id AND to_status = :to ORDER BY id DESC LIMIT 1""")
                .param("id", id)
                .param("to", toStatus)
                .query(String.class)
                .single();
    }

    List<String> ledgerEntries(UUID transactionId, String kind) {
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

    TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    static UUID idOf(HttpResponse<String> response) {
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    static Money idr(long amount) {
        return Money.of(amount, "IDR");
    }
}
