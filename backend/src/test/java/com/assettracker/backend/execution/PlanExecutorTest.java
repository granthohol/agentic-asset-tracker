package com.assettracker.backend.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.command.CommandPublisher;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.ObjectiveNode;
import com.assettracker.backend.graph.Waypoint;
import com.fasterxml.jackson.databind.ObjectMapper;

class PlanExecutorTest {

    private final GraphWriter graphWriter = Mockito.mock(GraphWriter.class);
    private final CommandPublisher commandPublisher = Mockito.mock(CommandPublisher.class);
    private final PlanExecutor executor = new PlanExecutor(graphWriter, commandPublisher, new ObjectMapper());

    @Test
    void mintsTempIdAndResolvesItForLaterActions() {
        ExecutionPlan plan = new ExecutionPlan("plan-1", "r", List.of(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, 39.05, -77.18, 250.0, null),
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.05, -77.18, "RECON")
        ));

        executor.execute(plan);

        // The objective was upserted with a freshly minted real id (not the tempId).
        ArgumentCaptor<ObjectiveNode> objCaptor = ArgumentCaptor.forClass(ObjectiveNode.class);
        verify(graphWriter).upsertObjective(objCaptor.capture());
        String mintedId = objCaptor.getValue().id();
        assertThat(mintedId).startsWith("objective-");
        assertThat(objCaptor.getValue().name()).isEqualTo("Observe");

        // The $obj-1 reference resolved to that same minted id.
        verify(graphWriter).deploySquadronToObjective("squadron-alpha", mintedId);

        // setWaypoint hits BOTH the command topic and the graph mirror.
        verify(commandPublisher).publishSetWaypoint("drone-007", 39.05, -77.18, "RECON");
        verify(graphWriter).setDroneWaypoint("drone-007", new Waypoint(39.05, -77.18));
    }

    @Test
    void failFastHaltsRemainingActions() {
        doThrow(new IllegalArgumentException("squadron not found"))
            .when(graphWriter).deploySquadronToObjective(Mockito.anyString(), Mockito.anyString());

        ExecutionPlan plan = new ExecutionPlan("plan-2", "r", List.of(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, null, null, null, null),
            new PlanAction.DeploySquadronToObjective("squadron-missing", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.0, -77.0, null) // must NOT run
        ));

        executor.execute(plan); // does not throw — fail-fast logs and stops

        verify(graphWriter).upsertObjective(Mockito.any());
        verify(graphWriter).deploySquadronToObjective(Mockito.anyString(), Mockito.anyString());
        // The action after the failure was skipped.
        verify(commandPublisher, never()).publishSetWaypoint(Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.any());
        verify(graphWriter, never()).setDroneWaypoint(Mockito.anyString(), Mockito.any());
    }
}
