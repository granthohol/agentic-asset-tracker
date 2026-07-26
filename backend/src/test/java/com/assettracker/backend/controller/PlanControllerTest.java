package com.assettracker.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.assettracker.backend.agent.AgentOrchestrationService;
import com.assettracker.backend.execution.MissionCancelService;
import com.assettracker.backend.execution.PlanPublisher;
import com.assettracker.backend.execution.PlanValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

class PlanControllerTest {

    private final AgentOrchestrationService orchestrator = Mockito.mock(AgentOrchestrationService.class);
    private final PlanValidator planValidator = Mockito.mock(PlanValidator.class);
    private final PlanPublisher planPublisher = Mockito.mock(PlanPublisher.class);
    private final MissionCancelService missionCancelService = Mockito.mock(MissionCancelService.class);
    private final PlanController controller = new PlanController(
        orchestrator, planValidator, planPublisher, missionCancelService, new ObjectMapper());

    /**
     * Regression: POST /api/plan used to have no exception handling at all, so any failure
     * (a swarm route with no clear detour, an LLM API error, ...) fell through to Spring's
     * default handler and produced a message-less 500 — the operator saw only
     * {"status":500,"error":"Internal Server Error"} with no indication of what actually
     * went wrong. The endpoint must catch and report the real reason instead.
     */
    @Test
    void planFailureReturns500WithTheActualReasonInsteadOfAnOpaqueError() {
        when(orchestrator.planFromPrompt("bad prompt"))
            .thenThrow(new IllegalStateException(
                "LLM failed to produce a valid ExecutionPlan after 2 retries: "
                    + "could not find a clear detour around restricted zone 'zone-1'"));

        ResponseEntity<String> response = controller.plan(new PlanController.PlanRequest("bad prompt"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("could not find a clear detour around restricted zone 'zone-1'");
    }

    @Test
    void planSuccessReturnsTheSerializedPlan() {
        when(orchestrator.planFromPrompt("good prompt")).thenReturn(
            new com.assettracker.backend.agent.plan.ExecutionPlan("p1", "ok", java.util.List.of()));

        ResponseEntity<String> response = controller.plan(new PlanController.PlanRequest("good prompt"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"planId\":\"p1\"");
    }
}
