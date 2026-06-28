package com.assettracker.backend.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Offline {@link LlmClient} that mimics a real provider's tool-use loop so the whole
 * planner pipeline can be exercised without an API key.
 *
 * <p>Behavior:
 * <ol>
 *   <li><b>Turn 1</b> (no tool results yet): emit two tool calls — {@code list_squadrons}
 *       and {@code list_drones} — so the orchestrator's dispatch + result feedback is real.</li>
 *   <li><b>Turn 2</b> (tool results present): read them and emit a schema-valid
 *       {@link ExecutionPlan} that demonstrates a {@code tempId} -> {@code $tempId} chain
 *       (create an objective, deploy a squadron to it, route a drone there).</li>
 * </ol>
 *
 * <p>Active by default. The real Anthropic client (added later) will set
 * {@code llm.provider=anthropic} to take over; this stub stays as the {@code stub} default.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmClient implements LlmClient {

    private static final double STUB_LAT = 39.05;
    private static final double STUB_LNG = -77.18;

    private final ObjectMapper mapper;

    public StubLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        boolean toolsAlreadyRun = request.messages().stream()
            .anyMatch(m -> m.role() == LlmMessage.Role.TOOL);

        if (!toolsAlreadyRun) {
            return LlmResponse.toolUse(
                "Inspecting the current fleet and squadrons before planning.",
                List.of(
                    new ToolCall("call_squadrons", "list_squadrons", mapper.createObjectNode()),
                    new ToolCall("call_drones", "list_drones", mapper.createObjectNode())
                )
            );
        }

        String squadronId = firstIdFromTool(request, "list_squadrons");
        String droneId = firstIdFromTool(request, "list_drones");
        String userCommand = firstUserText(request);

        ExecutionPlan plan = buildStubPlan(squadronId, droneId, userCommand);
        try {
            return LlmResponse.end(mapper.writeValueAsString(plan));
        } catch (Exception e) {
            throw new IllegalStateException("StubLlmClient failed to serialize its plan", e);
        }
    }

    private ExecutionPlan buildStubPlan(String squadronId, String droneId, String userCommand) {
        List<PlanAction> actions = new ArrayList<>();

        // 1. Create a new objective with a tempId (location demonstrates the geo fields).
        actions.add(new PlanAction.UpsertObjective(
            null, "obj-1", "Observe area (stub)", 1, STUB_LAT, STUB_LNG, 500.0, null
        ));

        // 2. Deploy a squadron to it. Use a real squadron if the graph has one; otherwise
        //    mint a new squadron via a second tempId chain.
        if (squadronId != null) {
            actions.add(new PlanAction.DeploySquadronToObjective(squadronId, "$obj-1"));
        } else {
            actions.add(new PlanAction.UpsertSquadron(null, "squad-1", "Squadron (stub)", "sector-1"));
            actions.add(new PlanAction.DeploySquadronToObjective("$squad-1", "$obj-1"));
        }

        // 3. Route a real drone to the objective center, if one exists.
        if (droneId != null) {
            actions.add(new PlanAction.SetWaypoint(droneId, STUB_LAT, STUB_LNG, "RECON"));
        }

        String rationale = "Stub plan for: \"" + (userCommand == null ? "(no command)" : userCommand)
            + "\". Creates an objective and deploys assets to it.";
        return new ExecutionPlan("plan-" + UUID.randomUUID(), rationale, actions);
    }

    /** Find the first {@code id} in the array result of the named tool, or null. */
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
