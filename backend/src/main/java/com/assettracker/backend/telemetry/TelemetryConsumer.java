package com.assettracker.backend.telemetry;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;

@Component
public class TelemetryConsumer {

    private final DroneService droneService;
    private final TelemetryEventMapper telemetryEventMapper;
    private final TelemetryWebSocket telemetryWebSocket;
    private final TelemetryPersistence telemetryPersistence;

    public TelemetryConsumer(DroneService droneService, TelemetryEventMapper telemetryEventMapper,
        TelemetryWebSocket telemetryWebSocket, TelemetryPersistence telemetryPersistence) {
        this.droneService = droneService;
        this.telemetryEventMapper = telemetryEventMapper;
        this.telemetryWebSocket = telemetryWebSocket;
        this.telemetryPersistence = telemetryPersistence;
    }

    /**
     * Hot path: update in-memory map, mark dirty for WS, buffer for async persistence.
     * SQLite + Neo4j writes happen on a scheduled flush so this never blocks on I/O.
     */
    @KafkaListener(
        topics = "drone.telemetry.v1"
    )
    public void onTelemetry(TelemetryEvent event) {
        Drone drone = telemetryEventMapper.mapToDrone(event);

        droneService.updateDroneMap(drone);
        telemetryWebSocket.markDirty(drone);
        telemetryPersistence.enqueue(event);
    }

}
