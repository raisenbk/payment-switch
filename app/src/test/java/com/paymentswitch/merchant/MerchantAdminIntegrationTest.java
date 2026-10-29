package com.paymentswitch.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.paymentswitch.AbstractIntegrationTest;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestMerchants;
import com.paymentswitch.support.TestMerchants.TestMerchant;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestMerchants merchants;

    @Autowired
    LedgerAssertions invariants;

    @Test
    void topUpCreditsTheBalanceOncePerReference() throws Exception {
        TestMerchant merchant = merchants.create();
        String body = """
                {"amount": 250000, "currency": "IDR", "reference": "bank-trf-0001"}""";

        HttpResponse<String> first = topUp(merchant.id(), body);
        HttpResponse<String> retry = topUp(merchant.id(), body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(first.body(), "$.balance.available.amount")).isEqualTo(250_000);
        assertThat(JsonPath.<String>read(retry.body(), "$.journalId"))
                .isEqualTo(JsonPath.read(first.body(), "$.journalId"));
        assertThat(JsonPath.<Integer>read(retry.body(), "$.balance.available.amount")).isEqualTo(250_000);
        invariants.assertGlobalInvariants();
    }

    @Test
    void rejectsUnknownMerchantsAndInvalidAmounts() throws Exception {
        assertThat(topUp(UUID.randomUUID(), """
                {"amount": 1000, "currency": "IDR", "reference": "r1"}""").statusCode()).isEqualTo(404);
        assertThat(topUp(merchants.create().id(), """
                {"amount": -5, "currency": "IDR", "reference": "r1"}""").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> topUp(UUID merchantId, String body) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(baseUri().resolve("/admin/merchants/" + merchantId + "/top-ups"))
                        .header("X-Admin-Token", "test-admin-token-not-secret")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
