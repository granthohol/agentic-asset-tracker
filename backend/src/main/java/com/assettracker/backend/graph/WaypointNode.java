package com.assettracker.backend.graph;

// A standalone, labeled point of interest on the map, stored as a :Waypoint node.
// NOTE: distinct from the ephemeral Waypoint value object (a drone's current target
// used by setWaypoint / drone tasking). This is a persistent, user/agent-placed marker.
public record WaypointNode(
    String id,
    String name,
    double latitude,
    double longitude
) {
}
