package com.assettracker.backend.entity;

import java.util.List;

import com.assettracker.backend.graph.TrackNode;
import com.assettracker.backend.graph.WaypointNode;
import com.assettracker.backend.graph.ZoneNode;

/** Full map state for REST and the WS connect snapshot. */
public record EntitySnapshot(
    List<TrackNode> tracks,
    List<WaypointNode> waypoints,
    List<ZoneNode> zones
) {
}
