package com.paymentswitch.common.idempotency;

import org.springframework.http.HttpStatus;

/** What an idempotent action produced; {@code body} is serialized to JSON and stored for replays. */
public record IdempotentResponse(HttpStatus status, Object body) {
}
