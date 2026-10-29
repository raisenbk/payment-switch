package com.paymentswitch.webhook;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter. The delay after attempt {@code n} is drawn uniformly from the
 * upper half of {@code min(initial * 2^(n-1), max)}, so retries of many webhooks that failed
 * together (merchant outage) spread out instead of arriving in synchronized waves.
 */
record WebhookRetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts) {

    static WebhookRetryPolicy of(WebhookProperties properties) {
        return new WebhookRetryPolicy(properties.initialDelay(), properties.maxDelay(), properties.maxAttempts());
    }

    Duration delayAfter(int attempt) {
        // At most 2^30 doublings: no overflow for any initial delay below ~97 days.
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);
        long cappedMs = Math.min(initialDelay.toMillis() << doublings, maxDelay.toMillis());
        long half = cappedMs / 2;
        return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(cappedMs - half + 1));
    }

    boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
