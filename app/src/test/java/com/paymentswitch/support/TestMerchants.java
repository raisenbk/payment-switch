package com.paymentswitch.support;

import com.paymentswitch.common.crypto.SecretCipher;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates isolated merchants with API credentials directly in the database. */
@TestComponent
public class TestMerchants {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public TestMerchants(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public TestMerchant create() {
        UUID merchantId = UUID.randomUUID();
        String apiKey = "pk_test_" + UUID.randomUUID().toString().replace("-", "");
        String secret = "sk_test_" + UUID.randomUUID();

        jdbc.sql("INSERT INTO merchants (id, name) VALUES (:id, :name)")
                .param("id", merchantId)
                .param("name", "Test Merchant " + merchantId.toString().substring(0, 8))
                .update();
        jdbc.sql("INSERT INTO api_credentials (merchant_id, api_key, secret_ciphertext) VALUES (:m, :k, :s)")
                .param("m", merchantId)
                .param("k", apiKey)
                .param("s", cipher.encrypt(secret))
                .update();
        return new TestMerchant(merchantId, apiKey, secret);
    }

    public void suspend(TestMerchant merchant) {
        jdbc.sql("UPDATE merchants SET status = 'SUSPENDED' WHERE id = :id").param("id", merchant.id()).update();
    }

    public void deactivateCredentials(TestMerchant merchant) {
        jdbc.sql("UPDATE api_credentials SET is_active = FALSE WHERE merchant_id = :id")
                .param("id", merchant.id())
                .update();
    }

    public record TestMerchant(UUID id, String apiKey, String secret) {
    }
}
