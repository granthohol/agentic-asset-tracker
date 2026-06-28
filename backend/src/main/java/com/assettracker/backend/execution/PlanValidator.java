package com.assettracker.backend.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;

/**
 * Pre-publish validation for {@code /api/execute-plan}. Rejects a plan (throws
 * {@link IllegalArgumentException}) before anything is enqueued, so a malformed plan never
 * reaches the executor. This is the structural/intra-plan layer of validation:
 *
 * <ul>
 *   <li>every upsert declares exactly one of {@code id} or {@code tempId};</li>
 *   <li>every {@code "$ref"} resolves to a {@code tempId} declared <b>earlier</b> in the list;</li>
 *   <li>required string fields are present; coordinates are in range.</li>
 * </ul>
 *
 * Existence of literal ids in Neo4j is verified later by the executor (it cannot be known
 * here without a graph read, and we keep this layer pure/fast).
 */
@Component
public class PlanValidator {

    public void validate(ExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        List<PlanAction> actions = plan.actions();
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("plan has no actions");
        }

        Set<String> declaredTempIds = new HashSet<>();
        for (int i = 0; i < actions.size(); i++) {
            validateAction(actions.get(i), i, declaredTempIds);
        }
    }

    private void validateAction(PlanAction action, int index, Set<String> declaredTempIds) {
        switch (action) {
            case PlanAction.UpsertSquadron a -> {
                String tempId = requireXorId(a.id(), a.tempId(), index, "upsertSquadron");
                requireText(a.name(), index, "upsertSquadron.name");
                requireText(a.sectorId(), index, "upsertSquadron.sectorId");
                declareTemp(tempId, declaredTempIds, index);
            }
            case PlanAction.UpsertObjective a -> {
                String tempId = requireXorId(a.id(), a.tempId(), index, "upsertObjective");
                requireText(a.name(), index, "upsertObjective.name");
                requireLatLng(a.centerLatitude(), a.centerLongitude(), index, "upsertObjective");
                declareTemp(tempId, declaredTempIds, index);
            }
            case PlanAction.AssignDroneToSquadron a -> {
                requireLiteral(a.droneId(), index, "assignDroneToSquadron.droneId");
                requireRefResolvable(a.squadronId(), declaredTempIds, index, "assignDroneToSquadron.squadronId");
            }
            case PlanAction.DeploySquadronToObjective a -> {
                requireRefResolvable(a.squadronId(), declaredTempIds, index, "deploySquadronToObjective.squadronId");
                requireRefResolvable(a.objectiveId(), declaredTempIds, index, "deploySquadronToObjective.objectiveId");
            }
            case PlanAction.RemoveDroneAssignment a ->
                requireLiteral(a.droneId(), index, "removeDroneAssignment.droneId");
            case PlanAction.RemoveSquadronFromObjective a ->
                requireRefResolvable(a.squadronId(), declaredTempIds, index, "removeSquadronFromObjective.squadronId");
            case PlanAction.SetWaypoint a -> {
                requireLiteral(a.droneId(), index, "setWaypoint.droneId");
                requireCoord(a.targetLat(), -90, 90, index, "setWaypoint.targetLat");
                requireCoord(a.targetLng(), -180, 180, index, "setWaypoint.targetLng");
            }
            case PlanAction.ClearWaypoint a ->
                requireLiteral(a.droneId(), index, "clearWaypoint.droneId");
        }
    }

    /** Returns the tempId (or null if a literal id was used). Enforces exactly-one. */
    private String requireXorId(String id, String tempId, int index, String op) {
        boolean hasId = isText(id);
        boolean hasTemp = isText(tempId);
        if (hasId == hasTemp) {
            throw new IllegalArgumentException(
                "action " + index + " (" + op + "): exactly one of id or tempId is required");
        }
        if (hasTemp && id != null && id.startsWith("$")) {
            throw new IllegalArgumentException("action " + index + " (" + op + "): id must not be a $ref");
        }
        return hasTemp ? tempId : null;
    }

    private void declareTemp(String tempId, Set<String> declaredTempIds, int index) {
        if (tempId != null) {
            if (tempId.startsWith("$")) {
                throw new IllegalArgumentException("action " + index + ": tempId must not start with '$'");
            }
            if (!declaredTempIds.add(tempId)) {
                throw new IllegalArgumentException("action " + index + ": duplicate tempId '" + tempId + "'");
            }
        }
    }

    /** A reference that may be a literal id or a "$tempId" pointing at an earlier upsert. */
    private void requireRefResolvable(String value, Set<String> declaredTempIds, int index, String field) {
        requireText(value, index, field);
        if (value.startsWith("$")) {
            String ref = value.substring(1);
            if (!declaredTempIds.contains(ref)) {
                throw new IllegalArgumentException(
                    "action " + index + " (" + field + "): unresolved reference '" + value
                    + "' (no earlier action declared tempId '" + ref + "')");
            }
        }
    }

    /** A field that must be a present literal id (no $ref allowed, e.g. drone ids). */
    private void requireLiteral(String value, int index, String field) {
        requireText(value, index, field);
        if (value.startsWith("$")) {
            throw new IllegalArgumentException("action " + index + " (" + field + "): must be a literal id, not a $ref");
        }
    }

    private void requireText(String value, int index, String field) {
        if (!isText(value)) {
            throw new IllegalArgumentException("action " + index + " (" + field + "): required and must be non-blank");
        }
    }

    private void requireLatLng(Double lat, Double lng, int index, String op) {
        // Center is optional; but if one is present, both must be, and both in range.
        if (lat == null && lng == null) {
            return;
        }
        if (lat == null || lng == null) {
            throw new IllegalArgumentException(
                "action " + index + " (" + op + "): centerLatitude and centerLongitude must be provided together");
        }
        requireCoord(lat, -90, 90, index, op + ".centerLatitude");
        requireCoord(lng, -180, 180, index, op + ".centerLongitude");
    }

    private void requireCoord(double value, double min, double max, int index, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                "action " + index + " (" + field + "): " + value + " out of range [" + min + ", " + max + "]");
        }
    }

    private boolean isText(String s) {
        return s != null && !s.isBlank();
    }
}
