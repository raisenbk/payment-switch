package com.paymentswitch.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pollInterval   pause between polls for due webhooks
 * @param batchSize      webhooks claimed per poll
 * @param lease          how long a claimed webhook is hidden from other dispatchers; must exceed
 *                       connect + read timeout
 * @param maxAttempts    attempts before the webhook is dead-lettered
 * @param initialDelay   delay after the first failed attempt, doubled per attempt (with jitter)
 * @param maxDelay       upper bound for the delay between attempts
 * @param connectTimeout TCP connect timeout to the merchant endpoint
 * @param readTimeout    how long the merchant endpoint may take to answer
 */
@ConfigurationProperties("payment-switch.webhooks")
record WebhookProperties(
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("30s") Duration initialDelay,
        @DefaultValue("6h") Duration maxDelay,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
