package com.paymentswitch.mocksupplier;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in merchant endpoint for the local demo: accepts webhooks under {@code /webhooks/{name}}
 * and lists the last 50 per name, so the switch's webhooks can be seen without running anything else.
 * It does not verify signatures (it does not know the merchant's secret).
 */
@RestController
class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);
    private static final int KEEP = 50;

    private final Map<String, Deque<Received>> received = new ConcurrentHashMap<>();

    record Received(Instant receivedAt, String webhookId, String timestamp, String signature, String body) {
    }

    @PostMapping("/webhooks/{name}")
    ResponseEntity<Void> receive(@PathVariable String name,
            @RequestHeader(value = "X-Webhook-Id", required = false) String webhookId,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String body) {
        log.info("Webhook for {}: id={} body={}", name, webhookId, body);
        Deque<Received> list = received.computeIfAbsent(name, n -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(new Received(Instant.now(), webhookId, timestamp, signature, body));
            while (list.size() > KEEP) {
                list.removeLast();
            }
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/webhooks/{name}")
    List<Received> list(@PathVariable String name) {
        Deque<Received> list = received.getOrDefault(name, new ArrayDeque<>());
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
