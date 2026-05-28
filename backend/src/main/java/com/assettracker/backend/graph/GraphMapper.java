package com.assettracker.backend.graph;

import org.springframework.stereotype.Component;

import com.assettracker.backend.model.Drone;

@Component
public class GraphMapper {

    public DroneNode toDroneNode(Drone drone) {
        return new DroneNode(
            drone.id(),
            drone.latitude(),
            drone.longitude(),
            drone.batteryLevel(),
            drone.status(),
            null
        );
    }
    
}
