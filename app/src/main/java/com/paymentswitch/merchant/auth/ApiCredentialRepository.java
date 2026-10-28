package com.paymentswitch.merchant.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ApiCredentialRepository {

    private final JdbcClient jdbc;

    ApiCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Only credentials that are active and belong to an active merchant can authenticate. */
    Optional<ActiveCredential> findActive(String apiKey) {
        return jdbc.sql("""
                        SELECT c.merchant_id, c.secret_ciphertext
                          FROM api_credentials c
                          JOIN merchants m ON m.id = c.merchant_id
                         WHERE c.api_key = :apiKey
                           AND c.is_active
                           AND m.status = 'ACTIVE'""")
                .param("apiKey", apiKey)
                .query((rs, row) -> new ActiveCredential(
                        rs.getObject("merchant_id", UUID.class), rs.getString("secret_ciphertext")))
                .optional();
    }

    record ActiveCredential(UUID merchantId, String secretCiphertext) {
    }
}
