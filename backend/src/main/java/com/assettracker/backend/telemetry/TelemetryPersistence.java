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
 * Phase 4: a bounded buffer that moves telemetry persistence (SQLite shadow log + Neo4j
 * projection) off the Kafka listener thread. At 1000 drones x 20 Hz, writing per event
 * synchronously in {@link TelemetryConsumer} stalls the listener and backs up the whole
 * pipeline. Instead the consumer {@link #enqueue}s events and a scheduled tick drains and
 * batch-writes them:
 *
 * <ul>
 *   <li>SQLite: one connection + one transaction per flush (JDBC batch) preserves the full
 *       event stream for replay.</li>
 *   <li>Neo4j: only the latest position per drone matters for the projection, so events are
 *       coalesced to newest-per-drone and upserted in a single UNWIND.</li>
 * </ul>
 *
 * Under sustained overload the bounded queue sheds the oldest events (best-effort dev
 * simulator) rather than growing without bound; drops are counted and logged.
 */
@Component
public class TelemetryPersistence {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPersistence.class);

    /** Cap so a stalled DB cannot let the queue grow unbounded. ~10s of 1000x20Hz. */
    private static final int QUEUE_CAPACITY = 200_000;
    /** Max events pulled per flush so one tick's work stays bounded. */
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

    /** Non-blocking hand-off from the listener thread. Sheds oldest on overload. */
    public void enqueue(TelemetryEvent event) {
        while (!queue.offer(event)) {
            queue.poll();                       // drop the oldest to make room
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

        // SQLite shadow log: keep the full ordered stream.
        try {
            eventLog.appendBatch(batch);
        } catch (Exception e) {
            log.warn("SQLite batch append failed ({} events): {}", batch.size(), e.getMessage());
        }

        // Neo4j projection: only the newest position per drone is meaningful. Iterating in
        // drain order and overwriting yields the latest because per-drone ordering is
        // preserved (keyed partition + single listener thread enqueues in order).
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
