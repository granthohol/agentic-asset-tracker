package com.assettracker.backend.agent.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;

/**
 * Reads RESTRICTED CIRCLE zones from the graph as {@link ZoneRouter} obstacles. Read-only.
 * Shared by the plan_route tool and by server-side swarm route expansion so both avoid the
 * same zones the same way.
 */
@Component
public class RestrictedZoneObstacles {

    private final GraphService graph;

    public RestrictedZoneObstacles(GraphService graph) {
        this.graph = graph;
    }

    /** Every RESTRICTED CIRCLE zone on the map. POLYGON and PATROL zones are not obstacles. */
    public List<CircleObstacle> all() {
        List<CircleObstacle> out = new ArrayList<>();
        for (ZoneNode z : graph.listZones()) {
            toCircleObstacle(z).ifPresent(out::add);
        }
        return out;
    }

    /** Same as {@link #all()} but restricted to the given zone ids. Throws on an unknown id. */
    public List<CircleObstacle> byIds(List<String> zoneIds) {
        List<CircleObstacle> out = new ArrayList<>();
        for (String id : zoneIds) {
            ZoneNode z = graph.getZoneById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown zone id: " + id));
            toCircleObstacle(z).ifPresent(out::add);
        }
        return out;
    }

    private static Optional<CircleObstacle> toCircleObstacle(ZoneNode z) {
        if (z.type() != ZoneType.RESTRICTED || z.shape() != ZoneShape.CIRCLE) {
            return Optional.empty();
        }
        if (z.centerLatitude() == null || z.centerLongitude() == null || z.radiusMeters() == null) {
            return Optional.empty();
        }
        return Optional.of(new CircleObstacle(z.id(), z.centerLatitude(), z.centerLongitude(), z.radiusMeters()));
    }
}
