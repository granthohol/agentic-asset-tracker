package com.assettracker.backend.graph;

// Optional location fields, see docs/ONTOLOGY.md for valid combos.
// Boxed types so null ("not set") is distinct from 0.
public record ObjectiveNode(
    String id,
    String name,
    int priority,
    Double centerLatitude,
    Double centerLongitude,
    String targetEntityId,
    Double radiusMeters
) {
}
