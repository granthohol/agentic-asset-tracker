package com.assettracker.backend.graph;

import com.assettracker.backend.model.DroneStatus;

public record DroneNode (
    String id,
    double latitude,
    double longitude,
    int batteryLevel,
    DroneStatus status,
    Waypoint currentWaypoint
) {
    
}
