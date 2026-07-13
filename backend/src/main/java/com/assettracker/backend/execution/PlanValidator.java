package com.assettracker.backend.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;
import com.assettracker.backend.graph.ZoneShape;

/**
 * Pre-publish validation for /api/execute-plan. Rejects bad plans before they hit Kafka.
 * Checks: id xor tempId, $refs resolve to earlier tempIds, coords in range.
 * Literal id existence is checked later by the executor.
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
            case PlanAction.ApplyFormation a -> {
                if (a.formationType() == null) {
                    throw new IllegalArgumentException(
                        "action " + index + " (applyFormation): formationType is required");
                }
                requireCoord(a.centerLat(), -90, 90, index, "applyFormation.centerLat");
                requireCoord(a.centerLng(), -180, 180, index, "applyFormation.centerLng");
                if (a.droneIds() == null || a.droneIds().isEmpty()) {
                    throw new IllegalArgumentException(
                        "action " + index + " (applyFormation): droneIds must be non-empty");
                }
                for (String droneId : a.droneIds()) {
                    requireLiteral(droneId, index, "applyFormation.droneIds[]");
                }
                if (a.spacingMeters() != null && a.spacingMeters() <= 0) {
                    throw new IllegalArgumentException(
                        "action " + index + " (applyFormation): spacingMeters must be > 0");
                }
                requireLatLng(a.facingLat(), a.facingLng(), index, "applyFormation.facing");
            }
            case PlanAction.ClearWaypoint a ->
                requireLiteral(a.droneId(), index, "clearWaypoint.droneId");
            case PlanAction.UpsertTrack a -> {
                String tempId = requireXorId(a.id(), a.tempId(), index, "upsertTrack");
                requireText(a.name(), index, "upsertTrack.name");
                if (a.affiliation() == null || a.domain() == null) {
                    throw new IllegalArgumentException(
                        "action " + index + " (upsertTrack): affiliation and domain are required");
                }
                requireBothLatLng(a.latitude(), a.longitude(), index, "upsertTrack");
                declareTemp(tempId, declaredTempIds, index);
            }
            case PlanAction.UpsertWaypoint a -> {
                String tempId = requireXorId(a.id(), a.tempId(), index, "upsertWaypoint");
                requireText(a.name(), index, "upsertWaypoint.name");
                requireBothLatLng(a.latitude(), a.longitude(), index, "upsertWaypoint");
                declareTemp(tempId, declaredTempIds, index);
            }
            case PlanAction.UpsertZone a -> {
                String tempId = requireXorId(a.id(), a.tempId(), index, "upsertZone");
                requireText(a.name(), index, "upsertZone.name");
                if (a.type() == null || a.shape() == null) {
                    throw new IllegalArgumentException(
                        "action " + index + " (upsertZone): type and shape are required");
                }
                validateZoneGeometry(a, index);
                declareTemp(tempId, declaredTempIds, index);
            }
            case PlanAction.RemoveTrack a ->
                requireLiteral(a.id(), index, "removeTrack.id");
            case PlanAction.RemoveWaypoint a ->
                requireLiteral(a.id(), index, "removeWaypoint.id");
            case PlanAction.RemoveZone a ->
                requireLiteral(a.id(), index, "removeZone.id");
        }
    }

    private void validateZoneGeometry(PlanAction.UpsertZone a, int index) {
        if (a.shape() == ZoneShape.CIRCLE) {
            requireBothLatLng(a.centerLatitude(), a.centerLongitude(), index, "upsertZone.center");
            if (a.radiusMeters() == null || a.radiusMeters() <= 0) {
                throw new IllegalArgumentException(
                    "action " + index + " (upsertZone): CIRCLE requires radiusMeters > 0");
            }
            return;
        }
        // POLYGON
        List<List<Double>> vertices = a.vertices();
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException(
                "action " + index + " (upsertZone): POLYGON requires at least 3 vertices");
        }
        for (int v = 0; v < vertices.size(); v++) {
            List<Double> vertex = vertices.get(v);
            if (vertex == null || vertex.size() != 2 || vertex.get(0) == null || vertex.get(1) == null) {
                throw new IllegalArgumentException(
                    "action " + index + " (upsertZone): vertex " + v + " must be [lat, lng]");
            }
            requireCoord(vertex.get(0), -90, 90, index, "upsertZone.vertex[" + v + "].lat");
            requireCoord(vertex.get(1), -180, 180, index, "upsertZone.vertex[" + v + "].lng");
        }
    }

    /** Returns tempId, or null if a literal id was used. */
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

    /** Literal id or $tempId pointing at an earlier upsert. */
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

    /** Must be a literal id, no $ref (e.g. drone ids). */
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
        // Center optional, but if one is set both must be and both in range.
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

    /** Both coords required and in range. */
    private void requireBothLatLng(Double lat, Double lng, int index, String op) {
        if (lat == null || lng == null) {
            throw new IllegalArgumentException(
                "action " + index + " (" + op + "): latitude and longitude are required");
        }
        requireCoord(lat, -90, 90, index, op + ".latitude");
        requireCoord(lng, -180, 180, index, op + ".longitude");
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
