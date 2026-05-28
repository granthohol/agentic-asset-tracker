package com.assettracker.backend.graph;

public record DroneDetail(
    DroneNode drone,
    SquadronNode squadron,      // null if drone has no ASSIGNED_TO edge yet
    ObjectiveNode objective     // null if squadron has no DEPLOYED_FOR edge
) {
}
