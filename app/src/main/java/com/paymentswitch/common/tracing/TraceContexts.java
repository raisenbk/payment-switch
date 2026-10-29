package com.paymentswitch.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Carries a trace across an asynchronous hand-off (outbox, webhook queue): {@link #capture()} the
 * W3C headers while the request's span is current, store them with the event, and {@link #continueTrace}
 * when the event is processed, so the work shows up in the same trace (ADR-009).
 */
@Component
public class TraceContexts {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };

    private final Tracer tracer;
    private final Propagator propagator;
    private final JsonMapper jsonMapper;

    TraceContexts(Tracer tracer, Propagator propagator, JsonMapper jsonMapper) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.jsonMapper = jsonMapper;
    }

    /** The current trace as JSON headers ({@code {"traceparent": …}}), or null when there is none. */
    public String capture() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(context, headers, Map::put);
        return headers.isEmpty() ? null : jsonMapper.writeValueAsString(headers);
    }

    /**
     * Runs {@code work} in a new span that continues the captured trace (a new trace when
     * {@code headersJson} is null). {@code kind} null means an internal span. Errors are recorded on
     * the span and rethrown.
     */
    public <T> T continueTrace(String headersJson, String spanName, Span.Kind kind, Map<String, String> tags,
            Supplier<T> work) {
        Map<String, String> headers = headersJson == null ? Map.of() : jsonMapper.readValue(headersJson, HEADERS);
        Span.Builder builder = headers.isEmpty()
                ? tracer.spanBuilder()
                : propagator.extract(headers, (carrier, key) -> carrier.get(key));
        builder.name(spanName);
        if (kind != null) {
            builder.kind(kind);
        }
        tags.forEach(builder::tag);
        Span span = builder.start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void continueTrace(String headersJson, String spanName, Span.Kind kind, Map<String, String> tags,
            Runnable work) {
        continueTrace(headersJson, spanName, kind, tags, () -> {
            work.run();
            return null;
        });
    }

    /** Adds the current span's W3C headers to an outgoing request. */
    public void injectCurrent(BiConsumer<String, String> header) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            propagator.inject(context, header, BiConsumer::accept);
        }
    }
}
