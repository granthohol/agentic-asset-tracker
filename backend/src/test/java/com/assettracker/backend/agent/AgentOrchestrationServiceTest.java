package com.assettracker.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.llm.StubLlmClient;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.agent.plan.PlanExpander;
import com.assettracker.backend.agent.tools.ToolRegistry;
import com.assettracker.backend.graph.DroneNode;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.SquadronNode;
import com.assettracker.backend.model.DroneStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgentOrchestrationServiceTest {

    private final GraphService graph = Mockito.mock(GraphService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(graph, new FormationService(), mapper);
    private final AgentOrchestrationService orchestrator = new AgentOrchestrationService(
        new StubLlmClient(mapper), registry, new PlanExpander(new FormationService()), mapper);

    @Test
    void twoPhaseSwarmPlanHasFormUpThenAdvance() {
        when(graph.listSquadrons()).thenReturn(List.of(new SquadronNode("squadron-alpha", "Alpha", "sector-1")));
        when(graph.listDrones()).thenReturn(IntStream.range(0, 6)
            .mapToObj(i -> new DroneNode(
                "drone-00" + i, 39.0 + i * 0.01, -77.2, 80, DroneStatus.ACTIVE, null))
            .toList());

        ExecutionPlan plan = orchestrator.planFromPrompt(
            "Observe the disturbance at 39.05,-77.18 with a swarm in a wedge");

        verify(graph).listSquadrons();
        verify(graph).listDrones();

        assertThat(plan.rationale()).containsIgnoringCase("WEDGE");
        assertThat(plan.rationale()).containsIgnoringCase("form up");
        assertThat(plan.rationale()).containsIgnoringCase("ADVANCE");
        assertThat(plan.rationale()).contains("drone-000");

        List<PlanAction.SetWaypoint> waypoints = plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint)
            .map(a -> (PlanAction.SetWaypoint) a)
            .toList();

        assertThat(waypoints).hasSize(12); // all 6 available × 2 phases
        List<PlanAction.SetWaypoint> formUps = waypoints.stream()
            .filter(w -> "FORM_UP".equals(w.missionType())).toList();
        List<PlanAction.SetWaypoint> advances = waypoints.stream()
            .filter(w -> "ADVANCE".equals(w.missionType())).toList();
        assertThat(formUps).hasSize(6);
        assertThat(advances).hasSize(6);

        double formUpAvgLat = formUps.stream().mapToDouble(PlanAction.SetWaypoint::targetLat).average().orElse(0);
        double advanceAvgLat = advances.stream().mapToDouble(PlanAction.SetWaypoint::targetLat).average().orElse(0);
        assertThat(Math.abs(advanceAvgLat - 39.05)).isLessThan(0.01);
        assertThat(Math.abs(formUpAvgLat - 39.05)).isGreaterThan(0.005);

        int lastFormUp = -1;
        int firstAdvance = Integer.MAX_VALUE;
        for (int i = 0; i < plan.actions().size(); i++) {
            if (plan.actions().get(i) instanceof PlanAction.SetWaypoint sw) {
                if ("FORM_UP".equals(sw.missionType())) {
                    lastFormUp = i;
                }
                if ("ADVANCE".equals(sw.missionType()) && firstAdvance == Integer.MAX_VALUE) {
                    firstAdvance = i;
                }
            }
        }
        assertThat(lastFormUp).isLessThan(firstAdvance);
    }

    @Test
    void unspecifiedCountUsesAllAvailableDrones() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(IntStream.range(0, 12)
            .mapToObj(i -> new DroneNode(
                "drone-" + String.format("%03d", i), 39.0, -77.2, 80, DroneStatus.ACTIVE, null))
            .toList());

        ExecutionPlan plan = orchestrator.planFromPrompt("Swarm recon at 39.05,-77.18");

        long formUps = plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "FORM_UP".equals(sw.missionType()))
            .count();
        long advances = plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "ADVANCE".equals(sw.missionType()))
            .count();
        assertThat(formUps).isEqualTo(12);
        assertThat(advances).isEqualTo(12);
    }

    @Test
    void promptCountLimitsSwarmSize() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(IntStream.range(0, 10)
            .mapToObj(i -> new DroneNode(
                "drone-" + String.format("%03d", i), 39.0, -77.2, 80, DroneStatus.ACTIVE, null))
            .toList());

        ExecutionPlan plan = orchestrator.planFromPrompt(
            "Send 3 drones in a wedge to 39.05,-77.18");

        long formUps = plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "FORM_UP".equals(sw.missionType()))
            .count();
        assertThat(formUps).isEqualTo(3);
        assertThat(plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "FORM_UP".equals(sw.missionType()))
            .map(a -> ((PlanAction.SetWaypoint) a).droneId())
            .toList()).containsExactly("drone-000", "drone-001", "drone-002");
    }

    @Test
    void promptNamedDronesSelectsThoseIds() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(IntStream.range(0, 8)
            .mapToObj(i -> new DroneNode(
                "drone-" + String.format("%03d", i), 39.0, -77.2, 80, DroneStatus.ACTIVE, null))
            .toList());

        ExecutionPlan plan = orchestrator.planFromPrompt(
            "Send drone-002 and drone-005 in a line to 39.05,-77.18");

        List<String> formUpIds = plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetWaypoint sw && "FORM_UP".equals(sw.missionType()))
            .map(a -> ((PlanAction.SetWaypoint) a).droneId())
            .toList();
        assertThat(formUpIds).containsExactly("drone-002", "drone-005");
        assertThat(plan.rationale()).contains("drone-002");
    }

    @Test
    void creationPromptYieldsUpsertTrackAndNoWaypoints() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(List.of());
        when(graph.listTracks()).thenReturn(List.of());
        when(graph.listWaypoints()).thenReturn(List.of());
        when(graph.listZones()).thenReturn(List.of());

        ExecutionPlan plan = orchestrator.planFromPrompt(
            "Mark a hostile aerial track at 39.05,-77.18");

        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().get(0)).isInstanceOf(PlanAction.UpsertTrack.class);
        PlanAction.UpsertTrack track = (PlanAction.UpsertTrack) plan.actions().get(0);
        assertThat(track.affiliation())
            .isEqualTo(com.assettracker.backend.graph.Affiliation.HOSTILE);
        assertThat(track.domain())
            .isEqualTo(com.assettracker.backend.graph.TrackDomain.AERIAL);
        assertThat(track.latitude()).isEqualTo(39.05);
        assertThat(track.longitude()).isEqualTo(-77.18);
        assertThat(plan.actions()).noneMatch(a -> a instanceof PlanAction.SetWaypoint);
    }

    @Test
    void mintsSquadronWhenGraphIsEmpty() {
        when(graph.listSquadrons()).thenReturn(List.of());
        when(graph.listDrones()).thenReturn(List.of());

        ExecutionPlan plan = orchestrator.planFromPrompt("Set up an observation objective");

        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a).isInstanceOf(PlanAction.UpsertSquadron.class);
            assertThat(((PlanAction.UpsertSquadron) a).tempId()).isEqualTo("squad-1");
        });
        assertThat(plan.actions()).noneMatch(a -> a instanceof PlanAction.SetWaypoint);
    }
}
