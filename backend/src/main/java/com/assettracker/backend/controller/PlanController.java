package com.assettracker.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.agent.AgentOrchestrationService;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The read/plan half of the Planner-Executor loop.
 *
 * <p>{@code POST /api/plan} runs the LLM tool-use loop and returns a <b>proposed</b>
 * {@link ExecutionPlan}. It is strictly read-only — it never writes to Neo4j or Kafka.
 * The mutation gate is {@code POST /api/execute-plan} (step 11), which only publishes an
 * approved plan to Kafka. This controller does not (and must not) reference
 * {@code GraphWriter} or {@code CommandPublisher}.
 *
 * <p>The plan is serialized with the Jackson 2 agent mapper (same one that built it) and
 * returned as a JSON string, to stay consistent across the project's dual-Jackson setup.
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final AgentOrchestrationService orchestrator;
    private final ObjectMapper mapper;

    public PlanController(AgentOrchestrationService orchestrator, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    public record PlanRequest(String command) {}

    @PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public String plan(@RequestBody PlanRequest request) {
        ExecutionPlan plan = orchestrator.planFromPrompt(request.command());
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan", e);
        }
    }
}
