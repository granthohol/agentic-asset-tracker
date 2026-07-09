package com.assettracker.backend.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.command.CommandPublisher;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.ObjectiveNode;
import com.assettracker.backend.graph.Waypoint;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.model.DroneStatus;
import com.assettracker.backend.service.DroneService;
import com.fasterxml.jackson.databind.ObjectMapper;

class PlanExecutorTest {

    private final GraphWriter graphWriter = Mockito.mock(GraphWriter.class);
    private final CommandPublisher commandPublisher = Mockito.mock(CommandPublisher.class);
    private final DroneService droneService = Mockito.mock(DroneService.class);
    private final PlanExecutor executor =
        new PlanExecutor(graphWriter, commandPublisher, droneService, new ObjectMapper());

    @Test
    void mintsTempIdAndResolvesItForLaterActions() {
        ExecutionPlan plan = new ExecutionPlan("plan-1", "r", List.of(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, 39.05, -77.18, 250.0, null),
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.05, -77.18, "RECON")
        ));

        executor.execute(plan);

        ArgumentCaptor<ObjectiveNode> objCaptor = ArgumentCaptor.forClass(ObjectiveNode.class);
        verify(graphWriter).upsertObjective(objCaptor.capture());
        String mintedId = objCaptor.getValue().id();
        assertThat(mintedId).startsWith("objective-");

        verify(graphWriter).deploySquadronToObjective("squadron-alpha", mintedId);
        verify(commandPublisher).publishSetWaypoint("drone-007", 39.05, -77.18, "RECON");
        verify(graphWriter).setDroneWaypoint("drone-007", new Waypoint(39.05, -77.18));
    }

    @Test
    void waitsForFormUpBeforePublishingAdvance() {
        when(droneService.getDrone("drone-000")).thenReturn(
            new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE)
        );
        when(droneService.getDrone("drone-001")).thenReturn(
            new Drone("drone-001", 39.029, -77.181, 80, DroneStatus.ACTIVE)
        );

        ExecutionPlan plan = new ExecutionPlan("plan-swarm", "r", List.of(
            new PlanAction.SetWaypoint("drone-000", 39.03, -77.18, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-001", 39.029, -77.181, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-000", 39.05, -77.18, "ADVANCE"),
            new PlanAction.SetWaypoint("drone-001", 39.049, -77.181, "ADVANCE")
        ));

        executor.execute(plan);

        InOrder order = Mockito.inOrder(commandPublisher);
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.03, -77.18, "FORM_UP");
        order.verify(commandPublisher).publishSetWaypoint("drone-001", 39.029, -77.181, "FORM_UP");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.05, -77.18, "ADVANCE");
        order.verify(commandPublisher).publishSetWaypoint("drone-001", 39.049, -77.181, "ADVANCE");
        verify(commandPublisher, times(4)).publishSetWaypoint(
            Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.any());
        verify(droneService, Mockito.atLeastOnce()).getDrone("drone-000");
    }

    @Test
    void failFastHaltsRemainingActions() {
        doThrow(new IllegalArgumentException("squadron not found"))
            .when(graphWriter).deploySquadronToObjective(Mockito.anyString(), Mockito.anyString());

        ExecutionPlan plan = new ExecutionPlan("plan-2", "r", List.of(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, null, null, null, null),
            new PlanAction.DeploySquadronToObjective("squadron-missing", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.0, -77.0, null)
        ));

        executor.execute(plan);

        verify(graphWriter).upsertObjective(Mockito.any());
        verify(graphWriter).deploySquadronToObjective(Mockito.anyString(), Mockito.anyString());
        verify(commandPublisher, never()).publishSetWaypoint(
            Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.any());
        verify(graphWriter, never()).setDroneWaypoint(Mockito.anyString(), Mockito.any());
    }
}
