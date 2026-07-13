package com.assettracker.backend.telemetry;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;
import com.assettracker.backend.telemetry.proto.DroneState;
import com.assettracker.backend.telemetry.proto.FrameType;
import com.assettracker.backend.telemetry.proto.TelemetryFrame;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams telemetry as binary protobuf frames (see proto/telemetry.proto).
 * SNAPSHOT on connect, coalesced BATCH on each broadcast tick.
 */
@Component
public class TelemetryWebSocket extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final DroneService droneService;

    // Coalesce dirty drones between ticks. ~20 batched frames/s instead of ~20k JSON frames/s/client.
    private final Map<String, Drone> dirty = new ConcurrentHashMap<>();

    public TelemetryWebSocket(DroneService droneService) {
        this.droneService = droneService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        TelemetryFrame snapshot = frame(FrameType.SNAPSHOT, droneService.getAllDrones());
        sendBinary(session, snapshot);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** Record the latest state for a drone; the next tick flushes it to all clients. */
    public void markDirty(Drone drone) {
        dirty.put(drone.id(), drone);
    }

    /** Drain dirty set, send one BATCH frame per tick. No dirty drones = no frame. */
    @Scheduled(fixedRateString = "${telemetry.broadcast.tick-ms:50}")
    public void flushBatch() {
        if (dirty.isEmpty() || sessions.isEmpty()) {
            return;
        }

        List<Drone> drones = new ArrayList<>(dirty.values());
        // Snapshot what we're flushing; updates mid-flush stay dirty for next tick.
        drones.forEach(d -> dirty.remove(d.id(), d));

        byte[] payload = frame(FrameType.BATCH, drones).toByteArray();
        BinaryMessage message = new BinaryMessage(payload);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {    // one sender at a time per session
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                sessions.remove(session);
            }
        }
    }

    private TelemetryFrame frame(FrameType type, List<Drone> drones) {
        TelemetryFrame.Builder builder = TelemetryFrame.newBuilder().setType(type);
        for (Drone drone : drones) {
            builder.addDrones(toProto(drone));
        }
        return builder.build();
    }

    private DroneState toProto(Drone drone) {
        return DroneState.newBuilder()
            .setId(drone.id())
            .setLat(drone.latitude())
            .setLng(drone.longitude())
            .setBattery(drone.batteryLevel())
            .setStatus(toProtoStatus(drone.status()))
            .build();
    }

    private com.assettracker.backend.telemetry.proto.DroneStatus toProtoStatus(
        com.assettracker.backend.model.DroneStatus status
    ) {
        return switch (status) {
            case ACTIVE -> com.assettracker.backend.telemetry.proto.DroneStatus.ACTIVE;
            case LOW_BATTERY -> com.assettracker.backend.telemetry.proto.DroneStatus.LOW_BATTERY;
            case OFFLINE -> com.assettracker.backend.telemetry.proto.DroneStatus.OFFLINE;
        };
    }

    private void sendBinary(WebSocketSession session, TelemetryFrame frame) throws IOException {
        BinaryMessage message = new BinaryMessage(frame.toByteArray());
        synchronized (session) {
            session.sendMessage(message);
        }
    }

}
