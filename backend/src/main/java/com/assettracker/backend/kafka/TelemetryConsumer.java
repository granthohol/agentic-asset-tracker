package com.assettracker.backend.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.assettracker.backend.service.DroneService;

@Component
public class TelemetryConsumer {

    private final DroneService droneService;
    private final TelemetryEventMapper telemetryEventMapper;

    public TelemetryConsumer(DroneService droneService, TelemetryEventMapper telemetryEventMapper) {
        this.droneService = droneService;
        this.telemetryEventMapper = telemetryEventMapper;
    }

    @KafkaListener(
        topics = "drone.telemetry.v1"
    )
    public void onTelemetry(TelemetryEvent event) {
        // when we get a telemetry event, map the event to a Drone object and update the Drone map
        droneService.updateDroneMap(telemetryEventMapper.mapToDrone(event));
    }
    
}
