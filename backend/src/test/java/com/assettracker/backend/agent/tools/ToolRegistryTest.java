package com.assettracker.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.graph.DroneNode;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.SquadronNode;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;
import com.assettracker.backend.model.DroneStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ToolRegistryTest {

    private final GraphService graph = Mockito.mock(GraphService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(graph, new FormationService(), mapper);

    @Test
    void specsAreWellFormedAndCoverTheReadApi() {
        JsonNode specs = registry.toolSpecs();
        assertThat(specs.isArray()).isTrue();

        List<String> names = specs.findValuesAsText("name");
        assertThat(names).contains(
            "list_squadrons", "list_objectives", "list_drones", "get_drone_by_id",
            "get_drones_in_squadron", "get_drones_by_status", "get_low_battery_drones",
            "get_low_battery_drones_in_sector", "get_squadrons_for_objective", "get_drones_near",
            "list_formations", "preview_formation",
            "list_tracks", "list_waypoints", "list_zones",
            "get_track_by_id", "get_waypoint_by_id", "get_zone_by_id"
        );
        assertThat(names).hasSize(18);

        for (JsonNode spec : specs) {
            assertThat(spec.hasNonNull("name")).isTrue();
            assertThat(spec.hasNonNull("description")).isTrue();
            JsonNode schema = spec.get("input_schema");
            assertThat(schema.get("type").asText()).isEqualTo("object");
            assertThat(schema.has("properties")).isTrue();
            assertThat(schema.has("required")).isTrue();
        }
    }

    @Test
    void invokeListSquadronsReturnsJsonArray() {
        when(graph.listSquadrons()).thenReturn(List.of(
            new SquadronNode("squadron-alpha", "Alpha", "sector-1")
        ));

        JsonNode result = registry.invoke("list_squadrons", null);

        assertThat(result.isArray()).isTrue();
        assertThat(result.get(0).get("id").asText()).isEqualTo("squadron-alpha");
        assertThat(result.get(0).get("sectorId").asText()).isEqualTo("sector-1");
    }

    @Test
    void invokeListFormationsReturnsCatalog() {
        JsonNode result = registry.invoke("list_formations", null);
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(3);
        assertThat(result.findValuesAsText("type")).contains("RING", "WEDGE", "LINE");
    }

    @Test
    void invokePreviewFormationHappyPath() {
        ObjectNode args = mapper.createObjectNode();
        args.put("type", "RING");
        args.put("centerLatitude", 39.05);
        args.put("centerLongitude", -77.18);
        ArrayNode ids = args.putArray("droneIds");
        ids.add("drone-000");
        ids.add("drone-001");
        ids.add("drone-002");

        JsonNode result = registry.invoke("preview_formation", args);

        assertThat(result.get("type").asText()).isEqualTo("RING");
        assertThat(result.get("slots")).hasSize(3);
        assertThat(result.get("slots").get(0).get("droneId").asText()).isEqualTo("drone-000");
        assertThat(result.get("slots").get(0).has("targetLat")).isTrue();
        assertThat(result.get("slots").get(0).has("targetLng")).isTrue();
    }

    @Test
    void invokePreviewFormationBadTypeThrows() {
        ObjectNode args = mapper.createObjectNode();
        args.put("type", "SPIRAL");
        args.put("centerLatitude", 39.05);
        args.put("centerLongitude", -77.18);
        args.putArray("droneIds").add("drone-000");

        assertThatThrownBy(() -> registry.invoke("preview_formation", args))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown formation type");
    }

    @Test
    void invokeLowBatteryPassesThresholdThrough() {
        when(graph.getLowBatteryDrones(30)).thenReturn(List.of(
            new DroneNode("drone-007", 39.0, -77.2, 12, DroneStatus.LOW_BATTERY, null)
        ));

        ObjectNode args = mapper.createObjectNode();
        args.put("threshold", 30);
        JsonNode result = registry.invoke("get_low_battery_drones", args);

        verify(graph).getLowBatteryDrones(30);
        assertThat(result.get(0).get("id").asText()).isEqualTo("drone-007");
        assertThat(result.get(0).get("batteryLevel").asInt()).isEqualTo(12);
    }

    @Test
    void invokeByStatusMapsEnum() {
        when(graph.getDronesByStatus(DroneStatus.ACTIVE)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("status", "ACTIVE");
        registry.invoke("get_drones_by_status", args);

        verify(graph).getDronesByStatus(eq(DroneStatus.ACTIVE));
    }

    @Test
    void getDroneByIdNotFoundReturnsFoundFalse() {
        when(graph.getDroneById("nope")).thenReturn(Optional.empty());

        ObjectNode args = mapper.createObjectNode();
        args.put("droneId", "nope");
        JsonNode result = registry.invoke("get_drone_by_id", args);

        assertThat(result.get("found").asBoolean()).isFalse();
        assertThat(result.get("droneId").asText()).isEqualTo("nope");
    }

    @Test
    void invokeListZonesReturnsJsonArray() {
        when(graph.listZones()).thenReturn(List.of(
            new ZoneNode("zone-1", "No-Fly", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                39.05, -77.18, 800.0, new double[0], new double[0])
        ));

        JsonNode result = registry.invoke("list_zones", null);

        assertThat(result.isArray()).isTrue();
        assertThat(result.get(0).get("id").asText()).isEqualTo("zone-1");
        assertThat(result.get(0).get("type").asText()).isEqualTo("RESTRICTED");
    }

    @Test
    void getZoneByIdNotFoundReturnsFoundFalse() {
        when(graph.getZoneById("nope")).thenReturn(Optional.empty());

        ObjectNode args = mapper.createObjectNode();
        args.put("id", "nope");
        JsonNode result = registry.invoke("get_zone_by_id", args);

        assertThat(result.get("found").asBoolean()).isFalse();
        assertThat(result.get("id").asText()).isEqualTo("nope");
    }

    @Test
    void unknownToolThrows() {
        assertThatThrownBy(() -> registry.invoke("drop_database", mapper.createObjectNode()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown tool");
    }

    @Test
    void missingRequiredArgThrows() {
        assertThatThrownBy(() -> registry.invoke("get_drones_in_squadron", mapper.createObjectNode()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("squadronId");
    }
}
