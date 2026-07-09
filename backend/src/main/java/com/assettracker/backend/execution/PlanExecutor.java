package com.assettracker.backend.execution;

import java.util.ArrayList;
import java.util.List;
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
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single, auditable write seam for user intent. Consumes approved plans from
 * {@code plan.events}, walks the actions in order, mints real ids for {@code tempId}
 * upserts, resolves {@code $tempId} references, and dispatches each action to
 * {@link GraphWriter} (Neo4j) or {@link CommandPublisher} (the {@code drone.commands.v1}
 * motion topic).
 *
 * <p>Two-phase swarm: all {@code FORM_UP} waypoints are published first; before the first
 * {@code ADVANCE} (or other non-FORM_UP) waypoint, the executor waits until live telemetry
 * shows the swarm has formed (or a timeout), then publishes the advance wave.
 */
@Component
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    /** Match frontend / edge arrival slack (~0.006°). */
    static final double ARRIVAL_DEG = 0.006;
    static final long FORM_UP_TIMEOUT_MS = 90_000L;
    static final long FORM_UP_POLL_MS = 300L;

    private final GraphWriter graphWriter;
    private final CommandPublisher commandPublisher;
    private final DroneService droneService;
    private final ObjectMapper mapper;

    public PlanExecutor(
        GraphWriter graphWriter,
        CommandPublisher commandPublisher,
        DroneService droneService,
        ObjectMapper mapper
    ) {
        this.graphWriter = graphWriter;
        this.commandPublisher = commandPublisher;
        this.droneService = droneService;
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
            log.error("Discarding malformed plan envelope: {}", e.getMessage());
            return;
        }
        execute(envelope.plan());
    }

    /** Walk the plan's actions in order; fail-fast on the first error. */
    void execute(ExecutionPlan plan) {
        ExecutionContext ctx = new ExecutionContext(plan.planId());
        List<FormUpTarget> formUps = new ArrayList<>();
        boolean waitedForFormUp = false;
        log.info("Executing plan {} ({} action(s))", plan.planId(), plan.actions().size());

        for (int i = 0; i < plan.actions().size(); i++) {
            PlanAction action = plan.actions().get(i);
            try {
                if (action instanceof PlanAction.SetWaypoint sw
                    && !isFormUp(sw.missionType())
                    && !formUps.isEmpty()
                    && !waitedForFormUp) {
                    waitForFormUp(plan.planId(), formUps);
                    waitedForFormUp = true;
                }

                dispatch(action, ctx, formUps);
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

    private void dispatch(PlanAction action, ExecutionContext ctx, List<FormUpTarget> formUps) {
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
                commandPublisher.publishSetWaypoint(a.droneId(), a.targetLat(), a.targetLng(), a.missionType());
                graphWriter.setDroneWaypoint(a.droneId(), new Waypoint(a.targetLat(), a.targetLng()));
                if (isFormUp(a.missionType())) {
                    formUps.add(new FormUpTarget(a.droneId(), a.targetLat(), a.targetLng()));
                }
            }
            case PlanAction.ClearWaypoint a ->
                graphWriter.clearDroneWaypoint(a.droneId());
        }
    }

    private void waitForFormUp(String planId, List<FormUpTarget> formUps) {
        log.info("Plan {}: waiting for {} FORM_UP drone(s) to arrive (timeout {}ms)",
            planId, formUps.size(), FORM_UP_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + FORM_UP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (allFormed(formUps)) {
                log.info("Plan {}: FORM_UP complete — publishing ADVANCE wave", planId);
                return;
            }
            try {
                Thread.sleep(FORM_UP_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Plan {}: FORM_UP wait interrupted — advancing anyway", planId);
                return;
            }
        }
        log.warn("Plan {}: FORM_UP wait timed out — advancing anyway", planId);
    }

    private boolean allFormed(List<FormUpTarget> formUps) {
        for (FormUpTarget t : formUps) {
            Drone d = droneService.getDrone(t.droneId());
            if (d == null) {
                return false;
            }
            double dist = Math.hypot(d.latitude() - t.targetLat(), d.longitude() - t.targetLng());
            if (dist > ARRIVAL_DEG) {
                return false;
            }
        }
        return true;
    }

    static boolean isFormUp(String missionType) {
        if (missionType == null) {
            return false;
        }
        String m = missionType.trim().toUpperCase();
        return "FORM_UP".equals(m) || "HOLD".equals(m);
    }

    private String resolveUpsertId(String id, String tempId, String label, ExecutionContext ctx) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        String realId = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        ctx.recordMint(tempId, realId);
        return realId;
    }

    record FormUpTarget(String droneId, double targetLat, double targetLng) {}
}
