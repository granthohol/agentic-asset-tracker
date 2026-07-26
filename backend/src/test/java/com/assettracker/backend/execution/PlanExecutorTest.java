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

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.agent.plan.PlanExpander;
import com.assettracker.backend.agent.routing.RestrictedZoneObstacles;
import com.assettracker.backend.command.CommandPublisher;
import com.assettracker.backend.entity.EntityService;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.ObjectiveNode;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.TrackNode;
import com.assettracker.backend.graph.Waypoint;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.model.DroneStatus;
import com.assettracker.backend.service.DroneService;
import com.fasterxml.jackson.databind.ObjectMapper;

class PlanExecutorTest {

    private final GraphWriter graphWriter = Mockito.mock(GraphWriter.class);
    private final CommandPublisher commandPublisher = Mockito.mock(CommandPublisher.class);
    private final DroneService droneService = Mockito.mock(DroneService.class);
    private final EntityService entityService = Mockito.mock(EntityService.class);
    private final GraphService graphService = Mockito.mock(GraphService.class);
    private final PlanExpander planExpander =
        new PlanExpander(new FormationService(), new RestrictedZoneObstacles(graphService));
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanExecutor executor = new PlanExecutor(
        graphWriter, commandPublisher, droneService, entityService, planExpander, mapper);

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
    void applyFormationExpandsToPerDroneSetWaypoints() {
        ExecutionPlan plan = new ExecutionPlan("plan-form", "r", List.of(
            new PlanAction.ApplyFormation(
                FormationType.RING, 39.05, -77.18,
                List.of("drone-000", "drone-001", "drone-002"),
                "RECON", null, null, null)
        ));

        executor.execute(plan);

        // one setWaypoint per drone, no FORM_UP gate
        verify(commandPublisher, times(3)).publishSetWaypoint(
            Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.eq("RECON"));
        verify(commandPublisher).publishSetWaypoint(
            Mockito.eq("drone-000"), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.eq("RECON"));
        verify(graphWriter, times(3)).setDroneWaypoint(Mockito.anyString(), Mockito.any());
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

    @Test
    void upsertTrackRoutesThroughEntityServiceAndMintsTempId() {
        ExecutionPlan plan = new ExecutionPlan("plan-track", "r", List.of(
            new PlanAction.UpsertTrack(
                null, "track-1", "Hostile aerial contact",
                Affiliation.HOSTILE, TrackDomain.AERIAL, 39.05, -77.18)
        ));

        executor.execute(plan);

        ArgumentCaptor<TrackNode> captor = ArgumentCaptor.forClass(TrackNode.class);
        verify(entityService).upsertTrack(captor.capture());
        TrackNode node = captor.getValue();
        assertThat(node.id()).startsWith("track-");
        assertThat(node.affiliation()).isEqualTo(Affiliation.HOSTILE);
        assertThat(node.domain()).isEqualTo(TrackDomain.AERIAL);
        assertThat(node.latitude()).isEqualTo(39.05);
    }

    @Test
    void upsertCircleZoneBuildsCircleNode() {
        ExecutionPlan plan = new ExecutionPlan("plan-zone", "r", List.of(
            new PlanAction.UpsertZone(
                null, "zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                39.05, -77.18, 800.0, null)
        ));

        executor.execute(plan);

        ArgumentCaptor<ZoneNode> captor = ArgumentCaptor.forClass(ZoneNode.class);
        verify(entityService).upsertZone(captor.capture());
        ZoneNode node = captor.getValue();
        assertThat(node.shape()).isEqualTo(ZoneShape.CIRCLE);
        assertThat(node.radiusMeters()).isEqualTo(800.0);
        assertThat(node.centerLatitude()).isEqualTo(39.05);
        assertThat(node.vertexLats()).isEmpty();
    }

    @Test
    void upsertPolygonZoneFlattensVerticesToParallelArrays() {
        ExecutionPlan plan = new ExecutionPlan("plan-poly", "r", List.of(
            new PlanAction.UpsertZone(
                null, "zone-1", "Patrol Box", ZoneType.PATROL, ZoneShape.POLYGON,
                null, null, null, List.of(
                    List.of(39.0, -77.2),
                    List.of(39.1, -77.2),
                    List.of(39.1, -77.1)))
        ));

        executor.execute(plan);

        ArgumentCaptor<ZoneNode> captor = ArgumentCaptor.forClass(ZoneNode.class);
        verify(entityService).upsertZone(captor.capture());
        ZoneNode node = captor.getValue();
        assertThat(node.shape()).isEqualTo(ZoneShape.POLYGON);
        assertThat(node.vertexLats()).containsExactly(39.0, 39.1, 39.1);
        assertThat(node.vertexLngs()).containsExactly(-77.2, -77.2, -77.1);
    }

    @Test
    void removeZoneCallsEntityServiceDelete() {
        ExecutionPlan plan = new ExecutionPlan("plan-rm", "r", List.of(
            new PlanAction.RemoveZone("zone-abc")
        ));

        executor.execute(plan);

        verify(entityService).deleteZone("zone-abc");
    }

    @Test
    void setRoutePublishesLegsSequentiallyWaitingBetween() {
        // Already at first leg; next publish only after arrival check.
        when(droneService.getDrone("drone-000")).thenReturn(
            new Drone("drone-000", 39.02, -77.19, 80, DroneStatus.ACTIVE)
        );

        ExecutionPlan plan = new ExecutionPlan("plan-route", "r", List.of(
            new PlanAction.SetRoute(
                "drone-000",
                List.of(List.of(39.02, -77.19), List.of(39.05, -77.18)),
                "ADVANCE")
        ));

        executor.execute(plan);

        InOrder order = Mockito.inOrder(commandPublisher);
        order.verify(commandPublisher).publishSetWaypoint(
            "drone-000", 39.02, -77.19, PlanExecutor.TRANSIT_MISSION);
        order.verify(commandPublisher).publishSetWaypoint(
            "drone-000", 39.05, -77.18, "ADVANCE");
        verify(droneService, Mockito.atLeastOnce()).getDrone("drone-000");
    }

    @Test
    void waitsBetweenHoldAndAdvanceWaves() {
        // Telemetry already at each target so wave waits return immediately.
        when(droneService.getDrone("drone-000")).thenReturn(
            new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 39.04, -77.185, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 39.04, -77.185, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 39.05, -77.18, 80, DroneStatus.ACTIVE)
        );

        ExecutionPlan plan = new ExecutionPlan("plan-hold", "r", List.of(
            new PlanAction.SetWaypoint("drone-000", 39.03, -77.18, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-000", 39.04, -77.185, "HOLD"),
            new PlanAction.SetWaypoint("drone-000", 39.05, -77.18, "ADVANCE")
        ));

        executor.execute(plan);

        InOrder order = Mockito.inOrder(commandPublisher);
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.03, -77.18, "FORM_UP");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.04, -77.185, "HOLD");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.05, -77.18, "ADVANCE");
        verify(droneService, Mockito.atLeastOnce()).getDrone("drone-000");
    }

    @Test
    void waitsBetweenMultipleDistinctHoldLegsNotJustHoldAndAdvance() {
        // Regression: PlanExpander numbers multi-detour HOLD legs (HOLD_1, HOLD_2, ...) so this
        // wave-grouping loop's contiguous-same-mission_type check can't merge two geometrically
        // different detour legs into one wave (which used to publish leg 2's coordinates as an
        // immediate same-wave overwrite of leg 1 for every drone, then wait forever for a leg-1
        // arrival that would never happen). This locks in that HOLD_1 gates HOLD_2 exactly like
        // FORM_UP gates HOLD_1 and HOLD_2 gates ADVANCE — three separate waits, not one.
        when(droneService.getDrone("drone-000")).thenReturn(
            new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 38.99, -77.30, 80, DroneStatus.ACTIVE),
            new Drone("drone-000", 39.00, -77.25, 80, DroneStatus.ACTIVE)
        );

        ExecutionPlan plan = new ExecutionPlan("plan-multi-hold", "r", List.of(
            new PlanAction.SetWaypoint("drone-000", 39.03, -77.18, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-000", 38.99, -77.30, "HOLD_1"),
            new PlanAction.SetWaypoint("drone-000", 39.00, -77.25, "HOLD_2"),
            new PlanAction.SetWaypoint("drone-000", 39.05, -77.18, "ADVANCE")
        ));

        executor.execute(plan);

        InOrder order = Mockito.inOrder(commandPublisher);
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.03, -77.18, "FORM_UP");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 38.99, -77.30, "HOLD_1");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.00, -77.25, "HOLD_2");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.05, -77.18, "ADVANCE");
        verify(commandPublisher, times(4)).publishSetWaypoint(
            Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.any());
        // One arrival check per wave transition (FORM_UP->HOLD_1, HOLD_1->HOLD_2, HOLD_2->ADVANCE).
        // If HOLD_1/HOLD_2 had collided on a shared mission_type, this would only be 2.
        verify(droneService, times(3)).getDrone("drone-000");
    }

    /**
     * Regression: execute() can block for minutes waiting on wave arrivals (up to
     * WAVE_TIMEOUT_MS per wave). Running that directly on the Kafka listener thread would stop
     * it calling poll(), which risks exceeding max.poll.interval.ms mid-mission — the broker
     * revokes the partition, and once poll() resumes this same not-yet-committed message gets
     * redelivered, silently re-running the whole plan from FORM_UP while drones are already
     * mid-mission or already at the destination (looks exactly like "drones went back to start
     * partway through, then corrected"). onPlanEnvelope must hand execute() off to a worker
     * thread and return immediately regardless of how long the plan takes to run.
     */
    @Test
    void onPlanEnvelopeReturnsImmediatelyEvenThoughExecuteWouldBlock() throws Exception {
        // getDrone is slow (simulating a wave that takes a while to arrive) but eventually
        // reports the drone at FORM_UP's target, so the background execution finishes quickly
        // enough for the test to observe the second wave without waiting out the real 90s timeout.
        when(droneService.getDrone("drone-000")).thenAnswer(inv -> {
            Thread.sleep(300);
            return new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE);
        });

        ExecutionPlan plan = new ExecutionPlan("plan-async", "r", List.of(
            new PlanAction.SetWaypoint("drone-000", 39.03, -77.18, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-000", 39.05, -77.18, "ADVANCE")
        ));
        String json = mapper.writeValueAsString(new PlanEnvelope(0L, plan));

        long start = System.currentTimeMillis();
        executor.onPlanEnvelope(json);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs)
            .as("onPlanEnvelope must not block on execute()'s wave waits")
            .isLessThan(200);

        awaitPublishCount(2, 2000);
        InOrder order = Mockito.inOrder(commandPublisher);
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.03, -77.18, "FORM_UP");
        order.verify(commandPublisher).publishSetWaypoint("drone-000", 39.05, -77.18, "ADVANCE");
    }

    @Test
    void onPlanEnvelopeSkipsARedeliveredDuplicateOfTheSamePlanId() throws Exception {
        when(droneService.getDrone("drone-000")).thenReturn(
            new Drone("drone-000", 39.03, -77.18, 80, DroneStatus.ACTIVE)
        );

        ExecutionPlan plan = new ExecutionPlan("plan-dup", "r", List.of(
            new PlanAction.SetWaypoint("drone-000", 39.03, -77.18, "FORM_UP")
        ));
        String json = mapper.writeValueAsString(new PlanEnvelope(0L, plan));

        executor.onPlanEnvelope(json);
        executor.onPlanEnvelope(json); // redelivery of the same message/planId

        awaitPublishCount(1, 2000);
        // Give a would-be second execution a moment to (wrongly) fire before asserting it didn't.
        Thread.sleep(300);
        verify(commandPublisher, times(1)).publishSetWaypoint(
            Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.any());
    }

    private void awaitPublishCount(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int count = Mockito.mockingDetails(commandPublisher).getInvocations().size();
            if (count >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
