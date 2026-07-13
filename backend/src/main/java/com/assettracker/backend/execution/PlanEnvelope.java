package com.assettracker.backend.execution;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Kafka payload for plan.events: approved plan + server timestamp. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanEnvelope(
    long receivedAt,
    ExecutionPlan plan
) {}
