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
}
