package com.assettracker.backend.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationType;

class PlanExpanderTest {

    private final PlanExpander expander = new PlanExpander(new FormationService());

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

        assertThat(out).hasSize(5); // objective + 2 FORM_UP + 2 ADVANCE
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
}
