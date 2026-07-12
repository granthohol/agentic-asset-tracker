package com.assettracker.backend.config;

import com.assettracker.backend.entity.EntityWebSocket;
import com.assettracker.backend.telemetry.TelemetryWebSocket;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TelemetryWebSocket telemetryWebSocket;
    private final EntityWebSocket entityWebSocket;

    public WebSocketConfig(TelemetryWebSocket telemetryWebSocket, EntityWebSocket entityWebSocket) {
        this.telemetryWebSocket = telemetryWebSocket;
        this.entityWebSocket = entityWebSocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetryWebSocket, "/ws/drones")
                .setAllowedOrigins("http://localhost:5173");
        registry.addHandler(entityWebSocket, "/ws/entities")
                .setAllowedOrigins("http://localhost:5173");
    }
}
