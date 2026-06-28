package com.assettracker.backend.agent.plan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The proposed, human-approvable unit of intent produced by the LLM orchestrator
 * (step 9) and, after approval, published whole to the {@code plan.events} Kafka topic
 * and executed by {@code PlanExecutor} (step 12).
 *
 * <p>{@code actions} is an <b>ordered</b> list: the executor walks it linearly so that a
 * {@code $tempId} reference always follows the {@code upsert*} that declared it.
 *
 * <p>This type is pure data. Validation (id xor tempId, {@code $tempId} resolvability,
 * coordinate bounds) lives in the {@code /api/execute-plan} handler and the executor —
 * not in the record — so the parse step stays a faithful mirror of the wire JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionPlan(
    String planId,
    String rationale,
    List<PlanAction> actions
) {}
