package com.assettracker.backend.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    private ExecutionPlan plan(PlanAction... actions) {
        return new ExecutionPlan("plan-1", "r", List.of(actions));
    }

    @Test
    void acceptsValidTempIdChain() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, 39.05, -77.18, 250.0, null),
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.05, -77.18, "RECON")
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyPlan() {
        assertThatThrownBy(() -> validator.validate(plan()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no actions");
    }

    @Test
    void rejectsUnresolvedRef() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolved reference");
    }

    @Test
    void rejectsRefThatComesBeforeDeclaration() {
        // Deploy references $obj-1 before the upsert that declares it.
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, null, null, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolved reference");
    }

    @Test
    void rejectsBothIdAndTempId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertSquadron("squadron-alpha", "squad-1", "Alpha", "sector-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one of id or tempId");
    }

    @Test
    void rejectsNeitherIdNorTempId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertSquadron(null, null, "Alpha", "sector-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one of id or tempId");
    }

    @Test
    void rejectsOutOfRangeWaypoint() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.SetWaypoint("drone-007", 200.0, -77.18, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void acceptsValidApplyFormation() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.ApplyFormation(FormationType.RING, 39.05, -77.18,
                List.of("drone-000", "drone-001"), "FORM_UP", null, 39.05, -77.18)
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsApplyFormationWithEmptyDroneIds() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.ApplyFormation(FormationType.RING, 39.05, -77.18,
                List.of(), "FORM_UP", null, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("droneIds");
    }

    @Test
    void rejectsApplyFormationWithDollarRefDrone() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.ApplyFormation(FormationType.RING, 39.05, -77.18,
                List.of("$drone-1"), "FORM_UP", null, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("literal id");
    }

    @Test
    void rejectsApplyFormationOutOfRangeCenter() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.ApplyFormation(FormationType.RING, 200.0, -77.18,
                List.of("drone-000"), "FORM_UP", null, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void rejectsDollarRefOnDroneId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.SetWaypoint("$drone-1", 39.0, -77.0, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("literal id");
    }

    @Test
    void acceptsValidTrackUpsert() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.UpsertTrack(
                null, "track-1", "Bogey", Affiliation.HOSTILE, TrackDomain.AERIAL, 39.05, -77.18)
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTrackMissingAffiliation() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertTrack(null, "track-1", "Bogey", null, TrackDomain.AERIAL, 39.05, -77.18)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("affiliation and domain");
    }

    @Test
    void rejectsTrackMissingCoordinates() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertTrack(null, "track-1", "Bogey", Affiliation.HOSTILE, TrackDomain.AERIAL, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("latitude and longitude");
    }

    @Test
    void acceptsValidCircleZone() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.UpsertZone(null, "zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                39.05, -77.18, 800.0, null)
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsCircleZoneWithoutRadius() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertZone(null, "zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                39.05, -77.18, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("radiusMeters");
    }

    @Test
    void rejectsPolygonZoneWithTooFewVertices() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertZone(null, "zone-1", "Box", ZoneType.PATROL, ZoneShape.POLYGON,
                null, null, null, List.of(List.of(39.0, -77.2), List.of(39.1, -77.2)))
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 3 vertices");
    }

    @Test
    void acceptsValidPolygonZone() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.UpsertZone(null, "zone-1", "Box", ZoneType.PATROL, ZoneShape.POLYGON,
                null, null, null, List.of(
                    List.of(39.0, -77.2), List.of(39.1, -77.2), List.of(39.1, -77.1)))
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoveWithDollarRef() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.RemoveTrack("$track-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("literal id");
    }
}
