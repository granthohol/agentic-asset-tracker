package com.assettracker.backend.agent.formation;

import java.util.List;

/** Result of preview_formation: waypoints the planner turns into setWaypoint actions. */
public record FormationPreview(
    FormationType type,
    double centerLat,
    double centerLng,
    double spacingMeters,
    List<FormationSlot> slots
) {}
