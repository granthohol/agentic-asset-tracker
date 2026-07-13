package com.assettracker.backend.graph;

public record DroneDetail(
    DroneNode drone,
    SquadronNode squadron,      // null without ASSIGNED_TO
    ObjectiveNode objective     // null without DEPLOYED_FOR
) {
}
