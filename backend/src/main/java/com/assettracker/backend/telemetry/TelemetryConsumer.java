package com.assettracker.backend.telemetry;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;

@Component
public class TelemetryConsumer {

    private final DroneService droneService;
    private final TelemetryEventMapper telemetryEventMapper;
    private final TelemetryEventLog telemetryEventLog;
    private final TelemetryWebSocket telemetryWebSocket;

    public TelemetryConsumer(DroneService droneService, TelemetryEventMapper telemetryEventMapper, TelemetryEventLog telemetryEventLog, TelemetryWebSocket telemetryWebSocket) {
        this.droneService = droneService;
        this.telemetryEventMapper = telemetryEventMapper;
        this.telemetryEventLog = telemetryEventLog;
        this.telemetryWebSocket = telemetryWebSocket;
    }

    @KafkaListener(
        topics = "drone.telemetry.v1"
    )
    public void onTelemetry(TelemetryEvent event) {
        Drone drone = telemetryEventMapper.mapToDrone(event);

        // log the telemetry event in the SQLite database
        telemetryEventLog.append(event);

        // when we get a telemetry event, map the event to a Drone object and update the Drone map
        droneService.updateDroneMap(drone);

        // broadcast drone update across web socket to the frontend
        telemetryWebSocket.broadcastDroneUpdate(drone);
    }
    
}
