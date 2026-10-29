package com.paymentswitch.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation ids for every request:
 * <ul>
 *   <li>{@value #REQUEST_ID_HEADER}: the caller's id if well-formed (so they can correlate their own
 *       logs), otherwise a new one; echoed back and logged as {@code requestId}.</li>
 *   <li>{@value #TRACE_ID_HEADER}: the OpenTelemetry trace id of this request, the key to find it in
 *       the tracing UI. It is also the {@code traceId} of error responses.</li>
 * </ul>
 * Runs just inside Spring's HTTP observation filter, so the request's span already exists.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    /** Set by Micrometer Tracing while a span is in scope. */
    public static final String MDC_TRACE_ID = "traceId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final ObjectProvider<Tracer> tracer;

    RequestIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
            span.tag("request.id", requestId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /** The id to quote in error responses: the trace id when tracing is active, else the request id. */
    public static String correlationId() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return traceId != null ? traceId : MDC.get(MDC_REQUEST_ID);
    }
}
