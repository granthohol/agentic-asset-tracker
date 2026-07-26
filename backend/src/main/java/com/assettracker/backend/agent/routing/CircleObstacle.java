package com.assettracker.backend.agent.routing;

/** A RESTRICTED CIRCLE zone used as a routing obstacle. */
public record CircleObstacle(
    String id,
    double lat,
    double lng,
    double radiusMeters
) {}
