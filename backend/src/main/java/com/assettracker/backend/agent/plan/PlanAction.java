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
 * One executable operation in an {@link ExecutionPlan}. The {@code op} string is the
 * polymorphic discriminator: Jackson reads it to pick the concrete record, and
 * serialization writes it back. Anything whose {@code op} is not listed in
 * {@link JsonSubTypes} fails to deserialize — <b>the schema is the policy</b>.
 *
 * <p>This is a {@code sealed} interface: the permitted set below is the complete,
 * exhaustive vocabulary the {@code PlanExecutor} can dispatch. Adding a capability
 * is a deliberate code change here, never emergent LLM behavior.
 *
 * <p><b>Temporary-id references.</b> Any id-typed string argument may be either a real
 * Neo4j id (must already exist) or a {@code "$tempId"} reference to an entity created
 * earlier in the same plan via an {@code upsert*} action's {@code tempId}. Resolution
 * happens in the executor, not here. See docs/PLAN.md.
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

    /**
     * Create or update a squadron. Provide exactly one of {@code id} (an existing
     * squadron to update) or {@code tempId} (a placeholder the executor replaces with a
     * freshly-minted real id, recorded for later {@code $tempId} references in this plan).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertSquadron(
        String id,
        String tempId,
        String name,
        String sectorId
    ) implements PlanAction {}

    /**
     * Create or update an objective. Optional geo fields describe where it is:
     * a point ({@code centerLatitude}/{@code centerLongitude}), an area
     * ({@code radiusMeters}), or a tracked entity ({@code targetEntityId}). See
     * docs/ONTOLOGY.md for the location semantics. {@code id} xor {@code tempId} as above.
     */
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

    /**
     * Motion intent: route a drone to a waypoint. The executor both publishes a
     * {@code SET_WAYPOINT} command (to the edge) and mirrors the target into the graph.
     * {@code mission_type} is snake_case on the wire to match the command contract.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetWaypoint(
        String droneId,
        double targetLat,
        double targetLng,
        @JsonProperty("mission_type") String missionType
    ) implements PlanAction {}

    /**
     * Compact swarm macro: place {@code droneIds} into a {@code formationType} formation around
     * ({@code centerLat}, {@code centerLng}), optionally facing ({@code facingLat},
     * {@code facingLng}). The backend <b>expands</b> this into one {@link SetWaypoint} per drone
     * (carrying {@code missionType}) before the plan leaves the orchestrator or reaches the
     * executor — the frontend and executor only ever see the resulting {@code setWaypoint}s. Lets
     * the model emit ~2 actions for a two-phase swarm instead of ~100. See docs/PLAN.md.
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

    /**
     * Create or update a persistent map track (a tracked contact). {@code id} xor
     * {@code tempId} as with the other upserts. This is a map annotation, not a drone.
     */
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

    /**
     * Create or update a persistent, labeled map waypoint (point of interest).
     * <b>Distinct from {@link SetWaypoint}</b>, which is ephemeral drone motion tasking;
     * this is a durable {@code :Waypoint} marker on the map.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertWaypoint(
        String id,
        String tempId,
        String name,
        Double latitude,
        Double longitude
    ) implements PlanAction {}

    /**
     * Create or update a map zone. CIRCLE => {@code centerLatitude}/{@code centerLongitude}
     * + {@code radiusMeters}. POLYGON => {@code vertices} as [[lat,lng],...] with >= 3 points.
     * {@code id} xor {@code tempId} as above.
     */
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
