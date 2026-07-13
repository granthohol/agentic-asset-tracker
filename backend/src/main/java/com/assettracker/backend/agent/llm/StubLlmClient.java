package com.assettracker.backend.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offline {@link LlmClient} that mimics a real provider's tool-use loop.
 *
 * <p>Two-phase swarm:
 * <ol>
 *   <li>list_squadrons / list_drones / list_formations / list_* entities</li>
 *   <li>preview_two_phase (one call → compact FORM_UP + ADVANCE centers)</li>
 *   <li>ExecutionPlan: objective + two {@code applyFormation} macros (FORM_UP then ADVANCE)</li>
 * </ol>
 *
 * <p>The orchestrator pre-expands each {@code applyFormation} into per-drone {@code setWaypoint}s,
 * so the offline path still exercises the executor's FORM_UP → ADVANCE gate.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmClient implements LlmClient {

    private static final double STUB_LAT = 39.05;
    private static final double STUB_LNG = -77.18;
    private static final Pattern LAT_LNG = Pattern.compile(
        "(?i)(?:at\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)"
    );
    /** Explicit ids in the prompt, e.g. drone-000 or drone-7. */
    private static final Pattern DRONE_ID = Pattern.compile("(?i)\\bdrone-(\\d+)\\b");
    /** Count phrases: "5 drones", "with 3 drones", "swarm of 4" (drones optional after swarm of). */
    private static final Pattern DRONE_COUNT = Pattern.compile(
        "(?i)swarm\\s+of\\s+(\\d+)(?:\\s*drones?)?"
            + "|(?:with|using|send)\\s+(\\d+)\\s*drones?"
            + "|\\b(\\d+)\\s*drones?\\b"
    );
    /** Create/remove verbs for map-entity intents. */
    private static final Pattern CREATE_VERB = Pattern.compile(
        "(?i)\\b(mark|add|create|place|drop|tag|put|set\\s+up)\\b");
    private static final Pattern REMOVE_VERB = Pattern.compile(
        "(?i)\\b(remove|delete|erase)\\b");
    /** "radius 800" or "800 m" / "800 meters". */
    private static final Pattern RADIUS = Pattern.compile(
        "(?i)radius\\s+(\\d+(?:\\.\\d+)?)|\\b(\\d+(?:\\.\\d+)?)\\s*(?:m|meters?)\\b");

    private final ObjectMapper mapper;

    public StubLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        boolean hasListDrones = hasToolResult(request, "list_drones");
        int twoPhaseCount = countToolResults(request, "preview_two_phase");

        if (!hasListDrones) {
            return LlmResponse.toolUse(
                "Inspecting fleet, squadrons, formations, and map entities.",
                List.of(
                    new ToolCall("call_squadrons", "list_squadrons", mapper.createObjectNode()),
                    new ToolCall("call_drones", "list_drones", mapper.createObjectNode()),
                    new ToolCall("call_formations", "list_formations", mapper.createObjectNode()),
                    new ToolCall("call_tracks", "list_tracks", mapper.createObjectNode()),
                    new ToolCall("call_waypoints", "list_waypoints", mapper.createObjectNode()),
                    new ToolCall("call_zones", "list_zones", mapper.createObjectNode())
                )
            );
        }

        String userCommand = firstUserText(request);

        // Map-entity intents (create/remove tracks, waypoints, zones) short-circuit the
        // swarm path — they need no formation preview.
        ExecutionPlan entityPlan = tryEntityPlan(userCommand, request);
        if (entityPlan != null) {
            return end(entityPlan);
        }

        List<String> available = collectAllDroneIds(request);
        List<String> droneIds = selectDroneIds(userCommand, available);
        double[] aoi = resolveAoi(userCommand, request);
        FormationType type = inferFormationType(userCommand);

        if (droneIds.isEmpty()) {
            // No drones to move: objective-only plan (no formation macro).
            ExecutionPlan plan = buildStubPlan(
                firstIdFromTool(request, "list_squadrons"), userCommand, aoi, type, droneIds, null, null);
            return end(plan);
        }

        // Turn 2: a single compact two-phase preview resolves both formation centers.
        if (twoPhaseCount == 0) {
            return LlmResponse.toolUse(
                "Previewing " + type + " two-phase approach for " + droneIds.size() + " drone(s).",
                List.of(new ToolCall(
                    "call_preview_two_phase",
                    "preview_two_phase",
                    twoPhaseArgs(type, droneIds, aoi[0], aoi[1])
                ))
            );
        }

        // Turn 3: emit a plan with two applyFormation macros from the summary centers.
        // The orchestrator expands these into per-drone FORM_UP/ADVANCE setWaypoints.
        JsonNode summary = allToolContents(request, "preview_two_phase").get(0);
        double[] formUp = centerFromSummary(summary, "formUpCenter", new double[] { aoi[0] - 0.018, aoi[1] });
        double[] advance = centerFromSummary(summary, "advanceCenter", aoi);
        ExecutionPlan plan = buildStubPlan(
            firstIdFromTool(request, "list_squadrons"), userCommand, aoi, type, droneIds, formUp, advance);
        return end(plan);
    }

    private LlmResponse end(ExecutionPlan plan) {
        try {
            return LlmResponse.end(mapper.writeValueAsString(plan));
        } catch (Exception e) {
            throw new IllegalStateException("StubLlmClient failed to serialize its plan", e);
        }
    }

    private ObjectNode twoPhaseArgs(FormationType type, List<String> droneIds, double aoiLat, double aoiLng) {
        ObjectNode args = mapper.createObjectNode();
        args.put("formationType", type.name());
        ArrayNode ids = args.putArray("droneIds");
        for (String id : droneIds) {
            ids.add(id);
        }
        args.put("aoiLat", aoiLat);
        args.put("aoiLng", aoiLng);
        return args;
    }

    /** Read a {lat,lng} center from a preview_two_phase summary, falling back if absent. */
    private static double[] centerFromSummary(JsonNode summary, String key, double[] fallback) {
        JsonNode c = summary == null ? null : summary.get(key);
        if (c != null && c.hasNonNull("lat") && c.hasNonNull("lng")) {
            return new double[] { c.get("lat").asDouble(), c.get("lng").asDouble() };
        }
        return fallback;
    }

    /**
     * Build the stub plan: an objective + squadron deploy, plus (when drones are selected) two
     * compact {@code applyFormation} macros. The orchestrator expands each macro into per-drone
     * FORM_UP/ADVANCE setWaypoints before the plan leaves the server.
     */
    private ExecutionPlan buildStubPlan(
        String squadronId,
        String userCommand,
        double[] aoi,
        FormationType type,
        List<String> droneIds,
        double[] formUpCenter,
        double[] advanceCenter
    ) {
        List<PlanAction> actions = new ArrayList<>();
        double aoiLat = aoi[0];
        double aoiLng = aoi[1];

        actions.add(new PlanAction.UpsertObjective(
            null, "obj-1", "Observe disturbance", 1, aoiLat, aoiLng, 500.0, null
        ));

        if (squadronId != null) {
            actions.add(new PlanAction.DeploySquadronToObjective(squadronId, "$obj-1"));
        } else {
            actions.add(new PlanAction.UpsertSquadron(null, "squad-1", "Squadron (stub)", "sector-1"));
            actions.add(new PlanAction.DeploySquadronToObjective("$squad-1", "$obj-1"));
        }

        String cmd = userCommand == null ? "(no command)" : userCommand;
        String rationale;
        // Inline (not a boolean var) so null-analysis narrows the fields inside the else branch.
        if (droneIds == null || droneIds.isEmpty() || formUpCenter == null || advanceCenter == null) {
            rationale = "Stub plan for: \"" + cmd + "\". Creates an objective and deploys assets to it.";
        } else {
            // FORM_UP faces the AOI; ADVANCE keeps the approach heading (points beyond the AOI).
            double faceLat = aoiLat + (aoiLat - formUpCenter[0]);
            double faceLng = aoiLng + (aoiLng - formUpCenter[1]);
            actions.add(new PlanAction.ApplyFormation(
                type, formUpCenter[0], formUpCenter[1], droneIds, "FORM_UP", null, aoiLat, aoiLng));
            actions.add(new PlanAction.ApplyFormation(
                type, advanceCenter[0], advanceCenter[1], droneIds, "ADVANCE", null, faceLat, faceLng));
            rationale = "Stub plan for: \"" + cmd + "\". " + type
                + ": form up under leader " + droneIds.get(0)
                + " (" + droneIds.size() + " drones), then ADVANCE on the disturbance.";
        }
        return new ExecutionPlan("plan-" + UUID.randomUUID(), rationale, actions);
    }

    static FormationType inferFormationType(String userCommand) {
        if (userCommand == null) {
            return FormationType.RING;
        }
        String lower = userCommand.toLowerCase(Locale.ROOT);
        if (lower.contains("wedge")) {
            return FormationType.WEDGE;
        }
        if (lower.contains("line") || lower.contains("picket")) {
            return FormationType.LINE;
        }
        return FormationType.RING;
    }

    // --- Map entity heuristics (offline analogue of an LLM's create/reference) ------

    /**
     * Detect a create/remove intent for a persistent map entity. Returns a ready plan, or
     * {@code null} when the prompt is not an entity command (so the swarm path is unchanged).
     */
    ExecutionPlan tryEntityPlan(String userCommand, LlmRequest request) {
        if (userCommand == null || userCommand.isBlank()) {
            return null;
        }
        String lower = userCommand.toLowerCase(Locale.ROOT);

        // Removal first: a verb plus an entity whose name is referenced in the prompt.
        if (REMOVE_VERB.matcher(lower).find()) {
            PlanAction removal = resolveRemoval(lower, request);
            if (removal != null) {
                return entityPlan("Removing the map entity named in the request.", removal);
            }
        }

        if (!CREATE_VERB.matcher(lower).find()) {
            return null;
        }

        if (containsAny(lower, "zone", "no-fly", "no fly", "nofly", "restricted", "patrol", "keep-out", "keep out")) {
            double[] c = parseLatLng(userCommand);
            ZoneType type = lower.contains("patrol") ? ZoneType.PATROL : ZoneType.RESTRICTED;
            double radius = parseRadiusMeters(userCommand);
            String name = (type == ZoneType.PATROL ? "Patrol" : "Restricted") + " zone";
            return entityPlan("Creating a " + type + " CIRCLE zone from the request.",
                new PlanAction.UpsertZone(null, "zone-1", name, type, ZoneShape.CIRCLE,
                    c[0], c[1], radius, null));
        }

        if (containsAny(lower, "waypoint", "way point", "rally", "checkpoint", "poi")) {
            double[] c = parseLatLng(userCommand);
            String name = lower.contains("rally") ? "Rally point" : "Waypoint";
            return entityPlan("Placing a persistent map waypoint.",
                new PlanAction.UpsertWaypoint(null, "wp-1", name, c[0], c[1]));
        }

        if (containsAny(lower, "track", "contact", "hostile", "friendly", "unknown", "bogey", "target")) {
            double[] c = parseLatLng(userCommand);
            Affiliation aff = lower.contains("friendly") ? Affiliation.FRIENDLY
                : lower.contains("unknown") ? Affiliation.UNKNOWN
                : Affiliation.HOSTILE;
            TrackDomain domain = containsAny(lower, "ground", "vehicle", "tank", "infantry", "convoy")
                ? TrackDomain.GROUND : TrackDomain.AERIAL;
            String name = capitalize(aff.name().toLowerCase(Locale.ROOT)) + " "
                + domain.name().toLowerCase(Locale.ROOT) + " contact";
            return entityPlan("Marking a " + aff + " " + domain + " track.",
                new PlanAction.UpsertTrack(null, "track-1", name, aff, domain, c[0], c[1]));
        }

        return null;
    }

    private PlanAction resolveRemoval(String lowerPrompt, LlmRequest request) {
        for (JsonNode arr : allToolContents(request, "list_tracks")) {
            for (JsonNode n : arr) {
                if (entityNameReferenced(lowerPrompt, n)) {
                    return new PlanAction.RemoveTrack(n.path("id").asText());
                }
            }
        }
        for (JsonNode arr : allToolContents(request, "list_zones")) {
            for (JsonNode n : arr) {
                if (entityNameReferenced(lowerPrompt, n)) {
                    return new PlanAction.RemoveZone(n.path("id").asText());
                }
            }
        }
        for (JsonNode arr : allToolContents(request, "list_waypoints")) {
            for (JsonNode n : arr) {
                if (entityNameReferenced(lowerPrompt, n)) {
                    return new PlanAction.RemoveWaypoint(n.path("id").asText());
                }
            }
        }
        return null;
    }

    /**
     * Resolve the AOI: explicit {@code lat,lng} wins; otherwise fall back to a named zone
     * center (then a named waypoint) found in tool results; else the stub default.
     */
    private double[] resolveAoi(String userCommand, LlmRequest request) {
        if (hasExplicitLatLng(userCommand)) {
            return parseLatLng(userCommand);
        }
        String lower = userCommand == null ? "" : userCommand.toLowerCase(Locale.ROOT);
        double[] zone = zoneCenterByName(lower, request);
        if (zone != null) {
            return zone;
        }
        double[] wp = waypointByName(lower, request);
        if (wp != null) {
            return wp;
        }
        return parseLatLng(userCommand);
    }

    private double[] zoneCenterByName(String lowerPrompt, LlmRequest request) {
        for (JsonNode arr : allToolContents(request, "list_zones")) {
            for (JsonNode z : arr) {
                if (entityNameReferenced(lowerPrompt, z)
                    && z.hasNonNull("centerLatitude") && z.hasNonNull("centerLongitude")) {
                    return new double[] {
                        z.get("centerLatitude").asDouble(), z.get("centerLongitude").asDouble() };
                }
            }
        }
        return null;
    }

    private double[] waypointByName(String lowerPrompt, LlmRequest request) {
        for (JsonNode arr : allToolContents(request, "list_waypoints")) {
            for (JsonNode w : arr) {
                if (entityNameReferenced(lowerPrompt, w)
                    && w.hasNonNull("latitude") && w.hasNonNull("longitude")) {
                    return new double[] { w.get("latitude").asDouble(), w.get("longitude").asDouble() };
                }
            }
        }
        return null;
    }

    private ExecutionPlan entityPlan(String rationale, PlanAction... actions) {
        return new ExecutionPlan("plan-" + UUID.randomUUID(), rationale, List.of(actions));
    }

    static boolean entityNameReferenced(String lowerPrompt, JsonNode entity) {
        JsonNode name = entity.get("name");
        if (name == null || name.isNull()) {
            return false;
        }
        String n = name.asText().toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) {
            return false;
        }
        if (lowerPrompt.contains(n)) {
            return true;
        }
        for (String token : n.split("[^a-z0-9]+")) {
            if (token.length() >= 4 && lowerPrompt.contains(token)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasExplicitLatLng(String userCommand) {
        if (userCommand == null) {
            return false;
        }
        Matcher m = LAT_LNG.matcher(userCommand);
        if (!m.find()) {
            return false;
        }
        try {
            double lat = Double.parseDouble(m.group(1));
            double lng = Double.parseDouble(m.group(2));
            return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static double parseRadiusMeters(String userCommand) {
        if (userCommand == null) {
            return 500.0;
        }
        Matcher m = RADIUS.matcher(userCommand);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                double r = Double.parseDouble(g);
                if (r > 0) {
                    return r;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 500.0;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private boolean hasToolResult(LlmRequest request, String toolName) {
        return countToolResults(request, toolName) > 0;
    }

    private int countToolResults(LlmRequest request, String toolName) {
        int n = 0;
        for (LlmMessage m : request.messages()) {
            if (m.role() != LlmMessage.Role.TOOL) {
                continue;
            }
            for (ToolResult r : m.toolResults()) {
                if (toolName.equals(r.toolName()) && r.content() != null && !r.content().isNull()) {
                    n++;
                }
            }
        }
        return n;
    }

    private List<JsonNode> allToolContents(LlmRequest request, String toolName) {
        List<JsonNode> out = new ArrayList<>();
        for (LlmMessage m : request.messages()) {
            if (m.role() != LlmMessage.Role.TOOL) {
                continue;
            }
            for (ToolResult r : m.toolResults()) {
                if (toolName.equals(r.toolName()) && r.content() != null && !r.content().isNull()) {
                    out.add(r.content());
                }
            }
        }
        return out;
    }

    /** All drone ids from list_drones (order preserved). Parses the columnar {fields, rows} shape. */
    private List<String> collectAllDroneIds(LlmRequest request) {
        List<String> ids = new ArrayList<>();
        for (JsonNode content : allToolContents(request, "list_drones")) {
            addDroneIds(content, ids);
        }
        return ids;
    }

    private static void addDroneIds(JsonNode content, List<String> ids) {
        // Legacy object-array shape ([{id, ...}, ...]).
        if (content.isArray()) {
            for (JsonNode element : content) {
                JsonNode id = element.get("id");
                if (id != null && !id.isNull()) {
                    ids.add(id.asText());
                }
            }
            return;
        }
        // Compact columnar shape ({"fields":["id","lat","lng"], "rows":[[...], ...]}).
        JsonNode fields = content.get("fields");
        JsonNode rows = content.get("rows");
        if (fields == null || !fields.isArray() || rows == null || !rows.isArray()) {
            return;
        }
        int idIdx = -1;
        for (int i = 0; i < fields.size(); i++) {
            if ("id".equals(fields.get(i).asText())) {
                idIdx = i;
                break;
            }
        }
        if (idIdx < 0) {
            return;
        }
        for (JsonNode row : rows) {
            if (row.isArray() && row.size() > idIdx) {
                JsonNode id = row.get(idIdx);
                if (id != null && !id.isNull()) {
                    ids.add(id.asText());
                }
            }
        }
    }

    /**
     * Resolve which drones to use from the prompt:
     * <ol>
     *   <li>Explicit ids ({@code drone-000}, …) that exist in {@code available}</li>
     *   <li>Else a count phrase ({@code 5 drones}, {@code swarm of 3}) → first N available</li>
     *   <li>Else all available (capped at {@link FormationService#MAX_FORMATION_DRONES})</li>
     * </ol>
     */
    static List<String> selectDroneIds(String userCommand, List<String> available) {
        if (available == null || available.isEmpty()) {
            return List.of();
        }
        List<String> named = parseNamedDroneIds(userCommand);
        if (!named.isEmpty()) {
            List<String> matched = new ArrayList<>();
            for (String id : named) {
                String canonical = findAvailableId(available, id);
                if (canonical != null && !matched.contains(canonical)) {
                    matched.add(canonical);
                }
            }
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        Integer count = parseDroneCount(userCommand);
        int n = count != null ? count : available.size();
        n = Math.max(0, Math.min(n, Math.min(available.size(), FormationService.MAX_FORMATION_DRONES)));
        return available.subList(0, n);
    }

    static List<String> parseNamedDroneIds(String userCommand) {
        List<String> ids = new ArrayList<>();
        if (userCommand == null || userCommand.isBlank()) {
            return ids;
        }
        Matcher m = DRONE_ID.matcher(userCommand);
        while (m.find()) {
            // Normalize to drone-NNN (zero-padded to 3 when possible) for matching.
            String rawNum = m.group(1);
            ids.add("drone-" + rawNum);
            try {
                ids.add(String.format("drone-%03d", Integer.parseInt(rawNum)));
            } catch (NumberFormatException ignored) {
                // keep raw form only
            }
        }
        // Dedup while preserving order of first occurrence of each canonical-ish form.
        List<String> unique = new ArrayList<>();
        for (String id : ids) {
            if (!unique.contains(id)) {
                unique.add(id);
            }
        }
        return unique;
    }

    static Integer parseDroneCount(String userCommand) {
        if (userCommand == null || userCommand.isBlank()) {
            return null;
        }
        Matcher m = DRONE_COUNT.matcher(userCommand);
        if (!m.find()) {
            return null;
        }
        for (int i = 1; i <= m.groupCount(); i++) {
            String g = m.group(i);
            if (g == null) {
                continue;
            }
            try {
                int n = Integer.parseInt(g);
                return n > 0 ? n : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String findAvailableId(List<String> available, String candidate) {
        for (String a : available) {
            if (a.equalsIgnoreCase(candidate)) {
                return a;
            }
        }
        // Match drone-7 against drone-007
        Matcher cm = DRONE_ID.matcher(candidate);
        if (!cm.matches()) {
            return null;
        }
        try {
            int n = Integer.parseInt(cm.group(1));
            for (String a : available) {
                Matcher am = DRONE_ID.matcher(a);
                if (am.matches() && Integer.parseInt(am.group(1)) == n) {
                    return a;
                }
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    static double[] parseLatLng(String userCommand) {
        if (userCommand == null || userCommand.isBlank()) {
            return new double[] { STUB_LAT, STUB_LNG };
        }
        Matcher m = LAT_LNG.matcher(userCommand);
        if (!m.find()) {
            return new double[] { STUB_LAT, STUB_LNG };
        }
        try {
            double lat = Double.parseDouble(m.group(1));
            double lng = Double.parseDouble(m.group(2));
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                return new double[] { STUB_LAT, STUB_LNG };
            }
            return new double[] { lat, lng };
        } catch (NumberFormatException e) {
            return new double[] { STUB_LAT, STUB_LNG };
        }
    }

    private String firstIdFromTool(LlmRequest request, String toolName) {
        for (LlmMessage m : request.messages()) {
            if (m.role() != LlmMessage.Role.TOOL) {
                continue;
            }
            for (ToolResult r : m.toolResults()) {
                if (!toolName.equals(r.toolName()) || r.content() == null || !r.content().isArray()) {
                    continue;
                }
                for (JsonNode element : r.content()) {
                    JsonNode id = element.get("id");
                    if (id != null && !id.isNull()) {
                        return id.asText();
                    }
                }
            }
        }
        return null;
    }

    private String firstUserText(LlmRequest request) {
        return request.messages().stream()
            .filter(m -> m.role() == LlmMessage.Role.USER)
            .map(LlmMessage::text)
            .findFirst()
            .orElse(null);
    }
}
