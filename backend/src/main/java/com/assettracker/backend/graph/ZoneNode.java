package com.assettracker.backend.graph;

// An area on the map, stored as a :Zone node.
// CIRCLE  -> centerLatitude/centerLongitude/radiusMeters set; vertex arrays empty.
// POLYGON -> vertexLats/vertexLngs are parallel arrays of >= 3 vertices; center/radius null.
// Neo4j nodes cannot hold nested maps, so polygon vertices are stored as two parallel
// primitive arrays (same constraint as the drone waypoint props in GraphWriter).
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
