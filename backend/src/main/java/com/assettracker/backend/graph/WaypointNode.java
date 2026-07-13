package com.assettracker.backend.graph;

// A standalone labeled point of interest, stored as a :Waypoint node.
// Don't confuse with the ephemeral Waypoint value object (a drone's current target).
// This one is a persistent, user/agent-placed marker.
public record WaypointNode(
    String id,
    String name,
    double latitude,
    double longitude
) {
}
