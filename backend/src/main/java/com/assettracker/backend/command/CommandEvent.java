package com.assettracker.backend.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Motion message published to Kafka topic {@code drone.commands.v1} and consumed by
 * the Python edge simulator. See docs/COMMANDS.md.
 *
 * <p>{@code type} is {@code SET_WAYPOINT} (steer) or {@code CLEAR_WAYPOINT} (resume walk).
 * For clear commands, {@code targetLat}/{@code targetLng}/{@code mission_type} are omitted.
 *
 * <p>{@code commandId} is minted server-side by {@link CommandPublisher}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandEvent(
    String type,
    String droneId,
    Double targetLat,
    Double targetLng,
    @JsonProperty("mission_type") String missionType,
    long issuedAt,
    String commandId
) {
    public static final String TYPE_SET_WAYPOINT = "SET_WAYPOINT";
    public static final String TYPE_CLEAR_WAYPOINT = "CLEAR_WAYPOINT";
}
