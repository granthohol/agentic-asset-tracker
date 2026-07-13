package com.assettracker.backend.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.assettracker.backend.agent.llm.LlmClient;
import com.assettracker.backend.agent.llm.LlmMessage;
import com.assettracker.backend.agent.llm.LlmRequest;
import com.assettracker.backend.agent.llm.LlmResponse;
import com.assettracker.backend.agent.llm.ToolCall;
import com.assettracker.backend.agent.llm.ToolResult;
import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanExpander;
import com.assettracker.backend.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runs the multi-turn LLM tool-use loop server-side and returns a validated, read-only
 * {@link ExecutionPlan}. This is the "planner brain": it reasons over the graph via
 * {@link ToolRegistry} read tools and emits a plan. It <b>never</b> mutates state — there
 * is no {@code GraphWriter} or {@code CommandPublisher} on this path. Approval and
 * execution happen later (steps 11-12).
 */
@Service
public class AgentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationService.class);

    /** Hard cap so a confused model cannot loop (and bill) forever.  */
    static final int MAX_TURNS = 6;
    /** How many times we ask the model to fix malformed plan JSON before giving up. */
    static final int MAX_PLAN_RETRIES = 2;
    static final int MAX_TOKENS = 2048;

    private static final String SYSTEM_PROMPT = """
        You are the planning brain of a drone Command & Control (C2) system. The world is a graph:
          - Drone -[ASSIGNED_TO]-> Squadron -[DEPLOYED_FOR]-> Objective (each link optional, at most one).
          - Objective: has a location (centerLatitude/centerLongitude, radiusMeters) or a
            targetEntityId (a track or zone id).

        Persistent map annotations you can read/create/update/remove:
          - Track: a static contact; affiliation FRIENDLY|HOSTILE|UNKNOWN, domain AERIAL|GROUND, lat/lng. Not a drone.
          - Waypoint: a durable labeled point (name + lat/lng). Distinct from a drone's motion target (setWaypoint).
          - Zone: a named area; type RESTRICTED|PATROL; shape CIRCLE (center + radiusMeters) or POLYGON (>=3 [lat,lng]).

        Use the READ-ONLY tools to ground every decision in real ids/state before planning. Never
        invent ids — discover them via list_*/get_*_by_id first. A zone's center is a good AOI when
        the operator names an area instead of coordinates.

        Swarm / formation requests: pick a type (RING, WEDGE, LINE). Choose drones from the prompt:
        explicit ids (drone-000, …), a count ("5 drones"), else ALL drones from list_drones. Call
        preview_two_phase(formationType, droneIds, aoiLat, aoiLng) ONCE — it returns
        { formationType, droneCount, formUpCenter{lat,lng}, advanceCenter{lat,lng} }. Then emit TWO
        applyFormation actions (do NOT emit per-drone setWaypoint for swarms): first at formUpCenter
        with mission_type FORM_UP and facingLat/facingLng set to the AOI; then at advanceCenter with
        mission_type ADVANCE. The backend expands each applyFormation into per-drone waypoints.

        When done, output ONE JSON object ExecutionPlan:
          { "planId": string, "rationale": string, "actions": PlanAction[] }

        Each PlanAction has an "op" discriminator. Allowed ops and fields:
          - upsertSquadron:             { op, id? | tempId?, name, sectorId }
          - upsertObjective:            { op, id? | tempId?, name, priority, centerLatitude?, centerLongitude?, radiusMeters?, targetEntityId? }
          - assignDroneToSquadron:      { op, droneId, squadronId }
          - deploySquadronToObjective:  { op, squadronId, objectiveId }
          - removeDroneAssignment:      { op, droneId }
          - removeSquadronFromObjective:{ op, squadronId }
          - setWaypoint:                { op, droneId, targetLat, targetLng, mission_type? }
          - applyFormation:             { op, formationType, centerLat, centerLng, droneIds, mission_type?, spacingMeters?, facingLat?, facingLng? }
          - clearWaypoint:              { op, droneId }
          - upsertTrack:                { op, id? | tempId?, name, affiliation, domain, latitude, longitude }
          - upsertWaypoint:             { op, id? | tempId?, name, latitude, longitude }
          - upsertZone:                 { op, id? | tempId?, name, type, shape, centerLatitude?, centerLongitude?, radiusMeters?, vertices? }
          - removeTrack | removeWaypoint | removeZone: { op, id }

        upsertZone: CIRCLE => center + radiusMeters; POLYGON => vertices [[lat,lng],...] (>=3).
        upsertWaypoint creates a PERSISTENT marker; use setWaypoint to move a drone. remove* needs a
        real id from a list/get tool (no "$" refs). To create-and-reference within one plan, give an
        upsert a "tempId" (e.g. "obj-1") and reference it later as "$obj-1" (only AFTER it is declared).
        Drones are never created by a plan; only reference drone ids from tool results.

        Output discipline: return ONLY the JSON object. No prose, no markdown, no code fences.
        """;

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final PlanExpander planExpander;
    private final ObjectMapper mapper;

    public AgentOrchestrationService(
        LlmClient llm, ToolRegistry tools, PlanExpander planExpander, ObjectMapper mapper) {
        this.llm = llm;
        this.tools = tools;
        this.planExpander = planExpander;
        this.mapper = mapper;
    }

    /**
     * Turn a natural-language command into a proposed {@link ExecutionPlan}. Read-only:
     * the returned plan is a proposal that the operator must approve before anything runs.
     */
    public ExecutionPlan planFromPrompt(String userCommand) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.user(userCommand));
        JsonNode toolSpecs = tools.toolSpecs();

        int planRetries = 0;

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            LlmResponse response = llm.complete(new LlmRequest(SYSTEM_PROMPT, messages, toolSpecs, MAX_TOKENS));

            if (response.stopReason() == LlmResponse.StopReason.TOOL_USE) {
                messages.add(LlmMessage.assistant(response.text(), response.toolCalls()));
                messages.add(LlmMessage.toolResults(runTools(response.toolCalls())));
                continue;
            }

            // Final answer: must parse + validate as an ExecutionPlan.
            try {
                ExecutionPlan plan = parsePlan(response.text());
                validate(plan);
                // Expand compact applyFormation macros into per-drone setWaypoints BEFORE the plan
                // leaves the server, so the frontend and executor only ever see setWaypoints. A bad
                // macro (e.g. empty droneIds) throws here and triggers the retry path below.
                ExecutionPlan expanded = planExpander.expand(plan);
                ExecutionPlan finalized = ensurePlanId(expanded);
                log.info("Planner produced plan {} with {} action(s) in {} turn(s)",
                    finalized.planId(), finalized.actions().size(), turn + 1);
                return finalized;
            } catch (Exception e) {
                if (++planRetries > MAX_PLAN_RETRIES) {
                    throw new IllegalStateException(
                        "LLM failed to produce a valid ExecutionPlan after " + MAX_PLAN_RETRIES + " retries: " + e.getMessage(), e);
                }
                log.warn("Invalid plan from model (retry {}/{}): {}", planRetries, MAX_PLAN_RETRIES, e.getMessage());
                messages.add(LlmMessage.assistant(response.text(), List.of()));
                messages.add(LlmMessage.user(
                    "That was not a valid ExecutionPlan JSON (" + e.getMessage()
                    + "). Return ONE JSON object matching the schema, with no prose or markdown."));
            }
        }

        throw new IllegalStateException("Planner exceeded MAX_TURNS (" + MAX_TURNS + ") without producing a plan");
    }

    private List<ToolResult> runTools(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            try {
                JsonNode out = tools.invoke(call.name(), call.input());
                results.add(new ToolResult(call.id(), call.name(), out, false));
                log.info("tool {} -> ok", call.name());
            } catch (Exception e) {
                ObjectNode err = mapper.createObjectNode();
                err.put("error", e.getMessage());
                results.add(new ToolResult(call.id(), call.name(), err, true));
                log.warn("tool {} -> error: {}", call.name(), e.getMessage());
            }
        }
        return results;
    }

    private ExecutionPlan parsePlan(String text) throws Exception {
        return mapper.readValue(stripFences(text), ExecutionPlan.class);
    }

    /** Tolerate a model that wraps JSON in ```json fences despite instructions. */
    private String stripFences(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty model response");
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.strip();
    }

    private void validate(ExecutionPlan plan) {
        if (plan == null || plan.actions() == null) {
            throw new IllegalArgumentException("plan or actions missing");
        }
        // Deeper checks ($tempId resolvability, id-vs-tempId, bounds) live in /api/execute-plan.
    }

    private ExecutionPlan ensurePlanId(ExecutionPlan plan) {
        if (plan.planId() == null || plan.planId().isBlank()) {
            return new ExecutionPlan("plan-" + UUID.randomUUID(), plan.rationale(), plan.actions());
        }
        return plan;
    }
}
