package com.paymentswitch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

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
    void unauthenticatedApiRequestReturnsConsistentErrorJson() {
        MvcTestResult result = mvc.get().uri("/v1/does-not-exist")
                .header("X-Request-Id", "test-trace-0001")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).hasHeader("X-Request-Id", "test-trace-0001");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.traceId").isEqualTo("test-trace-0001");
        assertThat(result).bodyJson().extractingPath("$.message").isNotNull();
    }
}
