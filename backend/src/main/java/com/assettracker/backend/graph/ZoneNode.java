package com.assettracker.backend.graph;

// An area on the map, stored as a :Zone node.
// CIRCLE  -> center lat/lng + radiusMeters set, vertex arrays empty.
// POLYGON -> vertexLats/vertexLngs are parallel arrays of >= 3 vertices, center/radius null.
// Vertices are two parallel arrays since Neo4j can't nest maps (same as drone waypoints).
public record ZoneNode(
    String id,
    String name,
    ZoneType type,
    ZoneShape shape,
    Double centerLatitude,
    Double centerLongitude,
    Double radiusMeters,
    double[] vertexLats,
    double[] vertexLngs
) {
}
