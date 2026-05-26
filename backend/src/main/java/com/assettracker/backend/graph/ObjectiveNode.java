package com.assettracker.backend.graph;

// Optional location fields: see docs/ONTOLOGY.md for which combinations are valid.
// All four optional fields use boxed types so null = "not set" is distinguishable from 0.
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
