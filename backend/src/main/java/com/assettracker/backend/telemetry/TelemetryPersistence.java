package com.assettracker.backend.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.assettracker.backend.graph.DroneNode;
import com.assettracker.backend.graph.GraphMapper;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.model.Drone;

/**
 * Buffers telemetry for async persistence (SQLite shadow log + Neo4j).
 * At 1000 drones x 20 Hz, writing per event in the listener stalls the pipeline.
 * Flush tick: batch SQLite append, coalesce to newest-per-drone for Neo4j.
 * Bounded queue drops oldest events under overload rather than growing forever.
 */
@Component
public class TelemetryPersistence {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPersistence.class);

    /** ~10s buffer at 1000x20Hz. Stalled DB can't grow the queue forever. */
    private static final int QUEUE_CAPACITY = 200_000;
    /** Max events per flush so one tick stays bounded. */
    private static final int MAX_DRAIN = 100_000;

    private final BlockingQueue<TelemetryEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedSinceLastLog = new AtomicLong();

    private final TelemetryEventLog eventLog;
    private final TelemetryEventMapper eventMapper;
    private final GraphWriter graphWriter;
    private final GraphMapper graphMapper;

    public TelemetryPersistence(
        TelemetryEventLog eventLog,
        TelemetryEventMapper eventMapper,
        GraphWriter graphWriter,
        GraphMapper graphMapper
    ) {
        this.eventLog = eventLog;
        this.eventMapper = eventMapper;
        this.graphWriter = graphWriter;
        this.graphMapper = graphMapper;
    }

    /** Non-blocking handoff from listener. Drops oldest on overload. */
    public void enqueue(TelemetryEvent event) {
        while (!queue.offer(event)) {
            queue.poll();                       // make room by dropping oldest
            droppedSinceLastLog.incrementAndGet();
        }
    }

    @Scheduled(fixedDelayString = "${telemetry.persistence.flush-ms:500}")
    public void flush() {
        List<TelemetryEvent> batch = new ArrayList<>(Math.min(MAX_DRAIN, QUEUE_CAPACITY));
        queue.drainTo(batch, MAX_DRAIN);
        if (batch.isEmpty()) {
            long dropped = droppedSinceLastLog.getAndSet(0);
            if (dropped > 0) {
                log.warn("Telemetry persistence dropped {} event(s) under backpressure", dropped);
            }
            return;
        }

        // SQLite: keep full ordered stream.
        try {
            eventLog.appendBatch(batch);
        } catch (Exception e) {
            log.warn("SQLite batch append failed ({} events): {}", batch.size(), e.getMessage());
        }

        // Neo4j: only newest position per drone matters. Drain order preserves per-drone ordering.
        Map<String, DroneNode> newestByDrone = new LinkedHashMap<>();
        for (TelemetryEvent event : batch) {
            Drone drone = eventMapper.mapToDrone(event);
            newestByDrone.put(drone.id(), graphMapper.toDroneNode(drone));
        }
        try {
            graphWriter.upsertDronesBatch(newestByDrone.values());
        } catch (Exception e) {
            log.warn("Neo4j batch upsert failed ({} drones): {}", newestByDrone.size(), e.getMessage());
        }

        long dropped = droppedSinceLastLog.getAndSet(0);
        if (dropped > 0) {
            log.warn("Telemetry persistence dropped {} event(s) under backpressure", dropped);
        }
    }
}
