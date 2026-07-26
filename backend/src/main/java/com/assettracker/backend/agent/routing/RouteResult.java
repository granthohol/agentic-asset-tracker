package com.assettracker.backend.agent.routing;

import java.util.List;

/**
 * Ordered destination legs (no start point). Each entry is [lat, lng].
 * {@code direct} is true when the straight segment cleared every obstacle.
 */
public record RouteResult(
    List<double[]> legs,
    List<String> avoidedZoneIds,
    boolean direct
) {}
