package com.paymentswitch.webhook;

import com.paymentswitch.merchant.MerchantWebhooks;
import com.paymentswitch.merchant.MerchantWebhooks.WebhookTarget;
import com.paymentswitch.webhook.WebhookRepository.DueWebhook;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers due webhooks: signed POST of the stored body, one attempt recorded per try, 2xx means
 * delivered, anything else (other status, timeout, connection error) retries with backoff + jitter
 * until {@code max-attempts}, then the webhook is dead-lettered (status DEAD) for an operator to
 * re-drive. Delivery is at-least-once (ADR-007).
 */
@Component
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookRepository repository;
    private final MerchantWebhooks merchants;
    private final WebhookProperties properties;
    private final WebhookRetryPolicy retryPolicy;
    private final Clock clock;
    private final HttpClient client;

    WebhookDispatcher(WebhookRepository repository, MerchantWebhooks merchants, WebhookProperties properties,
            Clock clock) {
        this.repository = repository;
        this.merchants = merchants;
        this.properties = properties;
        this.retryPolicy = WebhookRetryPolicy.of(properties);
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${payment-switch.webhooks.poll-interval:1s}")
    void poll() {
        List<DueWebhook> due = repository.claimDue(properties.batchSize(), properties.lease());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(webhook -> executor.execute(() -> deliver(webhook)));
        }
    }

    private void deliver(DueWebhook webhook) {
        MDC.put("merchantId", webhook.merchantId().toString());
        try {
            Optional<WebhookTarget> target = merchants.target(webhook.merchantId());
            Attempt attempt = target.map(t -> send(t, webhook)).orElseGet(() -> Attempt.failed("NO_WEBHOOK_URL", 0));
            repository.recordDelivery(webhook.id(), target.map(WebhookTarget::url).orElse(null),
                    attempt.statusCode(), attempt.durationMs(), attempt.error());
            if (attempt.delivered()) {
                repository.markDelivered(webhook.id());
            } else if (retryPolicy.exhausted(webhook.attempts())) {
                repository.markDead(webhook.id());
                log.warn("Webhook {} dead-lettered after {} attempts (last: {})",
                        webhook.id(), webhook.attempts(), attempt);
            } else {
                Duration delay = retryPolicy.delayAfter(webhook.attempts());
                repository.scheduleRetry(webhook.id(), delay);
                log.info("Webhook {} attempt {} failed ({}), retrying in {}", webhook.id(), webhook.attempts(),
                        attempt, delay);
            }
        } catch (RuntimeException e) {
            // The lease expires and the webhook is tried again.
            log.error("Delivering webhook {} failed unexpectedly", webhook.id(), e);
        } finally {
            MDC.remove("merchantId");
        }
    }

    private Attempt send(WebhookTarget target, DueWebhook webhook) {
        long timestamp = clock.instant().getEpochSecond();
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(properties.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Id", webhook.id().toString())
                    .header("X-Webhook-Timestamp", Long.toString(timestamp))
                    .header("X-Webhook-Signature", WebhookSignature.sign(target.secret(), timestamp, webhook.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(webhook.payload()))
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return new Attempt(status, elapsedMs(started), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.failed(e.toString(), elapsedMs(started));
        } catch (Exception e) {
            return Attempt.failed(e.toString(), elapsedMs(started));
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private record Attempt(Integer statusCode, long durationMs, String error) {

        static Attempt failed(String error, long durationMs) {
            return new Attempt(null, durationMs, error);
        }

        boolean delivered() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }
}
