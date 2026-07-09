package com.assettracker.backend.agent.formation;

/**
 * Named geometric layouts the planner can preview via {@code list_formations} /
 * {@code preview_formation}. Geometry is deterministic Java — the LLM only chooses
 * which type and where to center it.
 */
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
