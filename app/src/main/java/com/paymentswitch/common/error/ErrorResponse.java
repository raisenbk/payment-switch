package com.paymentswitch.common.error;

import java.util.List;

/**
 * The single error body returned by every endpoint.
 *
 * @param code    stable, machine-readable error code (e.g. {@code VALIDATION_FAILED})
 * @param message human-readable description; never contains secrets or internal details
 * @param traceId request trace id, also returned in the {@code X-Request-Id} header
 * @param details optional field-level problems, empty when not applicable
 */
public record ErrorResponse(String code, String message, String traceId, List<FieldProblem> details) {

    public record FieldProblem(String field, String message) {
    }
}
