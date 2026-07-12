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
 * Phase 4: streams telemetry to clients as binary Protocol Buffers frames (see
 * {@code proto/telemetry.proto}) instead of JSON text. Two frame kinds go out:
 * a {@code SNAPSHOT} on connect and a coalesced {@code BATCH} on each broadcast tick.
 */
@Component
public class TelemetryWebSocket extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();   // thread safe set to store all sessions on this web socket
    private final DroneService droneService;

    // Coalesce per-drone telemetry between broadcast ticks. The Kafka listener marks
    // drones dirty (cheap, in-memory); a scheduled tick serializes all dirty drones into
    // ONE binary frame. This replaces ~20k JSON frames/s/client with ~20 batched frames/s.
    private final Map<String, Drone> dirty = new ConcurrentHashMap<>();

    public TelemetryWebSocket(DroneService droneService) {
        this.droneService = droneService;
    }

    // method invoked the moment the WebSocket TCP handshake completes successfully
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);      // store the active TCP socket reference

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

    /**
     * Fixed-tick batch broadcast. Drains the dirty set and sends one binary {@code BATCH}
     * frame per tick to every open session. No dirty drones => no frame.
     */
    @Scheduled(fixedRateString = "${telemetry.broadcast.tick-ms:50}")
    public void flushBatch() {
        if (dirty.isEmpty() || sessions.isEmpty()) {
            return;
        }

        List<Drone> drones = new ArrayList<>(dirty.values());
        // Remove exactly what we snapshotted so updates arriving mid-flush survive.
        drones.forEach(d -> dirty.remove(d.id(), d));

        byte[] payload = frame(FrameType.BATCH, drones).toByteArray();
        BinaryMessage message = new BinaryMessage(payload);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {    // locks so multiple threads dont try to send message at same time
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
