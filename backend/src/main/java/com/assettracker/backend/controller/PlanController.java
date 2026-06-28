package com.assettracker.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.agent.AgentOrchestrationService;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.execution.PlanEnvelope;
import com.assettracker.backend.execution.PlanPublisher;
import com.assettracker.backend.execution.PlanValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The two HTTP halves of the Planner-Executor loop.
 *
 * <p>{@code POST /api/plan} — read-only: runs the LLM tool-use loop and returns a proposed
 * {@link ExecutionPlan}. Never writes to Neo4j or Kafka.
 *
 * <p>{@code POST /api/execute-plan} — the CQRS write gate: validates an approved plan and
 * <b>publishes it to Kafka</b> ({@code plan.events}), returning {@code 202 Accepted}. It does
 * NOT call {@code GraphWriter} or {@code CommandPublisher} — the async {@code PlanExecutor}
 * performs the actual mutations. This controller imports neither writer.
 *
 * <p>Bodies are handled with the Jackson 2 agent mapper for consistency with the rest of the
 * agent pipeline (Spring Boot 4's web layer uses Jackson 3).
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final AgentOrchestrationService orchestrator;
    private final PlanValidator planValidator;
    private final PlanPublisher planPublisher;
    private final ObjectMapper mapper;

    public PlanController(AgentOrchestrationService orchestrator, PlanValidator planValidator,
                          PlanPublisher planPublisher, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.planValidator = planValidator;
        this.planPublisher = planPublisher;
        this.mapper = mapper;
    }

    public record PlanRequest(String command) {}

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
