package com.paymentswitch.common.outbox;

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

    OutboxPublisher(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /** The event exists if, and only if, the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, UUID aggregateId, Map<String, Object> payload) {
        repository.insert(eventType, aggregateId, jsonMapper.writeValueAsString(payload));
    }
}
