package com.assettracker.backend.agent.formation;

/** One drone slot in a FormationPreview. */
public record FormationSlot(
    String droneId,
    double targetLat,
    double targetLng
) {}
