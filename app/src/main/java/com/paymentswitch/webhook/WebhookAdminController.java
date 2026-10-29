package com.paymentswitch.webhook;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.webhook.WebhookRepository.DeliveryRow;
import com.paymentswitch.webhook.WebhookRepository.WebhookEventRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator API (X-Admin-Token): inspect webhooks with their delivery attempts, re-drive dead ones. */
@RestController
@RequestMapping("/admin/webhooks")
class WebhookAdminController {

    private static final List<String> STATUSES = List.of("PENDING", "DELIVERED", "DEAD");

    private final WebhookRepository repository;

    WebhookAdminController(WebhookRepository repository) {
        this.repository = repository;
    }

    record WebhookView(UUID id, UUID merchantId, String eventType, String status, int attempts,
            OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime deliveredAt,
            List<DeliveryRow> deliveries) {
    }

    @GetMapping
    List<WebhookView> list(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be one of " + STATUSES);
        }
        return repository.list(status, Math.clamp(limit, 1, 200)).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    WebhookView get(@PathVariable UUID id) {
        return view(find(id));
    }

    /** Re-queues a dead webhook with a fresh set of attempts. Same event id, same body. */
    @PostMapping("/{id}/redrive")
    @Transactional
    ResponseEntity<WebhookView> redrive(@PathVariable UUID id) {
        WebhookEventRow webhook = find(id);
        if (!repository.redrive(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_NOT_DEAD",
                    "Only DEAD webhooks can be re-driven (status is " + webhook.status() + ")");
        }
        return ResponseEntity.accepted().body(view(find(id)));
    }

    private WebhookEventRow find(UUID id) {
        return repository.find(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found"));
    }

    private WebhookView view(WebhookEventRow row) {
        // next_attempt_at also holds the lease of the last claim; it only means something while PENDING.
        OffsetDateTime nextAttemptAt = "PENDING".equals(row.status()) ? row.nextAttemptAt() : null;
        return new WebhookView(row.id(), row.merchantId(), row.eventType(), row.status(), row.attempts(),
                nextAttemptAt, row.createdAt(), row.deliveredAt(), repository.deliveries(row.id()));
    }
}
