package com.paymentswitch;

import com.paymentswitch.support.IdempotencyProbeController;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestMerchants;
import java.net.URI;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests. A single PostgreSQL container is started once per JVM and
 * shared by every subclass; Spring wires the datasource to it through {@link ServiceConnection}.
 * The app listens on a random port so tests can send real, signed HTTP requests.
 * Tests must isolate their data (e.g. a fresh merchant per test) instead of relying on a fresh database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestMerchants.class, IdempotencyProbeController.class, LedgerAssertions.class})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    int port;

    protected URI baseUri() {
        return URI.create("http://localhost:" + port);
    }
}
