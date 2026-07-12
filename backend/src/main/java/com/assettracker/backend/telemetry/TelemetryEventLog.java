package com.assettracker.backend.telemetry;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Component
public class TelemetryEventLog {

    private final String sqlitePath;

    public TelemetryEventLog(@Value("${telemetry.log.sqlite.path}") String sqlitePath) {    // reads from application.properties for database path
        this.sqlitePath = sqlitePath;
    }

    // @PostConstruct: binds the method to the beans initialization phase
    // Spring container executes method exactly once on startup
    @PostConstruct
    public void initialize() {
        ensureParentDirectoryExists();

        String ddl = """
            CREATE TABLE IF NOT EXISTS telemetry_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                drone_id TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                battery_level INTEGER NOT NULL,
                status TEXT NOT NULL,
                event_time_ms INTEGER NOT NULL,
                seq_num INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL
            )
            """;
        
        try (Connection connection = openConnection();
            Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize telemetry event log", e);
        }
    }

    public void append(TelemetryEvent event) {
        String sql = """
                INSERT INTO telemetry_events (
                    drone_id,
                    latitude,
                    longitude,
                    battery_level,
                    status,
                    event_time_ms,
                    seq_num,
                    received_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, event.droneId());
                statement.setDouble(2, event.latitude());
                statement.setDouble(3, event.longitude());
                statement.setInt(4, event.batteryLevel());
                statement.setString(5, event.status());
                statement.setLong(6, event.time());
                statement.setInt(7, event.seqNum());
                statement.setLong(8, System.currentTimeMillis());

                statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append telemetry event", e);
        }

    }

    /**
     * Phase 4: append a whole batch of events in one connection + one transaction using
     * JDBC batching. Opening a fresh connection and auto-committing per event (as
     * {@link #append}) collapses under 1000-drone load; the persistence buffer drains its
     * queue here every flush tick instead.
     */
    public void appendBatch(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO telemetry_events (
                    drone_id,
                    latitude,
                    longitude,
                    battery_level,
                    status,
                    event_time_ms,
                    seq_num,
                    received_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        long receivedAt = System.currentTimeMillis();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (TelemetryEvent event : events) {
                    statement.setString(1, event.droneId());
                    statement.setDouble(2, event.latitude());
                    statement.setDouble(3, event.longitude());
                    statement.setInt(4, event.batteryLevel());
                    statement.setString(5, event.status());
                    statement.setLong(6, event.time());
                    statement.setInt(7, event.seqNum());
                    statement.setLong(8, receivedAt);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append telemetry event batch", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
    }

    private void ensureParentDirectoryExists() {
        Path databasePath = Path.of(sqlitePath);
        Path parent = databasePath.getParent();

        if (parent == null) {
            return;
        }

        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create telemetry log directory: " + parent, e); 
        }
    }
    
}
