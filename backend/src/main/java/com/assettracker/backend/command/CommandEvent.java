package com.assettracker.backend.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The internal motion message published to Kafka topic {@code drone.commands.v1}
 * and consumed by the Python edge simulator. See docs/COMMANDS.md.
 *
 * <p>This is the {@code SET_WAYPOINT} command shape. Field names match the wire
 * contract the Python consumer reads: note {@code mission_type} is snake_case on
 * the wire, mapped here via {@link JsonProperty} so Java stays camelCase.
 *
 * <p>{@code commandId} powers Python-side dedup and is minted server-side by
 * {@link CommandPublisher}; callers never set it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandEvent(
    String droneId,
    double targetLat,
    double targetLng,
    @JsonProperty("mission_type") String missionType,
    long issuedAt,
    String commandId
) {}
