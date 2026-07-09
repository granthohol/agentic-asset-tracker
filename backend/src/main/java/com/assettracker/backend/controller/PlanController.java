package com.assettracker.backend.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.agent.AgentOrchestrationService;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.execution.MissionCancelService;
import com.assettracker.backend.execution.PlanEnvelope;
import com.assettracker.backend.execution.PlanPublisher;
import com.assettracker.backend.execution.PlanValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * HTTP halves of the Planner-Executor loop plus HITL Stop.
 *
 * <p>{@code POST /api/plan} — read-only: runs the LLM tool-use loop and returns a proposed
 * {@link ExecutionPlan}. Never writes to Neo4j or Kafka.
 *
 * <p>{@code POST /api/execute-plan} — the CQRS write gate: validates an approved plan and
 * <b>publishes it to Kafka</b> ({@code plan.events}), returning {@code 202 Accepted}.
 *
 * <p>{@code POST /api/cancel-mission} — clears waypoints for the given drones (graph + edge).
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final AgentOrchestrationService orchestrator;
    private final PlanValidator planValidator;
    private final PlanPublisher planPublisher;
    private final MissionCancelService missionCancelService;
    private final ObjectMapper mapper;

    public PlanController(
        AgentOrchestrationService orchestrator,
        PlanValidator planValidator,
        PlanPublisher planPublisher,
        MissionCancelService missionCancelService,
        ObjectMapper mapper
    ) {
        this.orchestrator = orchestrator;
        this.planValidator = planValidator;
        this.planPublisher = planPublisher;
        this.missionCancelService = missionCancelService;
        this.mapper = mapper;
    }

    public record PlanRequest(String command) {}

    public record CancelMissionRequest(List<String> droneIds) {}

    @PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public String plan(@RequestBody PlanRequest request) {
        ExecutionPlan plan = orchestrator.planFromPrompt(request.command());
        return serialize(plan);
    }

    /**
     * Accept the approved plan as a raw JSON string (exactly what {@code /api/plan} returned),
     * validate it, and enqueue it. Parsing here with the agent mapper guarantees identical
     * (de)serialization to the rest of the pipeline.
     */
    @PostMapping(value = "/execute-plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> executePlan(@RequestBody String rawPlanJson) {
        ExecutionPlan plan;
        try {
            plan = mapper.readValue(rawPlanJson, ExecutionPlan.class);
            planValidator.validate(plan);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorJson(e.getMessage()));
        }

        PlanEnvelope envelope = planPublisher.publish(plan);
        ObjectNode body = mapper.createObjectNode();
        body.put("planId", plan.planId());
        body.put("status", "ENQUEUED");
        body.put("receivedAt", envelope.receivedAt());
        return ResponseEntity.accepted().body(body.toString());
    }

    @PostMapping(value = "/cancel-mission", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> cancelMission(@RequestBody CancelMissionRequest request) {
        if (request == null || request.droneIds() == null || request.droneIds().isEmpty()) {
            return ResponseEntity.badRequest().body(errorJson("droneIds required"));
        }
        int cleared = missionCancelService.cancelDrones(request.droneIds());
        ObjectNode body = mapper.createObjectNode();
        body.put("status", "CANCELLED");
        body.put("cleared", cleared);
        return ResponseEntity.ok(body.toString());
    }

    private String serialize(ExecutionPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan", e);
        }
    }

    private String errorJson(String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("status", "REJECTED");
        err.put("error", message);
        return err.toString();
    }
}
