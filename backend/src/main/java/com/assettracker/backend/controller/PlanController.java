package com.assettracker.backend.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Planner and executor HTTP API.
 * POST /api/plan: read-only LLM loop, returns a proposed {@link ExecutionPlan}.
 * POST /api/execute-plan: validate and publish to Kafka (the write gate).
 * POST /api/cancel-mission: clear waypoints for listed drones.
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

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
    public ResponseEntity<String> plan(@RequestBody PlanRequest request) {
        ExecutionPlan plan;
        try {
            plan = orchestrator.planFromPrompt(request.command());
        } catch (Exception e) {
            log.warn("Planning failed for command \"{}\": {}", request.command(), e.getMessage());
            return ResponseEntity.internalServerError().body(errorJson("PLANNING_FAILED", e.getMessage()));
        }
        return ResponseEntity.ok(serialize(plan));
    }

    /** Parse the raw plan JSON from /api/plan, validate, enqueue. */
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
        return errorJson("REJECTED", message);
    }

    private String errorJson(String status, String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("status", status);
        err.put("error", message);
        return err.toString();
    }
}
