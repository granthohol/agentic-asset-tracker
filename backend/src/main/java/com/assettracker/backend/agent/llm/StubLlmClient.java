package com.assettracker.backend.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * Offline LlmClient that fakes the tool-use loop for dev/tests.
 * Turn 1: list tools. Turn 2: preview_two_phase. Turn 3: plan with applyFormation macros.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmClient implements LlmClient {

    private static final double STUB_LAT = 39.05;
    private static final double STUB_LNG = -77.18;
    private static final Pattern LAT_LNG = Pattern.compile(
        "(?i)(?:at\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)"
    );
    /** Explicit drone ids in the prompt, e.g. drone-000 or drone-7. */
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
        int planRouteCount = countToolResults(request, "plan_route");

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

        // Map entity create/remove skips the swarm path entirely.
        ExecutionPlan entityPlan = tryEntityPlan(userCommand, request);
        if (entityPlan != null) {
            return end(entityPlan);
        }

        List<String> available = collectAllDroneIds(request);
        List<String> droneIds = selectDroneIds(userCommand, available);
        // Advance/AOI target (track/zone/coords). Named form-up waypoint is separate.
        double[] aoi = resolveAdvanceAoi(userCommand, request);
        double[] namedFormUp = resolveNamedFormUpCenter(userCommand, request);
        FormationType type = inferFormationType(userCommand);
        boolean avoid = wantsAvoid(userCommand);

        if (droneIds.isEmpty()) {
            ExecutionPlan plan = buildStubPlan(
                firstIdFromTool(request, "list_squadrons"), userCommand, aoi, type, droneIds, null, null);
            return end(plan);
        }

        // Single-drone avoid: plan_route from current pos to AOI, then setRoute.
        if (avoid && droneIds.size() == 1) {
            String droneId = droneIds.get(0);
            if (planRouteCount == 0) {
                double[] from = droneLatLng(request, droneId);
                if (from == null) {
                    from = new double[] { aoi[0] - 0.02, aoi[1] };
                }
                return LlmResponse.toolUse(
                    "Planning a route for " + droneId + " that avoids restricted circles.",
                    List.of(new ToolCall(
                        "call_plan_route",
                        "plan_route",
                        planRouteArgs(from[0], from[1], aoi[0], aoi[1])
                    ))
                );
            }
            JsonNode route = requireSuccessfulRoute(allToolContents(request, "plan_route").get(0));
            return end(buildSingleDroneRoutePlan(droneId, userCommand, aoi, route));
        }

        // Turn 2: preview_two_phase for ADVANCE AOI (and default standoff form-up when none named).
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

        JsonNode summary = allToolContents(request, "preview_two_phase").get(0);
        double[] previewFormUp = centerFromSummary(
            summary, "formUpCenter", new double[] { aoi[0] - 0.018, aoi[1] });
        double[] advance = centerFromSummary(summary, "advanceCenter", aoi);

        // The named rally always wins over the computed standoff. Avoidance itself is no longer
        // this class's job for swarms: buildStubPlan emits a single applyFormationRoute action
        // and the backend (PlanExpander + ZoneRouter) computes the FORM_UP -> HOLD* -> ADVANCE
        // route deterministically, guaranteeing every wave carries the full swarm.
        double[] formUp = namedFormUp != null ? namedFormUp : previewFormUp;

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

    private ObjectNode planRouteArgs(double fromLat, double fromLng, double toLat, double toLng) {
        ObjectNode args = mapper.createObjectNode();
        args.put("fromLat", fromLat);
        args.put("fromLng", fromLng);
        args.put("toLat", toLat);
        args.put("toLng", toLng);
        return args;
    }

    /** Read {lat,lng} from preview_two_phase summary, or use fallback. */
    private static double[] centerFromSummary(JsonNode summary, String key, double[] fallback) {
        JsonNode c = summary == null ? null : summary.get(key);
        if (c != null && c.hasNonNull("lat") && c.hasNonNull("lng")) {
            return new double[] { c.get("lat").asDouble(), c.get("lng").asDouble() };
        }
        return fallback;
    }

    static boolean wantsAvoid(String userCommand) {
        if (userCommand == null) {
            return false;
        }
        String lower = userCommand.toLowerCase(Locale.ROOT);
        return containsAny(lower, "avoid", "no-fly", "no fly", "nofly", "keep out", "keep-out",
            "restricted zone", "around the zone", "around the restricted");
    }

    private ExecutionPlan buildSingleDroneRoutePlan(
        String droneId, String userCommand, double[] aoi, JsonNode route
    ) {
        List<List<Double>> legs = legsFromRoute(route, aoi[0], aoi[1]);
        List<PlanAction> actions = new ArrayList<>();
        actions.add(new PlanAction.UpsertObjective(
            null, "obj-1", "Observe disturbance", 1, aoi[0], aoi[1], 500.0, null));
        actions.add(new PlanAction.SetRoute(droneId, legs, "ADVANCE"));
        boolean direct = route != null && route.path("direct").asBoolean(false);
        String cmd = userCommand == null ? "(no command)" : userCommand;
        String rationale = "Stub plan for: \"" + cmd + "\". Route " + droneId
            + " in " + legs.size() + " leg(s)"
            + (direct ? " (direct)." : " avoiding restricted circles.");
        return new ExecutionPlan("plan-" + UUID.randomUUID(), rationale, actions);
    }

    /**
     * Stub plan: objective + deploy, plus a single applyFormationRoute macro when drones are
     * selected. The backend expands that macro into FORM_UP -> HOLD* -> ADVANCE waves, routing
     * around restricted zones itself — this class no longer computes or previews that route.
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
        if (droneIds == null || droneIds.isEmpty() || formUpCenter == null || advanceCenter == null) {
            rationale = "Stub plan for: \"" + cmd + "\". Creates an objective and deploys assets to it.";
        } else {
            actions.add(new PlanAction.ApplyFormationRoute(
                type, droneIds, formUpCenter[0], formUpCenter[1],
                advanceCenter[0], advanceCenter[1], "ADVANCE", null));
            rationale = "Stub plan for: \"" + cmd + "\". " + type
                + ": form up under leader " + droneIds.get(0)
                + " (" + droneIds.size() + " drones) at the rally, then advance on the objective"
                + " (auto-routed around any restricted zones).";
        }
        return new ExecutionPlan("plan-" + UUID.randomUUID(), rationale, actions);
    }

    /** Legs from a successful plan_route JSON. Empty/error routes must not silently go direct. */
    private static List<List<Double>> legsFromRoute(JsonNode route, double toLat, double toLng) {
        requireSuccessfulRoute(route);
        List<List<Double>> legs = new ArrayList<>();
        for (JsonNode leg : route.path("legs")) {
            if (leg.hasNonNull("lat") && leg.hasNonNull("lng")) {
                legs.add(List.of(leg.get("lat").asDouble(), leg.get("lng").asDouble()));
            }
        }
        if (legs.isEmpty()) {
            throw new IllegalStateException("plan_route returned no legs");
        }
        // Ensure final destination is present.
        List<Double> last = legs.get(legs.size() - 1);
        if (Math.abs(last.get(0) - toLat) > 1e-5 || Math.abs(last.get(1) - toLng) > 1e-5) {
            legs.add(List.of(toLat, toLng));
        }
        return legs;
    }

    static JsonNode requireSuccessfulRoute(JsonNode route) {
        if (route == null || routeHasError(route)) {
            String msg = route == null ? "missing plan_route result"
                : route.path("error").asText("plan_route failed");
            throw new IllegalStateException("Cannot build avoid plan: " + msg);
        }
        return route;
    }

    static boolean routeHasError(JsonNode route) {
        return route != null && route.hasNonNull("error");
    }

    /** Lat/lng for a drone from columnar or legacy list_drones results. */
    private double[] droneLatLng(LlmRequest request, String droneId) {
        for (JsonNode content : allToolContents(request, "list_drones")) {
            if (content.isArray()) {
                for (JsonNode d : content) {
                    if (droneId.equals(d.path("id").asText())
                        && d.hasNonNull("latitude") && d.hasNonNull("longitude")) {
                        return new double[] { d.get("latitude").asDouble(), d.get("longitude").asDouble() };
                    }
                    if (droneId.equals(d.path("id").asText())
                        && d.hasNonNull("lat") && d.hasNonNull("lng")) {
                        return new double[] { d.get("lat").asDouble(), d.get("lng").asDouble() };
                    }
                }
                continue;
            }
            JsonNode fields = content.get("fields");
            JsonNode rows = content.get("rows");
            if (fields == null || rows == null || !fields.isArray() || !rows.isArray()) {
                continue;
            }
            int idIdx = -1;
            int latIdx = -1;
            int lngIdx = -1;
            for (int i = 0; i < fields.size(); i++) {
                String f = fields.get(i).asText();
                if ("id".equals(f)) {
                    idIdx = i;
                } else if ("lat".equals(f) || "latitude".equals(f)) {
                    latIdx = i;
                } else if ("lng".equals(f) || "longitude".equals(f)) {
                    lngIdx = i;
                }
            }
            if (idIdx < 0 || latIdx < 0 || lngIdx < 0) {
                continue;
            }
            for (JsonNode row : rows) {
                if (row.isArray() && row.size() > Math.max(idIdx, Math.max(latIdx, lngIdx))
                    && droneId.equals(row.get(idIdx).asText())) {
                    return new double[] { row.get(latIdx).asDouble(), row.get(lngIdx).asDouble() };
                }
            }
        }
        return null;
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

    // Map entity heuristics (offline stand-in for real LLM intent parsing)

    /** Detect create/remove map entity intent. Returns null if this is a swarm command. */
    ExecutionPlan tryEntityPlan(String userCommand, LlmRequest request) {
        if (userCommand == null || userCommand.isBlank()) {
            return null;
        }
        String lower = userCommand.toLowerCase(Locale.ROOT);

        // Removal: verb + entity name referenced in the prompt.
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
     * ADVANCE / objective center. Prefer tracks over zones/waypoints so a form-up rally
     * point is not mistaken for the destination when both are named.
     * Never use a RESTRICTED zone center as the AOI (especially on avoid prompts).
     */
    private double[] resolveAdvanceAoi(String userCommand, LlmRequest request) {
        if (hasExplicitLatLng(userCommand)) {
            return parseLatLng(userCommand);
        }
        String lower = userCommand == null ? "" : userCommand.toLowerCase(Locale.ROOT);
        double[] track = trackCenterByName(lower, request);
        if (track != null) {
            return track;
        }
        // PATROL zones can be destinations; RESTRICTED circles are obstacles only.
        double[] patrol = patrolZoneCenterByName(lower, request);
        if (patrol != null) {
            return patrol;
        }
        // Only use a named waypoint as the AOI when it is not the form-up rally.
        if (resolveNamedFormUpCenter(userCommand, request) == null) {
            double[] wp = waypointByName(lower, request);
            if (wp != null) {
                return wp;
            }
        }
        return parseLatLng(userCommand);
    }

    /**
     * Explicit form-up location from a named map waypoint (e.g. "form up at Rally").
     * Null when the operator did not name a rally / form-up waypoint.
     */
    private double[] resolveNamedFormUpCenter(String userCommand, LlmRequest request) {
        if (userCommand == null) {
            return null;
        }
        String lower = userCommand.toLowerCase(Locale.ROOT);
        if (!wantsNamedFormUp(lower)) {
            return null;
        }
        return waypointByName(lower, request);
    }

    /** True when the prompt asks to form / rally at a place before advancing. */
    static boolean wantsNamedFormUp(String lowerPrompt) {
        if (lowerPrompt == null || lowerPrompt.isBlank()) {
            return false;
        }
        return containsAny(lowerPrompt,
            "form up at", "form at", "form a", "form the", "form up the",
            "swarm at", "rally at", "rally to", "meet at", "assemble at",
            "at the waypoint", "at waypoint", "at the rally");
    }

    private double[] trackCenterByName(String lowerPrompt, LlmRequest request) {
        for (JsonNode arr : allToolContents(request, "list_tracks")) {
            for (JsonNode t : arr) {
                if (entityNameReferenced(lowerPrompt, t)
                    && t.hasNonNull("latitude") && t.hasNonNull("longitude")) {
                    return new double[] { t.get("latitude").asDouble(), t.get("longitude").asDouble() };
                }
            }
        }
        return null;
    }

    private double[] patrolZoneCenterByName(String lowerPrompt, LlmRequest request) {
        for (JsonNode arr : allToolContents(request, "list_zones")) {
            for (JsonNode z : arr) {
                String type = z.path("type").asText("").toUpperCase(Locale.ROOT);
                if (!"PATROL".equals(type)) {
                    continue;
                }
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

    /** Generic tokens that appear in prompts without naming a specific entity. */
    private static final Set<String> ENTITY_NAME_STOPWORDS = Set.of(
        "zone", "track", "point", "area", "contact", "waypoint", "rally", "drone",
        "restricted", "patrol", "nofly", "hostile", "friendly", "unknown", "aerial", "ground"
    );

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
            if (token.length() < 4 || ENTITY_NAME_STOPWORDS.contains(token)) {
                continue;
            }
            if (lowerPrompt.contains(token)) {
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

    /** All drone ids from list_drones. Handles columnar {fields, rows} and legacy array shape. */
    private List<String> collectAllDroneIds(LlmRequest request) {
        List<String> ids = new ArrayList<>();
        for (JsonNode content : allToolContents(request, "list_drones")) {
            addDroneIds(content, ids);
        }
        return ids;
    }

    private static void addDroneIds(JsonNode content, List<String> ids) {
        // Legacy shape: [{id, ...}, ...]
        if (content.isArray()) {
            for (JsonNode element : content) {
                JsonNode id = element.get("id");
                if (id != null && !id.isNull()) {
                    ids.add(id.asText());
                }
            }
            return;
        }
        // Columnar shape: {fields, rows}
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

    /** Pick drones: explicit ids, then count phrase, then all (capped at MAX_FORMATION_DRONES). */
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
            // Normalize to drone-NNN for matching.
            String rawNum = m.group(1);
            ids.add("drone-" + rawNum);
            try {
                ids.add(String.format("drone-%03d", Integer.parseInt(rawNum)));
            } catch (NumberFormatException ignored) {
                // keep raw form only
            }
        }
        // Dedup, keep first occurrence order.
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
