package com.assettracker.backend.agent.formation;

/** Catalog entry returned by {@code list_formations}. */
public record FormationSpec(
    FormationType type,
    String name,
    String description,
    int minDrones,
    int maxDrones,
    double defaultSpacingMeters
) {}
