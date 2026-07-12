package com.assettracker.backend.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.GraphWriter;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.TrackNode;
import com.assettracker.backend.graph.WaypointNode;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;

class EntityServiceTest {

    private final GraphWriter graphWriter = Mockito.mock(GraphWriter.class);
    private final GraphService graphService = Mockito.mock(GraphService.class);
    private final EntityWebSocket entityWebSocket = Mockito.mock(EntityWebSocket.class);
    private final EntityService service = new EntityService(graphWriter, graphService, entityWebSocket);

    @Test
    void upsertTrack_mintsIdWhenBlank_thenWritesAndBroadcasts() {
        TrackNode in = new TrackNode(null, "Bandit-1", Affiliation.HOSTILE, TrackDomain.AERIAL, 39.05, -77.18);

        TrackNode returned = service.upsertTrack(in);

        assertThat(returned.id()).startsWith("track-").hasSizeGreaterThan("track-".length());

        ArgumentCaptor<TrackNode> written = ArgumentCaptor.forClass(TrackNode.class);
        verify(graphWriter).upsertTrack(written.capture());
        assertThat(written.getValue().id()).isEqualTo(returned.id());
        assertThat(written.getValue().affiliation()).isEqualTo(Affiliation.HOSTILE);

        verify(entityWebSocket).broadcastUpsert(eq("track"), eq(returned));
    }

    @Test
    void upsertWaypoint_keepsProvidedId() {
        WaypointNode in = new WaypointNode("waypoint-abc", "Rally", 39.0, -77.2);

        WaypointNode returned = service.upsertWaypoint(in);

        assertThat(returned.id()).isEqualTo("waypoint-abc");
        verify(graphWriter).upsertWaypoint(returned);
        verify(entityWebSocket).broadcastUpsert("waypoint", returned);
    }

    @Test
    void upsertZone_polygon_writesAndBroadcasts() {
        ZoneNode in = new ZoneNode(
            null, "No-Fly", ZoneType.RESTRICTED, ZoneShape.POLYGON,
            null, null, null,
            new double[] { 39.0, 39.1, 39.0 }, new double[] { -77.2, -77.1, -77.0 });

        ZoneNode returned = service.upsertZone(in);

        assertThat(returned.id()).startsWith("zone-");
        assertThat(returned.vertexLats()).containsExactly(39.0, 39.1, 39.0);
        verify(graphWriter).upsertZone(returned);
        verify(entityWebSocket).broadcastUpsert("zone", returned);
    }

    @Test
    void deleteZone_writesAndBroadcasts() {
        service.deleteZone("zone-1");

        verify(graphWriter).deleteZone("zone-1");
        verify(entityWebSocket).broadcastDelete("zone", "zone-1");
    }

    @Test
    void snapshot_readsFromGraphService() {
        TrackNode track = new TrackNode("track-1", "Bandit", Affiliation.HOSTILE, TrackDomain.GROUND, 1, 2);
        WaypointNode waypoint = new WaypointNode("waypoint-1", "WP", 3, 4);
        ZoneNode zone = new ZoneNode("zone-1", "Z", ZoneType.PATROL, ZoneShape.CIRCLE, 1.0, 2.0, 300.0,
            new double[0], new double[0]);
        when(graphService.listTracks()).thenReturn(List.of(track));
        when(graphService.listWaypoints()).thenReturn(List.of(waypoint));
        when(graphService.listZones()).thenReturn(List.of(zone));

        EntitySnapshot snapshot = service.snapshot();

        assertThat(snapshot.tracks()).containsExactly(track);
        assertThat(snapshot.waypoints()).containsExactly(waypoint);
        assertThat(snapshot.zones()).containsExactly(zone);
    }
}
