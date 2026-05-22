package com.assettracker.backend.graph;

public record DroneDetail(
    DroneNode drone,
    SquadronNode squadron,
    ObjectiveNode objective     // null if OPTIONAL MATCH found nothing
) {
}
