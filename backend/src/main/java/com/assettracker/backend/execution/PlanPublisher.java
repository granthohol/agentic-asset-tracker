package com.assettracker.backend.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Publishes approved plans to plan.events. Doesn't touch Neo4j; PlanExecutor does the work. */
@Component
public class PlanPublisher {

    public static final String TOPIC = "plan.events";

    private static final Logger log = LoggerFactory.getLogger(PlanPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public PlanPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    /** Stamp receivedAt, serialize, publish keyed on planId. */
    public PlanEnvelope publish(ExecutionPlan plan) {
        PlanEnvelope envelope = new PlanEnvelope(System.currentTimeMillis(), plan);
        // Same planId -> same partition on retry.
        kafkaTemplate.send(TOPIC, plan.planId(), serialize(envelope));
        log.info("Enqueued plan {} ({} action(s)) to topic {}", plan.planId(), plan.actions().size(), TOPIC);
        return envelope;
    }

    private String serialize(PlanEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan envelope for " + envelope.plan().planId(), e);
        }
    }
}
