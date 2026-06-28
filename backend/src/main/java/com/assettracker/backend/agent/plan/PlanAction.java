package com.assettracker.backend.agent.plan;

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
    @JsonSubTypes.Type(value = PlanAction.ClearWaypoint.class, name = "clearWaypoint"),
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

    /** Clear a drone's current waypoint (it resumes free movement). */
    record ClearWaypoint(
        String droneId
    ) implements PlanAction {}
}
