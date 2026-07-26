package com.assettracker.backend.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class StubLlmClientSelectionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubLlmClient stub = new StubLlmClient(mapper);

    private final List<String> fleet = List.of(
        "drone-000", "drone-001", "drone-002", "drone-003", "drone-004"
    );

    /** Turn 2+ request with list_drones already in history. */
    private LlmRequest requestWith(String userCommand, LlmMessage... toolMessages) {
        java.util.List<LlmMessage> messages = new java.util.ArrayList<>();
        messages.add(LlmMessage.user(userCommand));
        ArrayNode drones = mapper.createArrayNode();
        drones.addObject().put("id", "drone-000").put("latitude", 39.0).put("longitude", -77.2);
        messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("c_drones", "list_drones", drones, false))));
        for (LlmMessage m : toolMessages) {
            messages.add(m);
        }
        return new LlmRequest("system", messages, mapper.createArrayNode(), 2048);
    }

    private LlmMessage zoneListResult(String id, String name, double lat, double lng) {
        ArrayNode zones = mapper.createArrayNode();
        ObjectNode zone = zones.addObject();
        zone.put("id", id).put("name", name).put("shape", "CIRCLE")
            .put("centerLatitude", lat).put("centerLongitude", lng);
        return LlmMessage.toolResults(List.of(new ToolResult("c_zones", "list_zones", zones, false)));
    }

    @Test
    void defaultsToAllAvailable() {
        assertThat(StubLlmClient.selectDroneIds("observe the area", fleet))
            .containsExactlyElementsOf(fleet);
    }

    @Test
    void respectsCountPhrase() {
        assertThat(StubLlmClient.selectDroneIds("send 3 drones in a wedge", fleet))
            .containsExactly("drone-000", "drone-001", "drone-002");
        assertThat(StubLlmClient.selectDroneIds("swarm of 2 at 39.05,-77.18", fleet))
            .containsExactly("drone-000", "drone-001");
    }

    @Test
    void respectsNamedIds() {
        assertThat(StubLlmClient.selectDroneIds("route drone-002 and drone-004", fleet))
            .containsExactly("drone-002", "drone-004");
    }

    @Test
    void namedIdsWinOverCount() {
        assertThat(StubLlmClient.selectDroneIds("send 5 drones: drone-001 and drone-003", fleet))
            .containsExactly("drone-001", "drone-003");
    }

    @Test
    void countClampsToAvailable() {
        assertThat(StubLlmClient.selectDroneIds("send 99 drones", fleet))
            .containsExactlyElementsOf(fleet);
    }

    // Map entity heuristics

    @Test
    void detectsExplicitLatLng() {
        assertThat(StubLlmClient.hasExplicitLatLng("mark a track at 39.05,-77.18")).isTrue();
        assertThat(StubLlmClient.hasExplicitLatLng("recon the no-fly zone")).isFalse();
    }

    @Test
    void parsesRadiusOrDefaults() {
        assertThat(StubLlmClient.parseRadiusMeters("no-fly zone radius 800")).isEqualTo(800.0);
        assertThat(StubLlmClient.parseRadiusMeters("patrol zone 250 meters")).isEqualTo(250.0);
        assertThat(StubLlmClient.parseRadiusMeters("no-fly zone at 39,-77")).isEqualTo(500.0);
    }

    @Test
    void createTrackIntentBuildsUpsertTrack() {
        ExecutionPlan plan = stub.tryEntityPlan(
            "mark a hostile aerial track at 39.05,-77.18", requestWith("x"));
        assertThat(plan).isNotNull();
        assertThat(plan.actions()).hasSize(1);
        PlanAction.UpsertTrack track = (PlanAction.UpsertTrack) plan.actions().get(0);
        assertThat(track.affiliation()).isEqualTo(Affiliation.HOSTILE);
        assertThat(track.latitude()).isEqualTo(39.05);
    }

    @Test
    void createZoneIntentBuildsCircleZone() {
        ExecutionPlan plan = stub.tryEntityPlan(
            "add a no-fly zone at 39.05,-77.18 radius 900", requestWith("x"));
        assertThat(plan).isNotNull();
        PlanAction.UpsertZone zone = (PlanAction.UpsertZone) plan.actions().get(0);
        assertThat(zone.type()).isEqualTo(ZoneType.RESTRICTED);
        assertThat(zone.shape()).isEqualTo(ZoneShape.CIRCLE);
        assertThat(zone.radiusMeters()).isEqualTo(900.0);
    }

    @Test
    void removeIntentResolvesIdFromToolResults() {
        LlmRequest req = requestWith(
            "remove the No-Fly zone", zoneListResult("zone-42", "No-Fly", 39.0, -77.2));
        ExecutionPlan plan = stub.tryEntityPlan("remove the No-Fly zone", req);
        assertThat(plan).isNotNull();
        PlanAction.RemoveZone remove = (PlanAction.RemoveZone) plan.actions().get(0);
        assertThat(remove.id()).isEqualTo("zone-42");
    }

    @Test
    void nonEntityPromptReturnsNull() {
        assertThat(stub.tryEntityPlan("observe the disturbance at 39.05,-77.18 with a swarm",
            requestWith("x"))).isNull();
    }

    @Test
    void wantsAvoidDetectsRestrictedPhrases() {
        assertThat(StubLlmClient.wantsAvoid("send drone-000 avoiding the restricted zone")).isTrue();
        assertThat(StubLlmClient.wantsAvoid("fly around the no-fly area")).isTrue();
        assertThat(StubLlmClient.wantsAvoid("observe the disturbance")).isFalse();
    }

    @Test
    void entityNameMatchIgnoresGenericZoneToken() {
        ObjectNode zone = mapper.createObjectNode().put("name", "Restricted Zone");
        // Prompt mentions "restricted zone" generically — full name still matches.
        assertThat(StubLlmClient.entityNameReferenced("avoid the restricted zone", zone)).isTrue();
        // Token-only match on "zone" must not fire for a differently named entity.
        ObjectNode other = mapper.createObjectNode().put("name", "Keep-Out Zone North");
        assertThat(StubLlmClient.entityNameReferenced("avoid the no-fly area", other)).isFalse();
    }

    @Test
    void avoidPromptUsesTrackNotRestrictedZoneAsAoi() {
        String cmd = "send drone-000 to Red Track 1 avoiding the restricted zone";
        ArrayNode tracks = mapper.createArrayNode();
        tracks.addObject().put("id", "trk-1").put("name", "Red Track 1")
            .put("latitude", 39.05).put("longitude", -77.18);
        ArrayNode zones = mapper.createArrayNode();
        zones.addObject().put("id", "zone-1").put("name", "Restricted Zone")
            .put("type", "RESTRICTED").put("shape", "CIRCLE")
            .put("centerLatitude", 39.025).put("centerLongitude", -77.19)
            .put("radiusMeters", 800);

        LlmRequest req = requestWith(cmd,
            LlmMessage.toolResults(List.of(
                new ToolResult("c_tr", "list_tracks", tracks, false),
                new ToolResult("c_z", "list_zones", zones, false))));

        LlmResponse step = stub.complete(req);
        assertThat(step.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(step.toolCalls().get(0).name()).isEqualTo("plan_route");
        assertThat(step.toolCalls().get(0).input().get("toLat").asDouble()).isEqualTo(39.05);
        assertThat(step.toolCalls().get(0).input().get("toLng").asDouble()).isEqualTo(-77.18);
        // Must not route into the restricted zone center.
        assertThat(step.toolCalls().get(0).input().get("toLat").asDouble()).isNotEqualTo(39.025);
    }

    @Test
    void planRouteErrorDoesNotSilentlyGoDirect() {
        ObjectNode err = mapper.createObjectNode().put("error", "destination is inside restricted zone 'z1'");
        assertThat(StubLlmClient.routeHasError(err)).isTrue();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> StubLlmClient.requireSuccessfulRoute(err));
    }

    @Test
    void wantsNamedFormUpDetectsRallyLanguage() {
        assertThat(StubLlmClient.wantsNamedFormUp(
            "form a drone swarm at the waypoint \"rally\", then send to red track 1")).isTrue();
        assertThat(StubLlmClient.wantsNamedFormUp("assemble at rally then advance")).isTrue();
        assertThat(StubLlmClient.wantsNamedFormUp("observe the disturbance at 39.05,-77.18")).isFalse();
    }

    @Test
    void formAtRallyThenTrackUsesWaypointForFormUpAndTrackForAdvance() {
        String cmd = "Form a drone swarm at the waypoint \"Rally\", then once formed, send the swarm to Red Track 1.";
        ArrayNode waypoints = mapper.createArrayNode();
        waypoints.addObject().put("id", "wp-rally").put("name", "Rally")
            .put("latitude", 38.90).put("longitude", -77.40);
        ArrayNode tracks = mapper.createArrayNode();
        tracks.addObject().put("id", "trk-1").put("name", "Red Track 1")
            .put("latitude", 39.05).put("longitude", -77.18);

        LlmRequest req1 = requestWith(cmd,
            LlmMessage.toolResults(List.of(
                new ToolResult("c_wp", "list_waypoints", waypoints, false),
                new ToolResult("c_tr", "list_tracks", tracks, false))));

        LlmResponse step1 = stub.complete(req1);
        assertThat(step1.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(step1.toolCalls().get(0).name()).isEqualTo("preview_two_phase");
        assertThat(step1.toolCalls().get(0).input().get("aoiLat").asDouble()).isEqualTo(39.05);
        assertThat(step1.toolCalls().get(0).input().get("aoiLng").asDouble()).isEqualTo(-77.18);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("formationType", "RING");
        summary.put("droneCount", 5);
        summary.putObject("formUpCenter").put("lat", 39.032).put("lng", -77.18);
        summary.putObject("advanceCenter").put("lat", 39.05).put("lng", -77.18);

        java.util.List<LlmMessage> messages = new java.util.ArrayList<>(req1.messages());
        messages.add(LlmMessage.assistant(step1.text(), step1.toolCalls()));
        messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("call_preview_two_phase", "preview_two_phase", summary, false))));
        LlmResponse step2 = stub.complete(new LlmRequest("system", messages, mapper.createArrayNode(), 2048));
        assertThat(step2.stopReason()).isEqualTo(LlmResponse.StopReason.END);

        ExecutionPlan plan;
        try {
            plan = mapper.readValue(step2.text(), ExecutionPlan.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        PlanAction.ApplyFormationRoute route = (PlanAction.ApplyFormationRoute) plan.actions().stream()
            .filter(a -> a instanceof PlanAction.ApplyFormationRoute)
            .findFirst().orElseThrow();

        // FORM_UP at Rally — not the standoff near Red Track from preview_two_phase — and the
        // destination is Red Track 1. The backend (PlanExpander) resolves the actual route.
        assertThat(route.formUpLat()).isEqualTo(38.90);
        assertThat(route.formUpLng()).isEqualTo(-77.40);
        assertThat(route.destLat()).isEqualTo(39.05);
        assertThat(route.destLng()).isEqualTo(-77.18);
    }

    @Test
    void singleDroneAvoidRequestsPlanRouteThenEmitsSetRoute() {
        String cmd = "send drone-000 to 39.05,-77.18 avoid the restricted zone";
        LlmRequest req1 = requestWith(cmd, zoneListResult("zone-1", "Restricted Zone", 39.025, -77.19));
        LlmResponse step1 = stub.complete(req1);
        assertThat(step1.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(step1.toolCalls()).hasSize(1);
        assertThat(step1.toolCalls().get(0).name()).isEqualTo("plan_route");

        ObjectNode route = mapper.createObjectNode();
        ArrayNode legs = route.putArray("legs");
        legs.addObject().put("lat", 39.03).put("lng", -77.17);
        legs.addObject().put("lat", 39.05).put("lng", -77.18);
        route.putArray("avoidedZoneIds").add("zone-1");
        route.put("direct", false);

        java.util.List<LlmMessage> messages = new java.util.ArrayList<>(req1.messages());
        messages.add(LlmMessage.assistant(step1.text(), step1.toolCalls()));
        messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("call_plan_route", "plan_route", route, false))));
        LlmRequest req2 = new LlmRequest("system", messages, mapper.createArrayNode(), 2048);

        LlmResponse step2 = stub.complete(req2);
        assertThat(step2.stopReason()).isEqualTo(LlmResponse.StopReason.END);
        ExecutionPlan plan;
        try {
            plan = mapper.readValue(step2.text(), ExecutionPlan.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(plan.actions().stream().anyMatch(a -> a instanceof PlanAction.SetRoute)).isTrue();
        PlanAction.SetRoute setRoute = (PlanAction.SetRoute) plan.actions().stream()
            .filter(a -> a instanceof PlanAction.SetRoute).findFirst().orElseThrow();
        assertThat(setRoute.droneId()).isEqualTo("drone-000");
        assertThat(setRoute.legs()).hasSize(2);
    }

    @Test
    void swarmAvoidWithNamedRallyEmitsSingleFormationRouteFromRally() {
        // requestWith()'s fixture only has one drone, which would (correctly) take the
        // single-drone avoid path instead of the swarm path this test targets, so build the
        // fleet inline with enough drones to select a genuine swarm.
        String cmd = "Send 5 drones to form up in a wedge at the waypoint \"Rally\", "
            + "then once formed up, send them to \"Red Track 1\" while avoiding the restricted zone.";
        ArrayNode drones = mapper.createArrayNode();
        for (String id : fleet) {
            drones.addObject().put("id", id).put("latitude", 39.0).put("longitude", -77.2);
        }
        ArrayNode waypoints = mapper.createArrayNode();
        waypoints.addObject().put("id", "wp-rally").put("name", "Rally")
            .put("latitude", 38.90).put("longitude", -77.40);
        ArrayNode tracks = mapper.createArrayNode();
        tracks.addObject().put("id", "trk-1").put("name", "Red Track 1")
            .put("latitude", 39.05).put("longitude", -77.18);

        java.util.List<LlmMessage> req1Messages = new java.util.ArrayList<>();
        req1Messages.add(LlmMessage.user(cmd));
        req1Messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("c_drones", "list_drones", drones, false))));
        req1Messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("c_wp", "list_waypoints", waypoints, false),
            new ToolResult("c_tr", "list_tracks", tracks, false))));
        LlmRequest req1 = new LlmRequest("system", req1Messages, mapper.createArrayNode(), 2048);

        LlmResponse step1 = stub.complete(req1);
        assertThat(step1.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(step1.toolCalls().get(0).name()).isEqualTo("preview_two_phase");

        ObjectNode summary = mapper.createObjectNode();
        summary.put("formationType", "WEDGE");
        summary.put("droneCount", 5);
        summary.putObject("formUpCenter").put("lat", 39.032).put("lng", -77.18);
        summary.putObject("advanceCenter").put("lat", 39.05).put("lng", -77.18);

        java.util.List<LlmMessage> messages = new java.util.ArrayList<>(req1.messages());
        messages.add(LlmMessage.assistant(step1.text(), step1.toolCalls()));
        messages.add(LlmMessage.toolResults(List.of(
            new ToolResult("call_preview_two_phase", "preview_two_phase", summary, false))));
        LlmResponse step2 = stub.complete(new LlmRequest("system", messages, mapper.createArrayNode(), 2048));

        // Regression: the stub no longer hand-composes a route (no plan_route call, no manual
        // HOLD waves) — it emits ONE applyFormationRoute whose formUpLat/Lng is the named rally
        // point (not the leader's raw starting position or the computed standoff center). The
        // backend (PlanExpander) is solely responsible for the actual avoidance route, which
        // guarantees every wave — FORM_UP, any HOLD, ADVANCE — carries the full swarm.
        assertThat(step2.stopReason()).isEqualTo(LlmResponse.StopReason.END);
        ExecutionPlan plan;
        try {
            plan = mapper.readValue(step2.text(), ExecutionPlan.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        PlanAction.ApplyFormationRoute route = (PlanAction.ApplyFormationRoute) plan.actions().stream()
            .filter(a -> a instanceof PlanAction.ApplyFormationRoute)
            .findFirst().orElseThrow();
        assertThat(route.droneIds()).hasSize(5);
        assertThat(route.formUpLat()).isEqualTo(38.90);
        assertThat(route.formUpLng()).isEqualTo(-77.40);
        assertThat(route.destLat()).isEqualTo(39.05);
        assertThat(route.destLng()).isEqualTo(-77.18);
    }
}
