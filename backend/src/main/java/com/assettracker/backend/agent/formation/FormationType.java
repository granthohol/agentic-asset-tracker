package com.assettracker.backend.agent.formation;

/** Formation layouts (RING, WEDGE, LINE). Geometry is Java; the LLM picks type and center. */
public enum FormationType {
    RING,
    WEDGE,
    LINE;

    public static FormationType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing formation type");
        }
        try {
            return FormationType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Unknown formation type: " + raw + " (expected RING, WEDGE, or LINE)");
        }
    }
}
