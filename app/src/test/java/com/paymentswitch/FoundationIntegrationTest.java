package com.paymentswitch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class FoundationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvcTester mvc;

    @Test
    void flywayCreatesBaselineTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "merchants", "api_credentials", "products", "suppliers", "supplier_products");
    }

    @Test
    void healthEndpointReportsUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void unauthenticatedApiRequestReturnsConsistentErrorJson() throws Exception {
        HttpResponse<String> response = get("/v1/does-not-exist", "X-Request-Id", "test-request-0001");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("test-request-0001");
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isNotNull();
        // The error quotes the OpenTelemetry trace id, which is also returned as X-Trace-Id.
        String traceId = JsonPath.read(response.body(), "$.traceId");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(response.headers().firstValue("X-Trace-Id")).hasValue(traceId);
    }

    @Test
    void incomingW3cTraceContextIsContinued() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> response = get("/v1/does-not-exist", "traceparent",
                "00-" + traceId + "-00f067aa0ba902b7-01");

        assertThat(response.headers().firstValue("X-Trace-Id")).hasValue(traceId);
        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).isEqualTo(traceId);
    }

    @Test
    void prometheusMetricsAreExposed() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus", "Accept", "text/plain");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("paymentswitch_transactions_open", "paymentswitch_outbox_lag",
                "paymentswitch_webhooks_dead");
    }

    private HttpResponse<String> get(String path, String header, String value) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(baseUri().resolve(path)).header(header, value).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
