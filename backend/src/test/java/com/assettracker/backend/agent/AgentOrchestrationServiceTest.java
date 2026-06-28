package com.assettracker.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.assettracker.backend.agent.llm.StubLlmClient;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.agent.tools.ToolRegistry;
import com.assettracker.backend.graph.DroneNode;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.SquadronNode;
import com.assettracker.backend.model.DroneStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgentOrchestrationServiceTest {

    private final GraphService graph = Mockito.mock(GraphService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(graph, mapper);
    private final AgentOrchestrationService orchestrator =
        new AgentOrchestrationService(new StubLlmClient(mapper), registry, mapper);

    @Test
    void runsToolLoopAndReturnsValidPlanReferencingRealEntities() {
        when(graph.listSquadrons()).thenReturn(List.of(new SquadronNode("squadron-alpha", "Alpha", "sector-1")));
        when(graph.listDrones()).thenReturn(List.of(new DroneNode("drone-007", 39.0, -77.2, 80, DroneStatus.ACTIVE, null)));

        ExecutionPlan plan = orchestrator.planFromPrompt("Observe the disturbance at 39.05,-77.18");

        // The loop actually invoked the read tools.
        verify(graph).listSquadrons();
        verify(graph).listDrones();

        // A planId is always present (minted if the model omitted it).
        assertThat(plan.planId()).startsWith("plan-");

        // Demonstrates a tempId -> $tempId chain: create objective, then deploy to it.
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.UpsertObjective.class);
            assertThat(((PlanAction.UpsertObjective) a).tempId()).isEqualTo("obj-1");
        });
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.DeploySquadronToObjective.class);
            PlanAction.DeploySquadronToObjective d = (PlanAction.DeploySquadronToObjective) a;
            assertThat(d.squadronId()).isEqualTo("squadron-alpha"); // grounded in the tool result
            assertThat(d.objectiveId()).isEqualTo("$obj-1");        // references the temp objective
        });
        // Routes the real drone discovered via the tool.
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.SetWaypoint.class);
            assertThat(((PlanAction.SetWaypoint) a).droneId()).isEqualTo("drone-007");
        });
    }

    @Test
    void mintsSquadronWhenGraphIsEmpty() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(List.of());

        ExecutionPlan plan = orchestrator.planFromPrompt("Set up an observation objective");

        // With no real squadron, the stub mints one via a second tempId chain.
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.UpsertSquadron.class);
            assertThat(((PlanAction.UpsertSquadron) a).tempId()).isEqualTo("squad-1");
        });
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.DeploySquadronToObjective.class);
            assertThat(((PlanAction.DeploySquadronToObjective) a).squadronId()).isEqualTo("$squad-1");
        });
    }
}
