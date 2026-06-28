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

import com.assettracker.backend.graph.DroneNode;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.SquadronNode;
import com.assettracker.backend.model.DroneStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ToolRegistryTest {

    private final GraphService graph = Mockito.mock(GraphService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(graph, mapper);

    @Test
    void specsAreWellFormedAndCoverTheReadApi() {
        JsonNode specs = registry.toolSpecs();
        assertThat(specs.isArray()).isTrue();

        List<String> names = specs.findValuesAsText("name");
        assertThat(names).contains(
            "list_squadrons", "list_objectives", "list_drones", "get_drone_by_id",
            "get_drones_in_squadron", "get_drones_by_status", "get_low_battery_drones",
            "get_low_battery_drones_in_sector", "get_squadrons_for_objective", "get_drones_near"
        );

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
