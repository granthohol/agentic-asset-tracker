package com.assettracker.backend.graph;

// A tracked contact on the map (friendly/hostile/unknown, aerial/ground).
// Static position for now, no telemetry feed. Stored as a :Track node.
public record TrackNode(
    String id,
    String name,
    Affiliation affiliation,
    TrackDomain domain,
    double latitude,
    double longitude
) {
}
