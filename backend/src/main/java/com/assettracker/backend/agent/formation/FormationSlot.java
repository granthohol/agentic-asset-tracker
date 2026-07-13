package com.assettracker.backend.agent.formation;

/** One drone assignment in a {@link FormationPreview}. */
public record FormationSlot(
    String droneId,
    double targetLat,
    double targetLng
) {}
