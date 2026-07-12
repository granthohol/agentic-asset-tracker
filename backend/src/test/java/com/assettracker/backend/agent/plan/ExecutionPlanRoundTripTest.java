package com.assettracker.backend.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ExecutionPlanRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String PLAN_JSON = """
        {
          "planId": "plan-001",
          "rationale": "Observe the disturbance and deploy Alpha.",
          "actions": [
            { "op": "upsertObjective", "tempId": "obj-1", "name": "Observe", "priority": 1,
              "centerLatitude": 39.05, "centerLongitude": -77.18, "radiusMeters": 250 },
            { "op": "deploySquadronToObjective", "squadronId": "squadron-alpha", "objectiveId": "$obj-1" },
            { "op": "setWaypoint", "droneId": "drone-007", "targetLat": 39.05, "targetLng": -77.18, "mission_type": "RECON" }
          ]
        }
        """;

    @Test
    void parsesPolymorphicActionsAndResolvesSubtypes() throws Exception {
        ExecutionPlan plan = mapper.readValue(PLAN_JSON, ExecutionPlan.class);

        assertThat(plan.planId()).isEqualTo("plan-001");
        assertThat(plan.actions()).hasSize(3);

        assertThat(plan.actions().get(0)).isInstanceOf(PlanAction.UpsertObjective.class);
        PlanAction.UpsertObjective obj = (PlanAction.UpsertObjective) plan.actions().get(0);
        assertThat(obj.tempId()).isEqualTo("obj-1");
        assertThat(obj.id()).isNull();
        assertThat(obj.centerLatitude()).isEqualTo(39.05);
        assertThat(obj.targetEntityId()).isNull();

        assertThat(plan.actions().get(1)).isInstanceOf(PlanAction.DeploySquadronToObjective.class);
        PlanAction.DeploySquadronToObjective deploy = (PlanAction.DeploySquadronToObjective) plan.actions().get(1);
        assertThat(deploy.objectiveId()).isEqualTo("$obj-1"); // $tempId preserved as a raw string

        assertThat(plan.actions().get(2)).isInstanceOf(PlanAction.SetWaypoint.class);
        PlanAction.SetWaypoint wp = (PlanAction.SetWaypoint) plan.actions().get(2);
        assertThat(wp.missionType()).isEqualTo("RECON"); // mission_type -> missionType mapping
    }

    @Test
    void roundTripsThroughSerializationWithoutLoss() throws Exception {
        ExecutionPlan first = mapper.readValue(PLAN_JSON, ExecutionPlan.class);
        String reserialized = mapper.writeValueAsString(first);
        ExecutionPlan second = mapper.readValue(reserialized, ExecutionPlan.class);

        // Records give us structural equality across the full polymorphic graph.
        assertThat(second).isEqualTo(first);

        // The discriminator survives serialization (so the round-trip is self-describing).
        assertThat(reserialized).contains("\"op\":\"upsertObjective\"");
        assertThat(reserialized).contains("\"mission_type\":\"RECON\"");
        // NON_NULL: optional fields that were absent stay absent.
        assertThat(reserialized).doesNotContain("targetEntityId");
    }

    private static final String ENTITY_PLAN_JSON = """
        {
          "planId": "plan-002",
          "rationale": "Mark a hostile track and a no-fly zone, then aim an objective at the track.",
          "actions": [
            { "op": "upsertTrack", "tempId": "trk-1", "name": "Bogey", "affiliation": "HOSTILE",
              "domain": "AERIAL", "latitude": 39.05, "longitude": -77.18 },
            { "op": "upsertZone", "tempId": "zone-1", "name": "No-Fly", "type": "RESTRICTED",
              "shape": "POLYGON", "vertices": [[39.0,-77.2],[39.1,-77.2],[39.1,-77.1]] },
            { "op": "upsertObjective", "tempId": "obj-1", "name": "Shadow bogey", "priority": 1,
              "targetEntityId": "$trk-1" },
            { "op": "removeZone", "id": "zone-legacy" }
          ]
        }
        """;

    @Test
    void parsesAndRoundTripsEntityOps() throws Exception {
        ExecutionPlan plan = mapper.readValue(ENTITY_PLAN_JSON, ExecutionPlan.class);
        assertThat(plan.actions()).hasSize(4);

        assertThat(plan.actions().get(0)).isInstanceOf(PlanAction.UpsertTrack.class);
        PlanAction.UpsertTrack track = (PlanAction.UpsertTrack) plan.actions().get(0);
        assertThat(track.affiliation()).isEqualTo(com.assettracker.backend.graph.Affiliation.HOSTILE);
        assertThat(track.domain()).isEqualTo(com.assettracker.backend.graph.TrackDomain.AERIAL);

        assertThat(plan.actions().get(1)).isInstanceOf(PlanAction.UpsertZone.class);
        PlanAction.UpsertZone zone = (PlanAction.UpsertZone) plan.actions().get(1);
        assertThat(zone.shape()).isEqualTo(com.assettracker.backend.graph.ZoneShape.POLYGON);
        assertThat(zone.vertices()).hasSize(3);
        assertThat(zone.vertices().get(0)).containsExactly(39.0, -77.2);

        PlanAction.UpsertObjective obj = (PlanAction.UpsertObjective) plan.actions().get(2);
        assertThat(obj.targetEntityId()).isEqualTo("$trk-1"); // entity $ref preserved

        assertThat(plan.actions().get(3)).isInstanceOf(PlanAction.RemoveZone.class);

        String reserialized = mapper.writeValueAsString(plan);
        ExecutionPlan second = mapper.readValue(reserialized, ExecutionPlan.class);
        assertThat(second).isEqualTo(plan);
        assertThat(reserialized).contains("\"op\":\"upsertTrack\"");
        assertThat(reserialized).contains("\"op\":\"removeZone\"");
    }

    @Test
    void unknownOpIsRejected() {
        String bad = """
            { "planId": "p", "rationale": "r", "actions": [ { "op": "selfDestruct", "droneId": "drone-007" } ] }
            """;
        assertThatThrownBy(() -> mapper.readValue(bad, ExecutionPlan.class))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidTypeIdException.class);
    }
}
