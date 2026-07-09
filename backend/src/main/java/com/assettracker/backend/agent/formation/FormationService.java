package com.assettracker.backend.agent.formation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Deterministic formation geometry for planner tools. Read-only: never mutates the graph
 * or publishes commands. The LLM (or stub) calls {@link #preview} then emits N×
 * {@code setWaypoint} actions from the returned slots.
 *
 * <p>Local frame: {@code +dLat} is forward (toward the facing point), {@code +dLng} is right.
 * Optional facing lat/lng rotates that frame so WEDGE/LINE point at the target.
 */
@Service
public class FormationService {

    /** ~200 m at mid-latitudes — matches the prior stub ring radius. */
    public static final double DEFAULT_SPACING_METERS = 200.0;
    /** Default distance short of the AOI for form-up (~2 km). */
    public static final double DEFAULT_STANDOFF_METERS = 2000.0;
    /** Rough meters-per-degree at mid-latitudes (good enough for local C2 demos). */
    private static final double METERS_PER_DEG = 111_320.0;
    /** If leader is closer than this to the AOI, fall back to "south of AOI". */
    private static final double MIN_LEADER_AOI_DEG = 0.002;
    /** Facing vector shorter than this → keep default north orientation. */
    private static final double MIN_FACING_DEG = 1e-9;

    /** Soft ceiling so a huge fleet cannot explode a single preview; prompt can still take "all". */
    public static final int MAX_FORMATION_DRONES = 50;

    private static final List<FormationSpec> CATALOG = List.of(
        new FormationSpec(
            FormationType.RING,
            "Ring",
            "Evenly spaced circle around the center. Rotation has no visual effect.",
            1, MAX_FORMATION_DRONES, DEFAULT_SPACING_METERS
        ),
        new FormationSpec(
            FormationType.WEDGE,
            "Wedge",
            "V with apex at the center, opening away from the facing point (points at the target).",
            1, MAX_FORMATION_DRONES, DEFAULT_SPACING_METERS
        ),
        new FormationSpec(
            FormationType.LINE,
            "Line",
            "Picket line perpendicular to the facing direction (broadside toward the target).",
            1, MAX_FORMATION_DRONES, DEFAULT_SPACING_METERS
        )
    );

    public List<FormationSpec> listSpecs() {
        return CATALOG;
    }

    /**
     * Point on the line from leader → AOI, {@code standoffMeters} short of the AOI
     * (toward the fleet). If the leader is already on top of the AOI, place the
     * form-up center due south of the AOI by the same distance.
     *
     * @return {@code double[]{lat, lng}}
     */
    public static double[] standoffCenter(
        double aoiLat,
        double aoiLng,
        double leaderLat,
        double leaderLng,
        double standoffMeters
    ) {
        double standoffDeg = standoffMeters / METERS_PER_DEG;
        double dLat = aoiLat - leaderLat;
        double dLng = aoiLng - leaderLng;
        double dist = Math.hypot(dLat, dLng);
        if (dist < MIN_LEADER_AOI_DEG) {
            return new double[] { aoiLat - standoffDeg, aoiLng };
        }
        double scale = Math.max(0.0, (dist - standoffDeg) / dist);
        return new double[] {
            leaderLat + dLat * scale,
            leaderLng + dLng * scale
        };
    }

    /** Preview with default (north) orientation. */
    public FormationPreview preview(
        FormationType type,
        double centerLat,
        double centerLng,
        List<String> droneIds,
        Double spacingMeters
    ) {
        return preview(type, centerLat, centerLng, droneIds, spacingMeters, null, null);
    }

    /**
     * Assign {@code droneIds} (in order) to formation slots around the center.
     * When {@code facingLatitude}/{@code facingLongitude} are set, rotates the formation
     * so its forward axis points from the center toward that point.
     */
    public FormationPreview preview(
        FormationType type,
        double centerLat,
        double centerLng,
        List<String> droneIds,
        Double spacingMeters,
        Double facingLatitude,
        Double facingLongitude
    ) {
        if (droneIds == null || droneIds.isEmpty()) {
            throw new IllegalArgumentException("preview_formation requires at least one droneId");
        }
        FormationSpec spec = specFor(type);
        double spacing = spacingMeters != null ? spacingMeters : spec.defaultSpacingMeters();
        if (spacing <= 0) {
            throw new IllegalArgumentException("spacingMeters must be positive");
        }

        int count = Math.min(droneIds.size(), spec.maxDrones());
        List<String> selected = droneIds.subList(0, count);
        List<double[]> offsets = offsetsFor(type, count, spacing);
        offsets = rotateTowardFacing(offsets, centerLat, centerLng, facingLatitude, facingLongitude);

        List<FormationSlot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double[] off = offsets.get(i);
            slots.add(new FormationSlot(
                i,
                selected.get(i),
                centerLat + off[0],
                centerLng + off[1]
            ));
        }
        return new FormationPreview(type, centerLat, centerLng, spacing, slots);
    }

    private FormationSpec specFor(FormationType type) {
        return CATALOG.stream()
            .filter(s -> s.type() == type)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No catalog entry for " + type));
    }

    /**
     * Rotate local (forward=north, right=east) offsets so +forward points at the facing point.
     * RING is rotationally symmetric; still rotated for slot-index consistency.
     */
    static List<double[]> rotateTowardFacing(
        List<double[]> offsets,
        double centerLat,
        double centerLng,
        Double facingLatitude,
        Double facingLongitude
    ) {
        if (facingLatitude == null || facingLongitude == null) {
            return offsets;
        }
        double fLat = facingLatitude - centerLat;
        double fLng = facingLongitude - centerLng;
        if (Math.hypot(fLat, fLng) < MIN_FACING_DEG) {
            return offsets;
        }
        // Heading from north toward east (radians).
        double heading = Math.atan2(fLng, fLat);
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);

        List<double[]> rotated = new ArrayList<>(offsets.size());
        for (double[] off : offsets) {
            double north = off[0];
            double east = off[1];
            rotated.add(new double[] {
                north * cos - east * sin,
                north * sin + east * cos
            });
        }
        return rotated;
    }

    /** Returns (dLat, dLng) offsets in the local frame: +dLat forward, +dLng right. */
    static List<double[]> offsetsFor(FormationType type, int count, double spacingMeters) {
        double r = spacingMeters / METERS_PER_DEG;
        return switch (type) {
            case RING -> ringOffsets(count, r);
            case LINE -> lineOffsets(count, r);
            case WEDGE -> wedgeOffsets(count, r);
        };
    }

    private static List<double[]> ringOffsets(int count, double radiusDeg) {
        List<double[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            out.add(new double[] {
                radiusDeg * Math.cos(angle),
                radiusDeg * Math.sin(angle)
            });
        }
        return out;
    }

    /**
     * Line along the right axis (east when facing north) — broadside toward the target
     * after rotation.
     */
    private static List<double[]> lineOffsets(int count, double spacingDeg) {
        List<double[]> out = new ArrayList<>(count);
        double start = -((count - 1) / 2.0) * spacingDeg;
        for (int i = 0; i < count; i++) {
            out.add(new double[] { 0.0, start + i * spacingDeg });
        }
        return out;
    }

    /**
     * V with apex at center; arms trail behind (−forward). After rotation, apex leads
     * toward the facing point.
     */
    private static List<double[]> wedgeOffsets(int count, double spacingDeg) {
        List<double[]> out = new ArrayList<>(count);
        if (count == 1) {
            out.add(new double[] { 0.0, 0.0 });
            return out;
        }
        out.add(new double[] { 0.0, 0.0 }); // apex
        int arm = 1;
        for (int i = 1; i < count; i++) {
            boolean left = (i % 2 == 1);
            double dForward = -arm * spacingDeg * 0.85;
            double dRight = (left ? -1 : 1) * arm * spacingDeg * 0.65;
            out.add(new double[] { dForward, dRight });
            if (!left) {
                arm++;
            }
        }
        return out;
    }
}
