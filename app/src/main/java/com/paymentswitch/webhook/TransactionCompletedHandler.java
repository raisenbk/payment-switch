package com.paymentswitch.webhook;

import com.paymentswitch.common.outbox.OutboxEvent;
import com.paymentswitch.common.outbox.OutboxHandler;
import com.paymentswitch.merchant.MerchantWebhooks;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a {@code transaction.completed} outbox event into a webhook for the merchant, if the
 * merchant has a callback URL. The body is fixed here, once: every retry sends the same bytes and
 * the same event id, so merchants can deduplicate (ADR-007).
 */
@Component
class TransactionCompletedHandler implements OutboxHandler {

    private final WebhookRepository repository;
    private final MerchantWebhooks merchants;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    TransactionCompletedHandler(WebhookRepository repository, MerchantWebhooks merchants, JsonMapper jsonMapper,
            Clock clock) {
        this.repository = repository;
        this.merchants = merchants;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return "transaction.completed";
    }

    @Override
    public void handle(OutboxEvent event) {
        JsonNode payload = jsonMapper.readTree(event.payload());
        UUID merchantId = UUID.fromString(payload.get("merchantId").asString());
        if (merchants.target(merchantId).isEmpty()) {
            return;
        }
        JsonNode transaction = payload.get("transaction");
        String type = switch (transaction.get("status").asString()) {
            case "SUCCESS" -> "transaction.succeeded";
            case "FAILED" -> "transaction.failed";
            default -> throw new IllegalArgumentException("Not a final status: " + transaction.get("status"));
        };

        UUID webhookId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", webhookId);
        body.put("type", type);
        body.put("createdAt", clock.instant().toString());
        body.put("data", transaction);
        repository.insertEvent(webhookId, event.id(), merchantId, type, jsonMapper.writeValueAsString(body));
    }
}
