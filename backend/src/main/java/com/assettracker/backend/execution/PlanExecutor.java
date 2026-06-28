package com.assettracker.backend.execution;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.command.CommandPublisher;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.ObjectiveNode;
import com.assettracker.backend.graph.SquadronNode;
import com.assettracker.backend.graph.Waypoint;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single, auditable write seam for user intent. Consumes approved plans from
 * {@code plan.events}, walks the actions in order, mints real ids for {@code tempId}
 * upserts, resolves {@code $tempId} references, and dispatches each action to
 * {@link GraphWriter} (Neo4j) or {@link CommandPublisher} (the {@code drone.commands.v1}
 * motion topic).
 *
 * <p>This is the only class besides the telemetry sensor-mirror that writes to Neo4j on
 * purpose, and the only legitimate caller of {@link CommandPublisher}. The {@code agent}
 * package never imports {@link GraphWriter}; all of that lives here, downstream of human
 * approval and a durable Kafka record.
 *
 * <p>Failure policy: <b>fail-fast</b>. The first failing action halts the plan; remaining
 * actions are skipped and the partial report is logged. We do not rethrow (no poison-pill
 * redelivery loop) — a fix is a corrected re-POST to {@code /api/execute-plan}.
 */
@Component
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final GraphWriter graphWriter;
    private final CommandPublisher commandPublisher;
    private final ObjectMapper mapper;

    public PlanExecutor(GraphWriter graphWriter, CommandPublisher commandPublisher, ObjectMapper mapper) {
        this.graphWriter = graphWriter;
        this.commandPublisher = commandPublisher;
        this.mapper = mapper;
    }

    @KafkaListener(
        topics = PlanPublisher.TOPIC,
        groupId = "plan-executor",
        containerFactory = "planEventsListenerContainerFactory"
    )
    public void onPlanEnvelope(String json) {
        PlanEnvelope envelope;
        try {
            envelope = mapper.readValue(json, PlanEnvelope.class);
        } catch (Exception e) {
            // A malformed envelope is unrecoverable; log and drop rather than loop forever.
            log.error("Discarding malformed plan envelope: {}", e.getMessage());
            return;
        }
        execute(envelope.plan());
    }

    /** Walk the plan's actions in order; fail-fast on the first error. */
    void execute(ExecutionPlan plan) {
        ExecutionContext ctx = new ExecutionContext(plan.planId());
        log.info("Executing plan {} ({} action(s))", plan.planId(), plan.actions().size());

        for (int i = 0; i < plan.actions().size(); i++) {
            PlanAction action = plan.actions().get(i);
            try {
                dispatch(action, ctx);
                String line = "[" + i + "] " + action.getClass().getSimpleName() + " ok";
                ctx.report.add(line);
                log.info("  {}", line);
            } catch (Exception e) {
                String line = "[" + i + "] " + action.getClass().getSimpleName() + " FAILED: " + e.getMessage();
                ctx.report.add(line);
                log.error("  {} -- halting plan {}", line, plan.planId());
                log.error("Plan {} partial report: {}", plan.planId(), ctx.report);
                return;
            }
        }
        log.info("Plan {} complete: {}", plan.planId(), ctx.report);
    }

    private void dispatch(PlanAction action, ExecutionContext ctx) {
        switch (action) {
            case PlanAction.UpsertSquadron a -> {
                String id = resolveUpsertId(a.id(), a.tempId(), "squadron", ctx);
                graphWriter.upsertSquadron(new SquadronNode(id, a.name(), a.sectorId()));
            }
            case PlanAction.UpsertObjective a -> {
                String id = resolveUpsertId(a.id(), a.tempId(), "objective", ctx);
                graphWriter.upsertObjective(new ObjectiveNode(
                    id, a.name(), a.priority(),
                    a.centerLatitude(), a.centerLongitude(), a.targetEntityId(), a.radiusMeters()
                ));
            }
            case PlanAction.AssignDroneToSquadron a ->
                graphWriter.assignDroneToSquadron(a.droneId(), ctx.resolve(a.squadronId()));
            case PlanAction.DeploySquadronToObjective a ->
                graphWriter.deploySquadronToObjective(ctx.resolve(a.squadronId()), ctx.resolve(a.objectiveId()));
            case PlanAction.RemoveDroneAssignment a ->
                graphWriter.removeDroneAssignment(a.droneId());
            case PlanAction.RemoveSquadronFromObjective a ->
                graphWriter.removeSquadronFromObjective(ctx.resolve(a.squadronId()));
            case PlanAction.SetWaypoint a -> {
                // Motion + graph mirror: publish the command AND reflect the target in Neo4j.
                commandPublisher.publishSetWaypoint(a.droneId(), a.targetLat(), a.targetLng(), a.missionType());
                graphWriter.setDroneWaypoint(a.droneId(), new Waypoint(a.targetLat(), a.targetLng()));
            }
            case PlanAction.ClearWaypoint a ->
                graphWriter.clearDroneWaypoint(a.droneId());
        }
    }

    /**
     * Literal {@code id} -> use as-is (update existing). {@code tempId} -> mint a real id,
     * record it for later {@code $tempId} resolution, and return it.
     */
    private String resolveUpsertId(String id, String tempId, String label, ExecutionContext ctx) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        String realId = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        ctx.recordMint(tempId, realId);
        return realId;
    }
}
