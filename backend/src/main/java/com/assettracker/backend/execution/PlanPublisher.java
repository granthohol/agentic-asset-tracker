package com.assettracker.backend.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes an approved {@link ExecutionPlan} as a single {@link PlanEnvelope} record to
 * the {@code plan.events} Kafka topic. This is the CQRS write seam's <b>enqueue</b> step:
 * it does not touch Neo4j or the command topic — it just durably hands the plan to the
 * async {@link PlanExecutor}. Called only by {@code /api/execute-plan}.
 */
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

    /** Stamp {@code receivedAt}, serialize, and publish keyed on {@code planId}. */
    public PlanEnvelope publish(ExecutionPlan plan) {
        PlanEnvelope envelope = new PlanEnvelope(System.currentTimeMillis(), plan);
        // Key = planId so retries / re-approvals of the same plan land in the same partition.
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
