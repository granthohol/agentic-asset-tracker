package com.assettracker.backend.model;

// Record: an immutable data carrier
// Records reduce the boilerplate code needed to create a simple data-holding class

// Drone object is pure data; it never has mutable behavior. Why?
// When a drone "moves", the service doesn't mutate the existing Drone instance,
// instead it replaces it with a brand new Drone record. It is a Value Object
public record Drone(String id, double latitude, double longitude,
                    int batteryLevel, DroneStatus status
) {
    
}
