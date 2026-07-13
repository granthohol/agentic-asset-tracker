package com.assettracker.backend.model;

// Immutable. Drone "moves" by swapping in a new record, not mutating fields.
public record Drone(
    String id, 
    double latitude, 
    double longitude,
    int batteryLevel, 
    DroneStatus status
) {}
