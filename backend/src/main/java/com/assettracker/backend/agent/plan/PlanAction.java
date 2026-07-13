package com.assettracker.backend.agent.plan;

import java.util.List;

import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One action in an ExecutionPlan. op is the Jackson discriminator.
 * Sealed interface: this list is the full vocabulary PlanExecutor can run.
 * Id fields can be a real id or $tempId (declared earlier in the same plan). See docs/PLAN.md.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PlanAction.UpsertSquadron.class, name = "upsertSquadron"),
    @JsonSubTypes.Type(value = PlanAction.UpsertObjective.class, name = "upsertObjective"),
    @JsonSubTypes.Type(value = PlanAction.AssignDroneToSquadron.class, name = "assignDroneToSquadron"),
    @JsonSubTypes.Type(value = PlanAction.DeploySquadronToObjective.class, name = "deploySquadronToObjective"),
    @JsonSubTypes.Type(value = PlanAction.RemoveDroneAssignment.class, name = "removeDroneAssignment"),
    @JsonSubTypes.Type(value = PlanAction.RemoveSquadronFromObjective.class, name = "removeSquadronFromObjective"),
    @JsonSubTypes.Type(value = PlanAction.SetWaypoint.class, name = "setWaypoint"),
    @JsonSubTypes.Type(value = PlanAction.ApplyFormation.class, name = "applyFormation"),
    @JsonSubTypes.Type(value = PlanAction.ClearWaypoint.class, name = "clearWaypoint"),
    @JsonSubTypes.Type(value = PlanAction.UpsertTrack.class, name = "upsertTrack"),
    @JsonSubTypes.Type(value = PlanAction.UpsertWaypoint.class, name = "upsertWaypoint"),
    @JsonSubTypes.Type(value = PlanAction.UpsertZone.class, name = "upsertZone"),
    @JsonSubTypes.Type(value = PlanAction.RemoveTrack.class, name = "removeTrack"),
    @JsonSubTypes.Type(value = PlanAction.RemoveWaypoint.class, name = "removeWaypoint"),
    @JsonSubTypes.Type(value = PlanAction.RemoveZone.class, name = "removeZone"),
})
public sealed interface PlanAction {

    /** Create or update a squadron. Exactly one of id (existing) or tempId (minted on execute). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertSquadron(
        String id,
        String tempId,
        String name,
        String sectorId
    ) implements PlanAction {}

    /** Create or update an objective. id xor tempId. See docs/ONTOLOGY.md for location fields. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertObjective(
        String id,
        String tempId,
        String name,
        int priority,
        Double centerLatitude,
        Double centerLongitude,
        Double radiusMeters,
        String targetEntityId
    ) implements PlanAction {}

    /** Attach a drone to a squadron (replaces any existing assignment). */
    record AssignDroneToSquadron(
        String droneId,
        String squadronId
    ) implements PlanAction {}

    /** Deploy a squadron to an objective (replaces any existing deployment). */
    record DeploySquadronToObjective(
        String squadronId,
        String objectiveId
    ) implements PlanAction {}

    /** Detach a drone from its squadron. */
    record RemoveDroneAssignment(
        String droneId
    ) implements PlanAction {}

    /** Undeploy a squadron from its objective. */
    record RemoveSquadronFromObjective(
        String squadronId
    ) implements PlanAction {}

    /** Route a drone to a waypoint. Publishes SET_WAYPOINT and mirrors target into the graph. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetWaypoint(
        String droneId,
        double targetLat,
        double targetLng,
        @JsonProperty("mission_type") String missionType
    ) implements PlanAction {}

    /**
     * Swarm macro: expands to per-drone setWaypoints before the plan hits the executor.
     * Lets the model emit ~2 actions for a two-phase swarm instead of ~100.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApplyFormation(
        FormationType formationType,
        double centerLat,
        double centerLng,
        List<String> droneIds,
        @JsonProperty("mission_type") String missionType,
        Double spacingMeters,
        Double facingLat,
        Double facingLng
    ) implements PlanAction {}

    /** Clear a drone's current waypoint (it resumes free movement). */
    record ClearWaypoint(
        String droneId
    ) implements PlanAction {}

    /** Create or update a map track (static contact, not a drone). id xor tempId. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertTrack(
        String id,
        String tempId,
        String name,
        Affiliation affiliation,
        TrackDomain domain,
        Double latitude,
        Double longitude
    ) implements PlanAction {}

    /** Persistent map waypoint (POI). Not the same as setWaypoint, which is drone motion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertWaypoint(
        String id,
        String tempId,
        String name,
        Double latitude,
        Double longitude
    ) implements PlanAction {}

    /** Map zone. CIRCLE needs center + radiusMeters. POLYGON needs vertices (>= 3). id xor tempId. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertZone(
        String id,
        String tempId,
        String name,
        ZoneType type,
        ZoneShape shape,
        Double centerLatitude,
        Double centerLongitude,
        Double radiusMeters,
        List<List<Double>> vertices
    ) implements PlanAction {}

    /** Delete a persistent map track by its real id. */
    record RemoveTrack(
        String id
    ) implements PlanAction {}

    /** Delete a persistent map waypoint by its real id. */
    record RemoveWaypoint(
        String id
    ) implements PlanAction {}

    /** Delete a map zone by its real id. */
    record RemoveZone(
        String id
    ) implements PlanAction {}
}
