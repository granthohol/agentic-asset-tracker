package com.assettracker.backend.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Motion command on drone.commands.v1. See docs/COMMANDS.md. */
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
