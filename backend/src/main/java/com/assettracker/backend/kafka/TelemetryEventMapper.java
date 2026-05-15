package com.assettracker.backend.kafka;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.model.DroneStatus;

import org.springframework.stereotype.Component;

// Mapper class to map the TelemetryEvent to a Drone object
@Component
public class TelemetryEventMapper {

    public Drone mapToDrone(TelemetryEvent event) {
        return new Drone(event.droneId(), event.latitude(), event.longitude(), event.batteryLevel(), DroneStatus.valueOf(event.status().toUpperCase()));
    }
}
