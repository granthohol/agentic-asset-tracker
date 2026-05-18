package com.assettracker.backend.telemetry;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TelemetryWebSocket extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();   // thread safe set to store all sessions on this web socket
    private final DroneService droneService;
    private final ObjectMapper objectMapper;    // library engine for serializing memory objects into JSON strings

    public TelemetryWebSocket(DroneService droneService, ObjectMapper objectMapper) {
        this.droneService = droneService;
        this.objectMapper = objectMapper;
    }

    // method invoked the moment the WebSocket TCP handshake completes successfully
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);      // store the active TCP socket reference

        Map<String, Object> snapshotMessage = Map.of(
            "type", "snapshot",
            "drones", droneService.getAllDrones()
        );

        sendJson(session, snapshotMessage);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }


    public void broadcastDroneUpdate(Drone drone) {
        Map<String, Object> updateMessage = Map.of(
            "type", "droneUpdate",
            "drone", drone
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(updateMessage);  // drone object converted into a JSON string via Jackson
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize drone update", e);
        }

        TextMessage message = new TextMessage(json);

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

    private void sendJson(WebSocketSession session, Object payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    
}
