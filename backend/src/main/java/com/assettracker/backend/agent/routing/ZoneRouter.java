package com.assettracker.backend.agent.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Demo-safe path planner around RESTRICTED CIRCLE zones.
 * Straight line when clear; otherwise left/right external-tangent detours.
 * Distances use a local meters frame (lat + lng*cos(lat)) so E–W circles are not undersized.
 */
public final class ZoneRouter {

    /** Extra clearance outside each circle (~200 m). */
    public static final double BUFFER_METERS = 200.0;
    private static final double METERS_PER_DEG = 111_320.0;
    private static final int MAX_ITERATIONS = 12;
    /** Rim samples when the tangent candidates are blocked. */
    private static final int RIM_SAMPLES = 36;
    /**
     * Detour-circle radius multipliers to retry at when nothing on the buffer boundary works.
     * When from/to are both close to the circle and roughly opposite each other, every point
     * sitting exactly on the buffer boundary is itself too close to the circle to see both ends
     * without re-crossing it — swinging the candidate ring further out gives it room to arc
     * around instead.
     */
    private static final double[] DETOUR_RADIUS_SCALES = { 1.0, 1.5, 2.5, 4.0 };

    private ZoneRouter() {}

    /**
     * Plan a route from {@code from} to {@code to} avoiding the given circles.
     * Throws if start/end is inside a buffered circle or no path is found.
     */
    public static RouteResult plan(
        double fromLat,
        double fromLng,
        double toLat,
        double toLng,
        List<CircleObstacle> obstacles
    ) {
        List<CircleObstacle> circles = obstacles == null ? List.of() : obstacles;
        for (CircleObstacle c : circles) {
            if (pointInBufferedCircle(fromLat, fromLng, c)) {
                throw new IllegalArgumentException(
                    "start point is inside restricted zone '" + c.id() + "'");
            }
            if (pointInBufferedCircle(toLat, toLng, c)) {
                throw new IllegalArgumentException(
                    "destination is inside restricted zone '" + c.id() + "'");
            }
        }

        List<double[]> path = new ArrayList<>();
        path.add(new double[] { fromLat, fromLng });
        Set<String> avoided = new LinkedHashSet<>();
        double curLat = fromLat;
        double curLng = fromLng;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            CircleObstacle blocker = firstBlocking(curLat, curLng, toLat, toLng, circles);
            if (blocker == null) {
                path.add(new double[] { toLat, toLng });
                List<double[]> legs = dropStart(path);
                boolean direct = legs.size() == 1 && avoided.isEmpty();
                return new RouteResult(legs, List.copyOf(avoided), direct);
            }
            avoided.add(blocker.id());
            double[] detour = pickDetour(curLat, curLng, toLat, toLng, blocker, circles);
            if (metersBetween(curLat, curLng, detour[0], detour[1]) < 1.0) {
                throw new IllegalArgumentException(
                    "could not find a path around restricted zone '" + blocker.id() + "'");
            }
            path.add(detour);
            curLat = detour[0];
            curLng = detour[1];
        }
        throw new IllegalArgumentException(
            "could not find a path around restricted zones within " + MAX_ITERATIONS + " detours");
    }

    private static List<double[]> dropStart(List<double[]> path) {
        List<double[]> legs = new ArrayList<>(path.size() - 1);
        for (int i = 1; i < path.size(); i++) {
            legs.add(path.get(i));
        }
        return legs;
    }

    static boolean pointInBufferedCircle(double lat, double lng, CircleObstacle c) {
        double r = c.radiusMeters() + BUFFER_METERS;
        return metersBetween(lat, lng, c.lat(), c.lng()) < r - 1e-6;
    }

    /** First obstacle whose buffered disk intersects the open segment from→to. */
    static CircleObstacle firstBlocking(
        double fromLat, double fromLng,
        double toLat, double toLng,
        List<CircleObstacle> circles
    ) {
        CircleObstacle best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (CircleObstacle c : circles) {
            double t = segmentCircleHitT(fromLat, fromLng, toLat, toLng, c);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = c;
            }
        }
        return best;
    }

    /**
     * Parametric t in (0,1) where the segment first enters the buffered circle, or -1 if none.
     * Work in local meters around the circle center so lng is scaled by cos(lat).
     */
    static double segmentCircleHitT(
        double fromLat, double fromLng,
        double toLat, double toLng,
        CircleObstacle c
    ) {
        double r = c.radiusMeters() + BUFFER_METERS;
        double[] a = toLocalMeters(fromLat, fromLng, c.lat(), c.lng());
        double[] b = toLocalMeters(toLat, toLng, c.lat(), c.lng());
        double abx = b[0] - a[0];
        double aby = b[1] - a[1];
        double abLen2 = abx * abx + aby * aby;
        if (abLen2 < 1e-6) {
            return -1;
        }
        // Closest point on segment to origin (circle center in local frame).
        double tClosest = (-a[0] * abx - a[1] * aby) / abLen2;
        tClosest = Math.max(0.0, Math.min(1.0, tClosest));
        double cx = a[0] + tClosest * abx;
        double cy = a[1] + tClosest * aby;
        if (Math.hypot(cx, cy) >= r) {
            return -1;
        }
        double lo = 0.0;
        double hi = 1.0;
        boolean hit = false;
        for (int i = 0; i < 48; i++) {
            double mid = (lo + hi) / 2.0;
            double mx = a[0] + mid * abx;
            double my = a[1] + mid * aby;
            if (Math.hypot(mx, my) < r) {
                hi = mid;
                hit = true;
            } else {
                lo = mid;
            }
        }
        if (!hit || hi <= 1e-9) {
            return hit ? Math.max(hi, 1e-6) : -1;
        }
        return hi;
    }

    /**
     * External-tangent rim points left/right of the circle, computed from BOTH endpoints, plus
     * dense rim samples; prefer a clear from→detour→destination path. Tangent points from
     * {@code from} guarantee from→p is clear by construction; tangent points from {@code to}
     * guarantee p→destination is clear the same way — combining both catches tight geometries
     * (start and destination both close to the zone) where neither endpoint's tangent alone also
     * has clear sight of the other end, and 12 rim samples at 30° apart could miss a narrow clear
     * window entirely. If nothing on the buffer boundary works — from/to both close to the circle
     * and roughly opposite each other, so every boundary point is itself too close to see past
     * the circle to the far end — retry with the candidate ring swung progressively further out
     * ({@link #DETOUR_RADIUS_SCALES}) before giving up.
     */
    static double[] pickDetour(
        double fromLat, double fromLng,
        double toLat, double toLng,
        CircleObstacle blocker,
        List<CircleObstacle> all
    ) {
        double baseR = blocker.radiusMeters() + BUFFER_METERS;
        for (double scale : DETOUR_RADIUS_SCALES) {
            double[] found = pickDetourAtRadius(fromLat, fromLng, toLat, toLng, blocker, all, baseR * scale);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalArgumentException(
            "could not find a clear detour around restricted zone '" + blocker.id() + "'");
    }

    /** One radius' worth of candidates; null if none clears both legs of the current blocker. */
    private static double[] pickDetourAtRadius(
        double fromLat, double fromLng,
        double toLat, double toLng,
        CircleObstacle blocker,
        List<CircleObstacle> all,
        double r
    ) {
        List<double[]> candidates = new ArrayList<>();
        candidates.add(tangentPoint(fromLat, fromLng, blocker, r, true));
        candidates.add(tangentPoint(fromLat, fromLng, blocker, r, false));
        candidates.add(tangentPoint(toLat, toLng, blocker, r, true));
        candidates.add(tangentPoint(toLat, toLng, blocker, r, false));
        for (int i = 0; i < RIM_SAMPLES; i++) {
            candidates.add(rimPoint(blocker, r, (2.0 * Math.PI * i) / RIM_SAMPLES));
        }

        double[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;

        for (double[] p : candidates) {
            if (pointInBufferedCircle(p[0], p[1], blocker)) {
                continue;
            }
            // Need progress away from current position.
            if (metersBetween(fromLat, fromLng, p[0], p[1]) < 1.0) {
                continue;
            }
            // from→p and p→destination must both clear the current blocker, or we oscillate
            // on an entry tangent that still cannot see the destination.
            if (firstBlocking(fromLat, fromLng, p[0], p[1], List.of(blocker)) != null) {
                continue;
            }
            if (firstBlocking(p[0], p[1], toLat, toLng, List.of(blocker)) != null) {
                continue;
            }
            double cost = pathCost(fromLat, fromLng, p, toLat, toLng);
            boolean clearsAll = firstBlocking(fromLat, fromLng, p[0], p[1], all) == null
                && firstBlocking(p[0], p[1], toLat, toLng, all) == null;
            // Prefer globally clear points; still accept blocker-only clear for multi-circle.
            double rank = clearsAll ? cost : cost + 1_000_000.0;
            if (rank < bestCost) {
                bestCost = rank;
                best = p;
            }
        }
        return best;
    }

    /**
     * Contact point of the left/right external tangent from {@code from} to the buffered circle.
     * Local frame origin = circle center; +x north, +y east.
     */
    static double[] tangentPoint(
        double fromLat, double fromLng,
        CircleObstacle c,
        double rMeters,
        boolean left
    ) {
        double[] fromM = toLocalMeters(fromLat, fromLng, c.lat(), c.lng());
        double d = Math.hypot(fromM[0], fromM[1]);
        if (d <= rMeters + 1e-6) {
            double px = d < 1e-6 ? 1.0 : -fromM[1] / d;
            double py = d < 1e-6 ? 0.0 : fromM[0] / d;
            if (!left) {
                px = -px;
                py = -py;
            }
            // Slightly outside so pointInBufferedCircle is false.
            return fromLocalMeters(px * (rMeters + 1.0), py * (rMeters + 1.0), c.lat(), c.lng());
        }
        double ux = fromM[0] / d;
        double uy = fromM[1] / d;
        double px = -uy;
        double py = ux;
        double along = (rMeters * rMeters) / d;
        double side = rMeters * Math.sqrt(Math.max(0.0, d * d - rMeters * rMeters)) / d;
        if (!left) {
            side = -side;
        }
        // Nudge 1 m outward so the waypoint is strictly outside the open disk.
        double tx = ux * along + px * side;
        double ty = uy * along + py * side;
        double tLen = Math.hypot(tx, ty);
        if (tLen > 1e-9) {
            tx = tx / tLen * (rMeters + 1.0);
            ty = ty / tLen * (rMeters + 1.0);
        }
        return fromLocalMeters(tx, ty, c.lat(), c.lng());
    }

    private static double[] rimPoint(CircleObstacle c, double rMeters, double angleRad) {
        // Local +x = north (lat), +y = east (lng*cos). Sit 1 m outside the open disk.
        double north = Math.cos(angleRad) * (rMeters + 1.0);
        double east = Math.sin(angleRad) * (rMeters + 1.0);
        return fromLocalMeters(north, east, c.lat(), c.lng());
    }

    private static double pathCost(double fromLat, double fromLng, double[] mid, double toLat, double toLng) {
        return metersBetween(fromLat, fromLng, mid[0], mid[1])
            + metersBetween(mid[0], mid[1], toLat, toLng);
    }

    /** Local meters: +x north, +y east, origin at (originLat, originLng). */
    static double[] toLocalMeters(double lat, double lng, double originLat, double originLng) {
        double cos = Math.cos(Math.toRadians(originLat));
        double north = (lat - originLat) * METERS_PER_DEG;
        double east = (lng - originLng) * METERS_PER_DEG * cos;
        return new double[] { north, east };
    }

    static double[] fromLocalMeters(double northM, double eastM, double originLat, double originLng) {
        double cos = Math.cos(Math.toRadians(originLat));
        if (Math.abs(cos) < 1e-6) {
            cos = 1e-6;
        }
        return new double[] {
            originLat + northM / METERS_PER_DEG,
            originLng + eastM / (METERS_PER_DEG * cos)
        };
    }

    static double metersBetween(double lat1, double lng1, double lat2, double lng2) {
        double midLat = (lat1 + lat2) / 2.0;
        double cos = Math.cos(Math.toRadians(midLat));
        double north = (lat2 - lat1) * METERS_PER_DEG;
        double east = (lng2 - lng1) * METERS_PER_DEG * cos;
        return Math.hypot(north, east);
    }
}
