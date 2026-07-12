package com.assettracker.backend.agent.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.model.DroneStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The set of read-only tools the LLM is given during planning, plus a single seam to
 * invoke them by name and to emit their provider-facing specs.
 *
 * <p><b>Architectural invariant:</b> this class (and the whole {@code agent} package)
 * depends only on {@link GraphService} (reads) and other read-only helpers such as
 * {@link FormationService}. It must never import
 * {@code com.assettracker.backend.graph.GraphWriter}. That lexical boundary is what makes
 * "the LLM can read but never mutate" a guarantee rather than a hope.
 *
 * <p>The registry is also the reuse seam for the MCP stretch goal: the same tools can be
 * exposed over an MCP transport without rewriting any graph logic.
 */
@Component
public class ToolRegistry {

    private final GraphService graph;
    private final FormationService formations;
    private final ObjectMapper mapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(GraphService graph, FormationService formations, ObjectMapper mapper) {
        this.graph = graph;
        this.formations = formations;
        this.mapper = mapper;
        registerAll();
    }

    private void registerAll() {
        register(new Tool(
            "list_squadrons",
            "List every squadron in the C2 graph (id, name, sectorId). Call this first to discover which squadrons exist before referencing one.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listSquadrons())
        ));

        register(new Tool(
            "list_objectives",
            "List every objective in the C2 graph (id, name, priority, optional location). Use this to find existing objectives before creating a new one.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listObjectives())
        ));

        register(new Tool(
            "list_drones",
            "List every drone known to the graph (id, position, batteryLevel, status, currentWaypoint). Prefer a narrower tool when you can; this can be large.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listDrones())
        ));

        register(new Tool(
            "get_drone_by_id",
            "Get one drone fully hydrated with its squadron and that squadron's objective (if any). Returns {\"found\": false} when the id is unknown.",
            objectSchema(props("droneId", field("string", "The drone id, e.g. 'drone-007'.")), "droneId"),
            args -> graph.getDroneById(requireString(args, "droneId"))
                .map(d -> (JsonNode) mapper.valueToTree(d))
                .orElseGet(() -> notFound("droneId", optString(args, "droneId")))
        ));

        register(new Tool(
            "get_drones_in_squadron",
            "List the drones currently assigned to a given squadron.",
            objectSchema(props("squadronId", field("string", "The squadron id, e.g. 'squadron-alpha'.")), "squadronId"),
            args -> mapper.valueToTree(graph.getDronesInSquadron(requireString(args, "squadronId")))
        ));

        register(new Tool(
            "get_drones_by_status",
            "List drones in a given operational status. Useful to find drones that need attention.",
            objectSchema(props("status", enumField("Drone status.", "ACTIVE", "LOW_BATTERY", "OFFLINE")), "status"),
            args -> mapper.valueToTree(graph.getDronesByStatus(DroneStatus.valueOf(requireString(args, "status"))))
        ));

        register(new Tool(
            "get_low_battery_drones",
            "List drones whose batteryLevel is strictly below a threshold (0-100). Use to find candidates for return-to-base.",
            objectSchema(props("threshold", field("integer", "Battery percentage cutoff (0-100); drones below this are returned.")), "threshold"),
            args -> mapper.valueToTree(graph.getLowBatteryDrones(requireInt(args, "threshold")))
        ));

        register(new Tool(
            "get_low_battery_drones_in_sector",
            "List low-battery drones assigned to squadrons in a given sector. Combines a battery cutoff with a sector filter.",
            objectSchema(
                props(
                    "sectorId", field("string", "The sector id to filter squadrons by, e.g. 'sector-1'."),
                    "threshold", field("integer", "Battery percentage cutoff (0-100).")
                ),
                "sectorId", "threshold"
            ),
            args -> mapper.valueToTree(graph.getLowBatteryDronesInSector(requireString(args, "sectorId"), requireInt(args, "threshold")))
        ));

        register(new Tool(
            "get_squadrons_for_objective",
            "List the squadrons currently deployed for a given objective.",
            objectSchema(props("objectiveId", field("string", "The objective id, e.g. 'objective-recon'.")), "objectiveId"),
            args -> mapper.valueToTree(graph.getSquadronsForObjective(requireString(args, "objectiveId")))
        ));

        register(new Tool(
            "get_drones_near",
            "List drones within a radius (in kilometers) of a latitude/longitude point. Use to find assets near a disturbance.",
            objectSchema(
                props(
                    "lat", field("number", "Center latitude in decimal degrees."),
                    "lng", field("number", "Center longitude in decimal degrees."),
                    "radiusKm", field("number", "Search radius in kilometers.")
                ),
                "lat", "lng", "radiusKm"
            ),
            args -> mapper.valueToTree(graph.getDronesNear(requireDouble(args, "lat"), requireDouble(args, "lng"), requireDouble(args, "radiusKm")))
        ));

        register(new Tool(
            "list_formations",
            "List named geometric formations the planner can use for swarm routing (RING, WEDGE, LINE). "
                + "Call this before preview_formation when the operator asks for a swarm or formation. "
                + "Formations are read-only previews — emit setWaypoint actions from the preview slots; do not invent offsets.",
            objectSchema(props()),
            args -> mapper.valueToTree(formations.listSpecs())
        ));

        ObjectNode droneIdsField = field("array", "Ordered drone ids to place into formation slots (from list_drones).");
        droneIdsField.set("items", field("string", "A drone id, e.g. 'drone-000'."));

        register(new Tool(
            "preview_formation",
            "Compute concrete target lat/lng for each drone in a formation around a center. "
                + "Optional facingLatitude/facingLongitude rotate the formation so WEDGE/LINE point "
                + "at that location (RING is unchanged visually). "
                + "Returns slots[{index, droneId, targetLat, targetLng}]. "
                + "Emit setWaypoint per slot (FORM_UP then ADVANCE for two-phase swarms). "
                + "Does not move drones — planning only.",
            objectSchema(
                props(
                    "type", enumField("Formation type from list_formations.", "RING", "WEDGE", "LINE"),
                    "centerLatitude", field("number", "Formation center latitude."),
                    "centerLongitude", field("number", "Formation center longitude."),
                    "droneIds", droneIdsField,
                    "spacingMeters", field("number", "Optional spacing between slots in meters (default ~200)."),
                    "facingLatitude", field("number", "Optional point the formation should face (e.g. AOI lat)."),
                    "facingLongitude", field("number", "Optional point the formation should face (e.g. AOI lng).")
                ),
                "type", "centerLatitude", "centerLongitude", "droneIds"
            ),
            args -> mapper.valueToTree(formations.preview(
                FormationType.parse(requireString(args, "type")),
                requireDouble(args, "centerLatitude"),
                requireDouble(args, "centerLongitude"),
                requireStringList(args, "droneIds"),
                optDouble(args, "spacingMeters"),
                optDouble(args, "facingLatitude"),
                optDouble(args, "facingLongitude")
            ))
        ));

        // --- persistent map entities (tracks / waypoints / zones) -----------
        register(new Tool(
            "list_tracks",
            "List every persistent map track (id, name, affiliation, domain, latitude, longitude). "
                + "Tracks are static contacts (FRIENDLY/HOSTILE/UNKNOWN, AERIAL/GROUND), not drones. "
                + "Call before referencing or removing a track by id.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listTracks())
        ));

        register(new Tool(
            "list_waypoints",
            "List every persistent map waypoint (id, name, latitude, longitude). These are durable, "
                + "labeled points of interest, distinct from a drone's ephemeral motion target.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listWaypoints())
        ));

        register(new Tool(
            "list_zones",
            "List every map zone (id, name, type RESTRICTED/PATROL, shape CIRCLE/POLYGON, and its "
                + "geometry). Use to find a named area (e.g. a no-fly or patrol zone) and its center "
                + "before routing drones toward or around it.",
            objectSchema(props()),
            args -> mapper.valueToTree(graph.listZones())
        ));

        register(new Tool(
            "get_track_by_id",
            "Get one map track by id. Returns {\"found\": false} when the id is unknown.",
            objectSchema(props("id", field("string", "The track id, e.g. 'track-1a2b3c4d'.")), "id"),
            args -> graph.getTrackById(requireString(args, "id"))
                .map(t -> (JsonNode) mapper.valueToTree(t))
                .orElseGet(() -> notFound("id", optString(args, "id")))
        ));

        register(new Tool(
            "get_waypoint_by_id",
            "Get one map waypoint by id. Returns {\"found\": false} when the id is unknown.",
            objectSchema(props("id", field("string", "The waypoint id.")), "id"),
            args -> graph.getWaypointById(requireString(args, "id"))
                .map(w -> (JsonNode) mapper.valueToTree(w))
                .orElseGet(() -> notFound("id", optString(args, "id")))
        ));

        register(new Tool(
            "get_zone_by_id",
            "Get one map zone by id. Returns {\"found\": false} when the id is unknown.",
            objectSchema(props("id", field("string", "The zone id.")), "id"),
            args -> graph.getZoneById(requireString(args, "id"))
                .map(z -> (JsonNode) mapper.valueToTree(z))
                .orElseGet(() -> notFound("id", optString(args, "id")))
        ));
    }

