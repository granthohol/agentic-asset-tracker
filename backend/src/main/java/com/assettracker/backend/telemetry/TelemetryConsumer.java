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
     * Phase 4: the hot path stays cheap and non-blocking. Update the in-memory map (the
     * live source of truth), mark the drone dirty for the next batched WS tick, and hand
     * the raw event to the persistence buffer. SQLite + Neo4j writes happen on a separate
     * scheduled flush so this listener never blocks on I/O.
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
