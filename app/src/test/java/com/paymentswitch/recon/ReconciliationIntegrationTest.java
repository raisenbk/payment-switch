package com.paymentswitch.recon;

import static com.paymentswitch.support.SignedRequests.send;
import static com.paymentswitch.support.SignedRequests.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.common.money.Money;
import com.paymentswitch.ledger.LedgerService;
import com.paymentswitch.support.TestCatalog;
import com.paymentswitch.support.TestCatalog.Mapping;
import com.paymentswitch.support.TestCatalog.TestProduct;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Settlement reconciliation through the admin API: a file with one of every discrepancy type
 * against transactions seeded directly, then real settlement files produced by the mock supplier.
 */
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token-not-secret";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 20);
    private static final String HEADER = "reference,supplier_ref,sku,destination,amount,currency,completed_at";

    @Autowired
    TestMerchants merchants;

    @Autowired
    TestCatalog catalog;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    String supplier;
    long supplierId;
    TestMerchant merchant;
    TestProduct product;

    @BeforeEach
    void setUp() {
        supplier = "RECON" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        supplierId = catalog.supplierId(supplier);
        merchant = merchants.create();
        product = catalog.productWithoutSupplier(10_500);
    }

    @Test
    void classifiesEveryDiscrepancyType() throws Exception {
        UUID matched = seed("SUCCESS", 10_000, BUSINESS_DATE);
        UUID billedTooMuch = seed("SUCCESS", 10_000, BUSINESS_DATE);
        UUID notBilled = seed("SUCCESS", 10_000, BUSINESS_DATE);
        UUID weFailed = seed("FAILED", 10_000, BUSINESS_DATE);
        UUID otherDay = seed("SUCCESS", 10_000, BUSINESS_DATE.plusDays(1));
        UUID ghost = UUID.randomUUID();
        String csv = file(
                line(matched, 10_000),
                line(billedTooMuch, 10_100),
                line(weFailed, 10_000),
                line(ghost, 10_000),
                "EXT-123,MS-EXT,SKU,081200000000,10000,IDR,2026-10-20T10:00:00Z",
                line(matched, 10_000));

        HttpResponse<String> response = importFile(supplier, csv);

        assertThat(response.statusCode()).isEqualTo(201);
        String body = response.body();
        assertThat(JsonPath.<Map<String, Integer>>read(body, "$.summary")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "MATCHED", 1, "AMOUNT_DIFF", 1, "MISSING_INTERNAL", 3, "MISSING_SUPPLIER", 1, "DUPLICATE", 1));
        assertThat(JsonPath.<Integer>read(body, "$.file.lineCount")).isEqualTo(6);
        assertThat(discrepancy(body, billedTooMuch, "AMOUNT_DIFF")).containsEntry("diffAmount", 100)
                .containsEntry("internalAmount", 10_000).containsEntry("supplierAmount", 10_100);
        assertThat(discrepancy(body, notBilled, "MISSING_SUPPLIER")).containsEntry("internalStatus", "SUCCESS");
        assertThat(discrepancy(body, weFailed, "MISSING_INTERNAL")).containsEntry("internalStatus", "FAILED");
        assertThat(discrepancy(body, matched, "DUPLICATE")).containsEntry("lineNo", 7);
        assertThat(JsonPath.<List<String>>read(body, "$.discrepancies[?(@.matchStatus == 'MISSING_INTERNAL')].reference"))
                .containsExactlyInAnyOrder(weFailed.toString(), ghost.toString(), "EXT-123");
        // Completed on another business day: belongs to the next file, not a discrepancy here.
        assertThat(JsonPath.<List<String>>read(body, "$.discrepancies[*].transactionId"))
                .doesNotContain(otherDay.toString());
    }

    @Test
    void reimportingTheSameFileIsANoOp() throws Exception {
        UUID matched = seed("SUCCESS", 10_000, BUSINESS_DATE);
        String csv = file(line(matched, 10_000));
        HttpResponse<String> first = importFile(supplier, csv);
        long fileId = JsonPath.<Number>read(first.body(), "$.file.id").longValue();

        HttpResponse<String> second = importFile(supplier, csv);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(second.body(), "$.alreadyImported")).isTrue();
        assertThat(JsonPath.<Number>read(second.body(), "$.file.id").longValue()).isEqualTo(fileId);
        assertThat(jdbc.sql("SELECT count(*) FROM recon_results WHERE settlement_file_id = :id")
                .param("id", fileId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void discrepanciesExportAsCsv() throws Exception {
        UUID billedTooMuch = seed("SUCCESS", 10_000, BUSINESS_DATE);
        UUID notBilled = seed("SUCCESS", 10_000, BUSINESS_DATE);
        HttpResponse<String> imported = importFile(supplier, file(line(billedTooMuch, 10_100)));
        long fileId = JsonPath.<Number>read(imported.body(), "$.file.id").longValue();

        HttpResponse<String> csv = admin("GET", "/admin/recon/settlements/" + fileId + "/discrepancies.csv", null);

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
        assertThat(csv.body().lines().toList()).containsExactly(
                "match_status,transaction_id,line_no,reference,supplier_ref,internal_status,internal_amount,"
                        + "supplier_amount,diff_amount,currency",
                "AMOUNT_DIFF," + billedTooMuch + ",2," + billedTooMuch + ",MS-" + billedTooMuch.toString().substring(0, 4)
                        + ",SUCCESS,10000,10100,100,IDR",
                "MISSING_SUPPLIER," + notBilled + ",," + notBilled + ",MS-SEED,SUCCESS,10000,,,IDR");
    }

    @Test
    void invalidFileIsRejectedAndLeavesNothingBehind() throws Exception {
        UUID matched = seed("SUCCESS", 10_000, BUSINESS_DATE);

        HttpResponse<String> badAmount = importFile(supplier,
                file(line(matched, 10_000), matched + ",MS-1,SKU,081200000000,ten,IDR,2026-10-20T10:00:00Z"));
        HttpResponse<String> badHeader = importFile(supplier, "ref,amount\n" + line(matched, 10_000) + "\n");
        HttpResponse<String> badSupplier = importFile("NO-SUCH-SUPPLIER", file(line(matched, 10_000)));

        assertThat(badAmount.statusCode()).isEqualTo(422);
        assertThat(JsonPath.<String>read(badAmount.body(), "$.code")).isEqualTo("INVALID_SETTLEMENT_FILE");
        assertThat(JsonPath.<String>read(badAmount.body(), "$.message")).contains("line 3");
        assertThat(JsonPath.<String>read(badHeader.body(), "$.message")).contains("header");
        assertThat(JsonPath.<String>read(badSupplier.body(), "$.code")).isEqualTo("UNKNOWN_SUPPLIER");
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_files WHERE supplier_id = :s")
                .param("s", supplierId).query(Long.class).single()).isZero();
    }

    @Test
    void reconcilesTheMockSuppliersOwnSettlementFiles() throws Exception {
        // MOCKB is only used by transactions that really succeeded there, so its file reconciles exactly.
        TestProduct viaMockB = catalog.productSuppliedBy(10_500, Mapping.of("MOCKB", 10_000, 1));
        TestMerchant buyer = merchants.create();
        ledger.topUp(buyer.id(), Money.of(100_000, "IDR"), "seed");
        List<UUID> ours = Stream.generate(() -> buy(buyer, viaMockB)).limit(3).toList();
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM transactions WHERE id IN (:ids) AND status = 'SUCCESS'")
                .param("ids", ours).query(Long.class).single() == 3);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        HttpResponse<String> clean = importFile("MOCKB", today, mockSettlement("MOCKB", today, false));
        Map<String, Integer> cleanSummary = JsonPath.read(clean.body(), "$.summary");
        assertThat(cleanSummary).containsEntry("AMOUNT_DIFF", 0).containsEntry("MISSING_INTERNAL", 0)
                .containsEntry("MISSING_SUPPLIER", 0).containsEntry("DUPLICATE", 0);
        assertThat(cleanSummary.get("MATCHED")).isGreaterThanOrEqualTo(3);
        assertThat(JsonPath.<Integer>read(clean.body(), "$.file.lineCount")).isEqualTo(cleanSummary.get("MATCHED"));

        HttpResponse<String> seeded = importFile("MOCKB", today, mockSettlement("MOCKB", today, true));
        assertThat(JsonPath.<Map<String, Integer>>read(seeded.body(), "$.summary")).containsEntry("AMOUNT_DIFF", 1)
                .containsEntry("MISSING_INTERNAL", 1).containsEntry("MISSING_SUPPLIER", 1).containsEntry("DUPLICATE", 1);
    }

    private UUID seed(String status, long cost, LocalDate completedOn) {
        UUID id = UUID.randomUUID();
        OffsetDateTime completedAt = completedOn.atTime(10, 0).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO transactions (id, merchant_id, merchant_ref, product_id, destination, amount, currency,
                                                  status, supplier_id, supplier_cost, supplier_ref, created_at, updated_at)
                        VALUES (:id, :merchant, :ref, :product, '081200000000', 10500, 'IDR', :status, :supplier, :cost,
                                'MS-SEED', :at, :at)""")
                .param("id", id)
                .param("merchant", merchant.id())
                .param("ref", "seed-" + id)
                .param("product", product.id())
                .param("status", status)
                .param("supplier", supplierId)
                .param("cost", cost)
                .param("at", completedAt)
                .update();
        return id;
    }

    private static String line(UUID reference, long amount) {
        return String.join(",", reference.toString(), "MS-" + reference.toString().substring(0, 4), "SKU",
                "081200000000", Long.toString(amount), "IDR", "2026-10-20T10:00:00Z");
    }

    private static String file(String... lines) {
        return HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    private static Map<String, Object> discrepancy(String body, UUID transactionId, String status) {
        List<Map<String, Object>> found = JsonPath.read(body, "$.discrepancies[?(@.transactionId == '" + transactionId
                + "' && @.matchStatus == '" + status + "')]");
        assertThat(found).as("%s for %s", status, transactionId).hasSize(1);
        return found.getFirst();
    }

    private UUID buy(TestMerchant buyer, TestProduct product) {
        HttpResponse<String> response = send(signed(baseUri(), buyer, "POST", "/v1/transactions", """
                {"merchantRef":"recon-%s","productCode":"%s","destination":"081234560000"}"""
                .formatted(UUID.randomUUID(), product.code()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .build());
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private String mockSettlement(String supplierCode, LocalDate date, boolean discrepancies) throws Exception {
        HttpResponse<String> response = client().send(HttpRequest.newBuilder(URI.create(
                        mockSupplierUrl(supplierCode) + "/settlements/" + date + "?discrepancies=" + discrepancies))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> importFile(String supplierCode, String csv) throws Exception {
        return importFile(supplierCode, BUSINESS_DATE, csv);
    }

    private HttpResponse<String> importFile(String supplierCode, LocalDate date, String csv) throws Exception {
        return admin("POST", "/admin/recon/settlements?supplier=" + supplierCode + "&businessDate=" + date, csv);
    }

    private HttpResponse<String> admin(String method, String uri, String csv) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri().resolve(uri))
                .header("X-Admin-Token", ADMIN_TOKEN);
        if (csv == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "text/csv").method(method, HttpRequest.BodyPublishers.ofString(csv));
        }
        return client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }
}
