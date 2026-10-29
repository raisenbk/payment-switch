package com.paymentswitch.merchant;

import com.paymentswitch.common.crypto.SecretCipher;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public API of the merchant module for webhook delivery: where to send and what to sign with. */
@Service
public class MerchantWebhooks {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    MerchantWebhooks(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Empty when the merchant has no callback URL or no webhook secret configured. */
    public Optional<WebhookTarget> target(UUID merchantId) {
        return jdbc.sql("""
                        SELECT callback_url, webhook_secret_ciphertext FROM merchants
                         WHERE id = :id AND callback_url IS NOT NULL AND webhook_secret_ciphertext IS NOT NULL""")
                .param("id", merchantId)
                .query((rs, row) -> new WebhookTarget(rs.getString(1), cipher.decrypt(rs.getString(2))))
                .optional();
    }

    /** {@code secret} is the clear webhook secret: never log it. */
    public record WebhookTarget(String url, String secret) {

        @Override
        public String toString() {
            return "WebhookTarget[url=" + url + ", secret=***]";
        }
    }
}