    // --- public seam ---------------------------------------------------------

    public Collection<Tool> all() {
        return tools.values();
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** Invoke a tool by name with a JSON arguments object; returns its JSON result. */
    public JsonNode invoke(String name, JsonNode args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        JsonNode safeArgs = (args == null || args.isNull()) ? mapper.createObjectNode() : args;
        return tool.invoke().apply(safeArgs);
    }

    /** Tool specs pre-serialized to a JSON string (for the web layer / debug endpoint). */
    public String toolSpecsJson() {
        try {
            return mapper.writeValueAsString(toolSpecs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool specs", e);
        }
    }

    /** Provider-facing tool specs: [{ name, description, input_schema }]. */
    public ArrayNode toolSpecs() {
        ArrayNode arr = mapper.createArrayNode();
        for (Tool tool : tools.values()) {
            ObjectNode spec = mapper.createObjectNode();
            spec.put("name", tool.name());
            spec.put("description", tool.description());
            spec.set("input_schema", tool.inputSchema());
            arr.add(spec);
        }
        return arr;
    }

    // --- JSON Schema helpers -------------------------------------------------

    /** Build a JSON Schema {@code properties} object from alternating (name, fieldSchema) pairs. */
    private ObjectNode props(Object... pairs) {
        ObjectNode p = mapper.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            p.set((String) pairs[i], (ObjectNode) pairs[i + 1]);
        }
        return p;
    }

    private ObjectNode field(String type, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.put("description", description);
        return n;
    }

    private ObjectNode enumField(String description, String... values) {
        ObjectNode n = field("string", description);
        ArrayNode en = mapper.createArrayNode();
        for (String v : values) {
            en.add(v);
        }
        n.set("enum", en);
        return n;
    }

    private ObjectNode objectSchema(ObjectNode properties, String... required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = mapper.createArrayNode();
        for (String r : required) {
            req.add(r);
        }
        schema.set("required", req);
        schema.put("additionalProperties", false);
        return schema;
    }

    // --- argument extraction (throws IllegalArgumentException the loop surfaces back to the model) ---

    private String requireString(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.asText();
    }

    private String optString(JsonNode args, String key) {
        JsonNode v = args.get(key);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private int requireInt(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull() || !v.isNumber()) {
            throw new IllegalArgumentException("Missing or non-numeric required argument: " + key);
        }
        return v.asInt();
    }

    private double requireDouble(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull() || !v.isNumber()) {
            throw new IllegalArgumentException("Missing or non-numeric required argument: " + key);
        }
        return v.asDouble();
    }

    private Double optDouble(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException("Non-numeric optional argument: " + key);
        }
        return v.asDouble();
    }

    private List<String> requireStringList(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull() || !v.isArray()) {
            throw new IllegalArgumentException("Missing or non-array required argument: " + key);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode el : v) {
            if (el == null || el.isNull()) {
                continue;
            }
            out.add(el.asText());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(key + " must contain at least one string");
        }
        return out;
    }

    private ObjectNode notFound(String idKey, String idValue) {
        ObjectNode n = mapper.createObjectNode();
        n.put("found", false);
        n.put(idKey, idValue);
        return n;
    }

    private void register(Tool tool) {
        tools.put(tool.name(), tool);
    }
}
