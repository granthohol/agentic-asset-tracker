package com.assettracker.backend.agent.plan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Proposed plan from the LLM. After approval, goes to plan.events and PlanExecutor.
 * actions is ordered: $tempId refs must come after the upsert that declared them.
 * Validation lives in /api/execute-plan and the executor, not here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionPlan(
    String planId,
    String rationale,
    List<PlanAction> actions
) {}
