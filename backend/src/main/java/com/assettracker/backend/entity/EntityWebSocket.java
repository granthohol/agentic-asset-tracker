package com.assettracker.backend.entity;

import com.assettracker.backend.graph.GraphService;
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

/**
 * Live feed for persistent map entities (tracks / waypoints / zones), mirroring
 * {@link com.assettracker.backend.telemetry.TelemetryWebSocket}. On connect a client
 * receives a full {@code snapshot}; thereafter {@link EntityService} pushes
 * {@code entityUpsert} / {@code entityDelete} frames as entities change (from either
 * manual REST edits or, later, the plan executor).
 */
@Component
public class EntityWebSocket extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public EntityWebSocket(GraphService graphService, ObjectMapper objectMapper) {
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        Map<String, Object> snapshot = Map.of(
            "type", "snapshot",
            "tracks", graphService.listTracks(),
            "waypoints", graphService.listWaypoints(),
            "zones", graphService.listZones()
        );
        sendJson(session, snapshot);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** kind = "track" | "waypoint" | "zone"; entity = the persisted node record. */
    public void broadcastUpsert(String kind, Object entity) {
        broadcast(Map.of("type", "entityUpsert", "kind", kind, "entity", entity));
    }

    public void broadcastDelete(String kind, String id) {
        broadcast(Map.of("type", "entityDelete", "kind", kind, "id", id));
    }

    private void broadcast(Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize entity event", e);
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
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
