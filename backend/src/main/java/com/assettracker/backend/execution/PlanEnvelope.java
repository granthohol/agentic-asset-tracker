package com.assettracker.backend.execution;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What actually rides on the {@code plan.events} Kafka topic: the approved
 * {@link ExecutionPlan} plus the server timestamp at which {@code /api/execute-plan}
 * accepted it. One envelope per approval = one Kafka record = one audit entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanEnvelope(
    long receivedAt,
    ExecutionPlan plan
) {}
