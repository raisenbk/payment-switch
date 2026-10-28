package com.paymentswitch;

import com.paymentswitch.support.IdempotencyProbeController;
import com.paymentswitch.support.LedgerAssertions;
import com.paymentswitch.support.TestCatalog;
import com.paymentswitch.support.TestMerchants;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for integration tests. Containers are started once per JVM and shared:
 * <ul>
 *   <li>PostgreSQL, wired to Spring through {@link ServiceConnection};</li>
 *   <li>the real mock-supplier application (its packaged jar from the {@code mock-supplier} module),
 *       configured as supplier {@code MOCKA}.</li>
 * </ul>
 * The app listens on a random port so tests can send real, signed HTTP requests.
 * Tests must isolate their data (e.g. a fresh merchant per test) instead of relying on a fresh database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestMerchants.class, TestCatalog.class, IdempotencyProbeController.class, LedgerAssertions.class})
public abstract class AbstractIntegrationTest {

    /** Slow mock-supplier behaviours take this long; the app's read timeout in tests is 1s. */
    protected static final Duration SUPPLIER_DELAY = Duration.ofSeconds(2);

    private static final int MOCK_SUPPLIER_PORT = 8090;

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final GenericContainer<?> MOCK_SUPPLIER = new GenericContainer<>("eclipse-temurin:21-jre")
            .withCopyFileToContainer(MountableFile.forHostPath(mockSupplierJar()), "/opt/mock-supplier.jar")
            .withCommand("java", "-jar", "/opt/mock-supplier.jar")
            .withEnv("MOCK_SUPPLIER_DELAY", SUPPLIER_DELAY.toMillis() + "ms")
            .withExposedPorts(MOCK_SUPPLIER_PORT)
            .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofSeconds(90)));

    static {
        Startables.deepStart(POSTGRES, MOCK_SUPPLIER).join();
    }

    @DynamicPropertySource
    static void supplierEndpoints(DynamicPropertyRegistry registry) {
        registry.add("payment-switch.suppliers.mocka.base-url", () -> mockSupplierUrl("MOCKA"));
    }

    @LocalServerPort
    int port;

    protected URI baseUri() {
        return URI.create("http://localhost:" + port);
    }

    protected static String mockSupplierUrl(String supplierCode) {
        return "http://" + MOCK_SUPPLIER.getHost() + ":" + MOCK_SUPPLIER.getMappedPort(MOCK_SUPPLIER_PORT)
                + "/suppliers/" + supplierCode;
    }

    private static Path mockSupplierJar() {
        Path jar = Path.of("..", "mock-supplier", "target", "mock-supplier.jar").toAbsolutePath().normalize();
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Missing " + jar + ". Build from the repository root first: ./mvnw verify");
        }
        return jar;
    }
}
