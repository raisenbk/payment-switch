package com.paymentswitch.common.outbox;

import com.paymentswitch.common.tracing.TraceContexts;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes events to the outbox as part of the caller's database transaction (ADR-006). */
@Component
public class OutboxPublisher {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final TraceContexts traces;

    OutboxPublisher(OutboxRepository repository, JsonMapper jsonMapper, TraceContexts traces) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.traces = traces;
    }

    /**
     * The event exists if, and only if, the caller's transaction commits. The current trace context
     * is stored with it, so its handler continues the same trace.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, UUID aggregateId, Map<String, Object> payload) {
        repository.insert(eventType, aggregateId, jsonMapper.writeValueAsString(payload), traces.capture());
    }
}
