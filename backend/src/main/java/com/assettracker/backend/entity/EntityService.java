package com.assettracker.backend.entity;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.TrackNode;
import com.assettracker.backend.graph.WaypointNode;
import com.assettracker.backend.graph.ZoneNode;

/**
 * One write path for tracks, waypoints, and zones. REST and the plan executor both
 * come through here: Neo4j write, then {@link EntityWebSocket} broadcast.
 * Map annotations, not drone commands, so no Kafka pipeline.
 */
@Service
public class EntityService {

    private final GraphWriter graphWriter;
    private final GraphService graphService;
    private final EntityWebSocket entityWebSocket;

    public EntityService(GraphWriter graphWriter, GraphService graphService, EntityWebSocket entityWebSocket) {
        this.graphWriter = graphWriter;
        this.graphService = graphService;
        this.entityWebSocket = entityWebSocket;
    }

    public TrackNode upsertTrack(TrackNode in) {
        TrackNode node = new TrackNode(
            ensureId(in.id(), "track"),
            in.name(), in.affiliation(), in.domain(), in.latitude(), in.longitude());
        graphWriter.upsertTrack(node);
        entityWebSocket.broadcastUpsert("track", node);
        return node;
    }

    public WaypointNode upsertWaypoint(WaypointNode in) {
        WaypointNode node = new WaypointNode(
            ensureId(in.id(), "waypoint"),
            in.name(), in.latitude(), in.longitude());
        graphWriter.upsertWaypoint(node);
        entityWebSocket.broadcastUpsert("waypoint", node);
        return node;
    }

    public ZoneNode upsertZone(ZoneNode in) {
        ZoneNode node = new ZoneNode(
            ensureId(in.id(), "zone"),
            in.name(), in.type(), in.shape(),
            in.centerLatitude(), in.centerLongitude(), in.radiusMeters(),
            in.vertexLats(), in.vertexLngs());
        graphWriter.upsertZone(node);
        entityWebSocket.broadcastUpsert("zone", node);
        return node;
    }

    public void deleteTrack(String id) {
        graphWriter.deleteTrack(id);
        entityWebSocket.broadcastDelete("track", id);
    }

    public void deleteWaypoint(String id) {
        graphWriter.deleteWaypoint(id);
        entityWebSocket.broadcastDelete("waypoint", id);
    }

    public void deleteZone(String id) {
        graphWriter.deleteZone(id);
        entityWebSocket.broadcastDelete("zone", id);
    }

    public EntitySnapshot snapshot() {
        return new EntitySnapshot(
            graphService.listTracks(),
            graphService.listWaypoints(),
            graphService.listZones());
    }

    /** Reuse the id on update, generate one on create. */
    private static String ensureId(String id, String prefix) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
