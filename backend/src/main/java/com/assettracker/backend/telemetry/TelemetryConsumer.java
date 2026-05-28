package com.assettracker.backend.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.assettracker.backend.graph.GraphMapper;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;

@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final DroneService droneService;
    private final TelemetryEventMapper telemetryEventMapper;
    private final TelemetryEventLog telemetryEventLog;
    private final TelemetryWebSocket telemetryWebSocket;
    private final GraphWriter graphWriter;
    private final GraphMapper graphMapper;

    public TelemetryConsumer(DroneService droneService, TelemetryEventMapper telemetryEventMapper, 
        TelemetryEventLog telemetryEventLog, TelemetryWebSocket telemetryWebSocket, 
        GraphWriter graphWriter, GraphMapper graphMapper) {
        this.droneService = droneService;
        this.telemetryEventMapper = telemetryEventMapper;
        this.telemetryEventLog = telemetryEventLog;
        this.telemetryWebSocket = telemetryWebSocket;
        this.graphWriter = graphWriter;
        this.graphMapper = graphMapper;
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

        // Graph projection: upsert drone properties only; no squadron assignment.
        try {
            graphWriter.upsertDrone(graphMapper.toDroneNode(drone));
        } catch (Exception e) {
            log.warn("Neo4j upsert failed for {}: {}", drone.id(), e.getMessage());
        }
    }
    
}
