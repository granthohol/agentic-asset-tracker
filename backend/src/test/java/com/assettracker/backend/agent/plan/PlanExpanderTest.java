package com.assettracker.backend.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.agent.routing.RestrictedZoneObstacles;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;

class PlanExpanderTest {

    private final GraphService graph = Mockito.mock(GraphService.class);
    private final RestrictedZoneObstacles restrictedZones = new RestrictedZoneObstacles(graph);
    private final PlanExpander expander = new PlanExpander(new FormationService(), restrictedZones);

    @Test
    void expandsApplyFormationIntoPerDroneSetWaypoints() {
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormation(
                FormationType.WEDGE, 39.03, -77.18,
                List.of("drone-000", "drone-001", "drone-002"),
                "FORM_UP", null, 39.05, -77.18)
        ));

        ExecutionPlan out = expander.expand(plan);

        assertThat(out.planId()).isEqualTo("p");
        assertThat(out.actions()).hasSize(3);
        assertThat(out.actions()).allMatch(a -> a instanceof PlanAction.SetWaypoint);

        List<PlanAction.SetWaypoint> waypoints = out.actions().stream()
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();
        assertThat(waypoints).allSatisfy(w -> assertThat(w.missionType()).isEqualTo("FORM_UP"));
        assertThat(waypoints.stream().map(PlanAction.SetWaypoint::droneId).toList())
            .containsExactly("drone-000", "drone-001", "drone-002");
    }

    @Test
    void passesThroughNonFormationActionsUnchanged() {
        PlanAction objective = new PlanAction.UpsertObjective(
            null, "obj-1", "Observe", 1, 39.0, -77.0, 100.0, null);
        PlanAction waypoint = new PlanAction.SetWaypoint("drone-000", 39.0, -77.0, "RECON");

        List<PlanAction> out = expander.expandActions(List.of(objective, waypoint));

        assertThat(out).containsExactly(objective, waypoint);
    }

    @Test
    void interleavesExpandedFormationsWithOtherActionsInOrder() {
        PlanAction objective = new PlanAction.UpsertObjective(
            null, "obj-1", "Observe", 1, 39.0, -77.0, 100.0, null);
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            objective,
            new PlanAction.ApplyFormation(FormationType.LINE, 39.03, -77.18,
                List.of("drone-000", "drone-001"), "FORM_UP", null, null, null),
            new PlanAction.ApplyFormation(FormationType.LINE, 39.05, -77.18,
                List.of("drone-000", "drone-001"), "ADVANCE", null, null, null)
        ));

        List<PlanAction> out = expander.expand(plan).actions();

        assertThat(out).hasSize(5); // objective, 2 FORM_UP, 2 ADVANCE
        assertThat(out.get(0)).isEqualTo(objective);
        long formUps = out.stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "FORM_UP".equals(sw.missionType()))
            .count();
        long advances = out.stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "ADVANCE".equals(sw.missionType()))
            .count();
        assertThat(formUps).isEqualTo(2);
        assertThat(advances).isEqualTo(2);
    }

    @Test
    void emptyDroneIdsBubblesUpFromFormationService() {
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormation(FormationType.RING, 39.0, -77.0,
                List.of(), "FORM_UP", null, null, null)
        ));

        assertThatThrownBy(() -> expander.expand(plan))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reordersAdvanceWaveBeforeFormUp() {
        PlanAction objective = new PlanAction.UpsertObjective(
            null, "obj-1", "Observe", 1, 39.0, -77.0, 100.0, null);
        List<PlanAction> out = expander.expandActions(List.of(
            objective,
            new PlanAction.SetWaypoint("drone-000", 39.05, -77.18, "ADVANCE"),
            new PlanAction.SetWaypoint("drone-001", 39.051, -77.181, "ADVANCE"),
            new PlanAction.SetWaypoint("drone-000", 38.90, -77.30, "FORM_UP"),
            new PlanAction.SetWaypoint("drone-001", 38.901, -77.301, "FORM_UP")
        ));

        assertThat(out.get(0)).isEqualTo(objective);
        assertThat(((PlanAction.SetWaypoint) out.get(1)).missionType()).isEqualTo("FORM_UP");
        assertThat(((PlanAction.SetWaypoint) out.get(2)).missionType()).isEqualTo("FORM_UP");
        assertThat(((PlanAction.SetWaypoint) out.get(3)).missionType()).isEqualTo("ADVANCE");
        assertThat(((PlanAction.SetWaypoint) out.get(4)).missionType()).isEqualTo("ADVANCE");
    }

    @Test
    void formationRouteWithNoObstaclesExpandsToFormUpThenAdvanceForTheWholeSwarm() {
        List<String> droneIds = List.of("drone-000", "drone-001", "drone-002");
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormationRoute(
                FormationType.WEDGE, droneIds, 38.90, -77.40, 39.05, -77.18, "ADVANCE", null)
        ));

        List<PlanAction> out = expander.expand(plan).actions();
        assertThat(out).allMatch(a -> a instanceof PlanAction.SetWaypoint);

        List<PlanAction.SetWaypoint> waypoints = out.stream()
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();
        List<PlanAction.SetWaypoint> formUp = waypoints.stream()
            .filter(w -> "FORM_UP".equals(w.missionType())).toList();
        List<PlanAction.SetWaypoint> advance = waypoints.stream()
            .filter(w -> "ADVANCE".equals(w.missionType())).toList();

        // No RESTRICTED zone in the way: no HOLD wave, but the FULL swarm still moves in both
        // FORM_UP and ADVANCE — never just the leader.
        assertThat(formUp).extracting(PlanAction.SetWaypoint::droneId)
            .containsExactlyInAnyOrderElementsOf(droneIds);
        assertThat(advance).extracting(PlanAction.SetWaypoint::droneId)
            .containsExactlyInAnyOrderElementsOf(droneIds);
        assertThat(waypoints.stream().filter(w -> "HOLD".equals(w.missionType()))).isEmpty();
    }

    @Test
    void formationRouteDetoursAroundRestrictedCircleWithFullSwarmAtEveryWave() {
        // A RESTRICTED CIRCLE sitting on the straight line between formUp and dest forces a
        // HOLD detour. This is the regression that matters: every wave — FORM_UP, HOLD, and
        // ADVANCE — must carry the identical full drone set, so no subset of the swarm is ever
        // left stranded (the "leader takes off alone, the rest go idle" bug).
        when(graph.listZones()).thenReturn(List.of(
            new ZoneNode("zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                38.975, -77.29, 3000.0, new double[0], new double[0])
        ));

        List<String> droneIds = List.of("drone-000", "drone-001", "drone-002", "drone-003", "drone-004");
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormationRoute(
                FormationType.WEDGE, droneIds, 38.90, -77.40, 39.05, -77.18, "ADVANCE", null)
        ));

        List<PlanAction> out = expander.expand(plan).actions();
        List<PlanAction.SetWaypoint> waypoints = out.stream()
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();

        List<Set<String>> waveDroneSets = new java.util.ArrayList<>();
        int i = 0;
        while (i < waypoints.size()) {
            String mission = waypoints.get(i).missionType();
            Set<String> wave = new java.util.LinkedHashSet<>();
            while (i < waypoints.size() && mission.equals(waypoints.get(i).missionType())) {
                wave.add(waypoints.get(i).droneId());
                i++;
            }
            waveDroneSets.add(wave);
        }

        // FORM_UP, at least one HOLD detour, and ADVANCE — every wave has the whole swarm.
        assertThat(waveDroneSets.size()).isGreaterThanOrEqualTo(3);
        assertThat(waveDroneSets).allSatisfy(
            wave -> assertThat(wave).containsExactlyInAnyOrderElementsOf(droneIds));

        boolean hasHold = waypoints.stream().anyMatch(w -> "HOLD".equals(w.missionType()));
        assertThat(hasHold).isTrue();
    }

    @Test
    void formationRouteKeepsEveryDroneSlotClearOfTheZoneNotJustTheCenter() {
        // ZoneRouter only keeps the formation CENTER outside zone.radius + its own 200 m buffer.
        // A RING spread wide enough can put a drone much farther from center than that buffer —
        // and with 16 drones evenly spaced around the ring, at least one of them sits close to
        // whatever direction points at the zone from any given waypoint — so an off-center drone
        // could still land inside the real zone even though the center's route clears it. Every
        // waypoint's distance from the zone center must be strictly outside zone.radiusMeters.
        double zoneLat = 38.975;
        double zoneLng = -77.29;
        double zoneRadiusMeters = 2500.0;
        when(graph.listZones()).thenReturn(List.of(
            new ZoneNode("zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                zoneLat, zoneLng, zoneRadiusMeters, new double[0], new double[0])
        ));

        List<String> droneIds = java.util.stream.IntStream.range(0, 16)
            .mapToObj(i -> String.format("drone-%03d", i))
            .toList();
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormationRoute(
                FormationType.RING, droneIds, 38.90, -77.40, 39.05, -77.18, "ADVANCE", 1000.0)
        ));

        List<PlanAction.SetWaypoint> waypoints = expander.expand(plan).actions().stream()
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();
        assertThat(waypoints).isNotEmpty();

        for (PlanAction.SetWaypoint w : waypoints) {
            double dist = metersBetween(w.targetLat(), w.targetLng(), zoneLat, zoneLng);
            assertThat(dist)
                .as("drone %s waypoint (%s) must clear the zone radius", w.droneId(), w.missionType())
                .isGreaterThan(zoneRadiusMeters);
        }
    }

    private static double metersBetween(double lat1, double lng1, double lat2, double lng2) {
        double midLat = (lat1 + lat2) / 2.0;
        double cos = Math.cos(Math.toRadians(midLat));
        double north = (lat2 - lat1) * 111_320.0;
        double east = (lng2 - lng1) * 111_320.0 * cos;
        return Math.hypot(north, east);
    }

    @Test
    void formationRouteGivesEachHoldLegADistinctMissionTypeSoWavesCannotMerge() {
        // Regression: PlanExecutor (and the frontend) rediscover wave boundaries by grouping
        // CONTIGUOUS setWaypoints sharing an identical mission_type string. Two zones forcing two
        // separate detour legs used to both come out as bare "HOLD" — PlanExecutor would then
        // merge them into a single wave, publish leg 2's coordinates as an immediate same-wave
        // overwrite of leg 1 for every drone (skipping leg 1 outright), and then wait forever for
        // arrival at a leg-1 target no drone would ever occupy, stalling 90s before it gave up and
        // moved on. Each HOLD leg must get its own mission_type so no two waves can collide.
        // zone-a blocks the direct line; zone-b sits on the segment from zone-a's own detour
        // point to the destination, so clearing zone-a still leaves the route inside zone-b —
        // forcing a second, distinct detour leg (verified empirically: 2 HOLD legs + dest).
        when(graph.listZones()).thenReturn(List.of(
            new ZoneNode("zone-a", "No-Fly A", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                38.975, -77.29, 3000.0, new double[0], new double[0]),
            new ZoneNode("zone-b", "No-Fly B", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                39.0053, -77.251, 2000.0, new double[0], new double[0])
        ));

        List<String> droneIds = List.of("drone-000", "drone-001", "drone-002");
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormationRoute(
                FormationType.RING, droneIds, 38.90, -77.40, 39.05, -77.18, "ADVANCE", null)
        ));

        List<PlanAction.SetWaypoint> waypoints = expander.expand(plan).actions().stream()
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();

        // Ordered, de-duplicated sequence of mission_type values as they appear in the plan.
        List<String> waveOrder = new java.util.ArrayList<>();
        for (PlanAction.SetWaypoint w : waypoints) {
            if (waveOrder.isEmpty() || !waveOrder.get(waveOrder.size() - 1).equals(w.missionType())) {
                waveOrder.add(w.missionType());
            }
        }

        long holdWaveCount = waveOrder.stream().filter(m -> m.startsWith("HOLD")).count();
        assertThat(holdWaveCount)
            .as("this geometry must actually force multiple detour legs for the test to mean anything")
            .isGreaterThanOrEqualTo(2);
        // No two adjacent waves may share a mission_type — that's exactly the collision that let
        // PlanExecutor merge them.
        for (int i = 1; i < waveOrder.size(); i++) {
            assertThat(waveOrder.get(i)).isNotEqualTo(waveOrder.get(i - 1));
        }
        assertThat(waveOrder).doesNotHaveDuplicates();
        assertThat(waveOrder.get(0)).isEqualTo("FORM_UP");
        assertThat(waveOrder.get(waveOrder.size() - 1)).isEqualTo("ADVANCE");
    }

    @Test
    void formationRouteRejectsFormUpPointInsideRestrictedZone() {
        when(graph.listZones()).thenReturn(List.of(
            new ZoneNode("zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                38.90, -77.40, 500.0, new double[0], new double[0])
        ));
        ExecutionPlan plan = new ExecutionPlan("p", "r", List.of(
            new PlanAction.ApplyFormationRoute(
                FormationType.RING, List.of("drone-000"), 38.90, -77.40, 39.05, -77.18, "ADVANCE", null)
        ));

        assertThatThrownBy(() -> expander.expand(plan)).isInstanceOf(IllegalArgumentException.class);
    }
}
