package com.assettracker.backend.agent.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.formation.FormationPreview;
import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationSlot;
import com.assettracker.backend.agent.formation.FormationType;
import com.assettracker.backend.agent.routing.CircleObstacle;
import com.assettracker.backend.agent.routing.RestrictedZoneObstacles;
import com.assettracker.backend.agent.routing.RouteResult;
import com.assettracker.backend.agent.routing.ZoneRouter;

/**
 * Expands applyFormation / applyFormationRoute macros into setWaypoint actions the rest of the
 * system already knows. Runs in the orchestrator (before the plan leaves the server) and again
 * in PlanExecutor (defensive).
 */
@Component
public class PlanExpander {

    private static final String FORM_UP_MISSION = "FORM_UP";
    private static final String HOLD_MISSION = "HOLD";
    private static final String DEFAULT_ADVANCE_MISSION = "ADVANCE";

    private final FormationService formations;
    private final RestrictedZoneObstacles restrictedZones;

    public PlanExpander(FormationService formations, RestrictedZoneObstacles restrictedZones) {
        this.formations = formations;
        this.restrictedZones = restrictedZones;
    }

    /** Flatten every formation macro to setWaypoints. No-op if there are none. */
    public ExecutionPlan expand(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new ExecutionPlan(plan.planId(), plan.rationale(), expandActions(plan.actions()));
    }

    /** Replace each formation macro with per-drone setWaypoints. Other actions pass through in order. */
    public List<PlanAction> expandActions(List<PlanAction> actions) {
        if (actions == null) {
            return List.of();
        }
        List<PlanAction> out = new ArrayList<>(actions.size());
        for (PlanAction action : actions) {
            if (action instanceof PlanAction.ApplyFormation af) {
                out.addAll(formationWave(af.formationType(), af.centerLat(), af.centerLng(),
                    af.droneIds(), af.spacingMeters(), af.facingLat(), af.facingLng(), af.missionType()));
            } else if (action instanceof PlanAction.ApplyFormationRoute afr) {
                out.addAll(expandFormationRoute(afr));
            } else {
                out.add(action);
            }
        }
        return prioritizeFormUpWaves(out);
    }

    /**
     * Two-phase swarm macro: form up, route around every RESTRICTED CIRCLE zone between formUp
     * and dest (server-side, deterministic — never the model's job), then advance. Every wave —
     * FORM_UP, each HOLD detour, and the final ADVANCE — carries the full droneIds list, so the
     * swarm always moves together; there is no way for a subset of the drones to be left behind.
     *
     * ZoneRouter only keeps the formation CENTER clear of each zone by its own buffer. A slot on
     * the edge of the formation (or the straight line an off-center drone flies between two
     * waypoints) can be much farther from center than that buffer — e.g. a 9-drone WEDGE spans
     * ~850 m at default spacing, well past the router's 200 m margin — so it would clear the
     * center's route while still clipping the zone itself. Inflating every obstacle by the
     * formation's footprint radius before routing fixes both: since a linear interpolation
     * between two points, each within radius R of their own reference point, is always within R
     * of the interpolated reference point (convexity), every slot's waypoint AND the straight
     * line it flies between waypoints stays outside the real zone by at least ZoneRouter's own
     * buffer, not just the center's path.
     */
    private List<PlanAction.SetWaypoint> expandFormationRoute(PlanAction.ApplyFormationRoute afr) {
        String advanceMission = afr.missionType() == null || afr.missionType().isBlank()
            ? DEFAULT_ADVANCE_MISSION : afr.missionType();

        List<PlanAction.SetWaypoint> out = new ArrayList<>();
        out.addAll(formationWave(afr.formationType(), afr.formUpLat(), afr.formUpLng(),
            afr.droneIds(), afr.spacingMeters(), afr.destLat(), afr.destLng(), FORM_UP_MISSION));

        double footprintRadiusMeters = formations.footprintRadiusMeters(
            afr.formationType(), afr.droneIds().size(), afr.spacingMeters());
        List<CircleObstacle> obstacles = inflate(restrictedZones.all(), footprintRadiusMeters);
        RouteResult route = ZoneRouter.plan(
            afr.formUpLat(), afr.formUpLng(), afr.destLat(), afr.destLng(), obstacles);
        List<double[]> legs = route.legs();
        // Every leg but the last is an intermediate HOLD; the last leg is the destination itself.
        // PlanExecutor (and the frontend) rediscover wave boundaries by grouping CONTIGUOUS
        // setWaypoints with an identical mission_type string. Two circles (or one circle needing
        // two detour points) produce two separate HOLD legs — if both were labeled bare "HOLD",
        // that grouping would merge them into one wave, publish leg 2's coordinates as a
        // same-wave overwrite of leg 1's for every drone (so the swarm skips leg 1 entirely),
        // and then wait forever for arrival at a leg-1 target no drone will ever occupy, stalling
        // the plan until PlanExecutor's 90s wave timeout. Numbering each HOLD leg keeps every
        // wave's mission_type unique so it can never collide with a neighboring HOLD wave.
        int holdCount = legs.size() - 1;
        for (int i = 0; i < holdCount; i++) {
            double[] leg = legs.get(i);
            String holdMission = holdCount > 1 ? HOLD_MISSION + "_" + (i + 1) : HOLD_MISSION;
            out.addAll(formationWave(afr.formationType(), leg[0], leg[1],
                afr.droneIds(), afr.spacingMeters(), afr.destLat(), afr.destLng(), holdMission));
        }
        out.addAll(formationWave(afr.formationType(), afr.destLat(), afr.destLng(),
            afr.droneIds(), afr.spacingMeters(), afr.destLat(), afr.destLng(), advanceMission));
        return out;
    }

    private static List<CircleObstacle> inflate(List<CircleObstacle> obstacles, double extraRadiusMeters) {
        if (extraRadiusMeters <= 0) {
            return obstacles;
        }
        List<CircleObstacle> out = new ArrayList<>(obstacles.size());
        for (CircleObstacle o : obstacles) {
            out.add(new CircleObstacle(o.id(), o.lat(), o.lng(), o.radiusMeters() + extraRadiusMeters));
        }
        return out;
    }

    private List<PlanAction.SetWaypoint> formationWave(
        FormationType type,
        double centerLat,
        double centerLng,
        List<String> droneIds,
        Double spacingMeters,
        Double facingLat,
        Double facingLng,
        String missionType
    ) {
        FormationPreview preview = formations.preview(
            type, centerLat, centerLng, droneIds, spacingMeters, facingLat, facingLng);
        List<PlanAction.SetWaypoint> out = new ArrayList<>(preview.slots().size());
        for (FormationSlot slot : preview.slots()) {
            out.add(new PlanAction.SetWaypoint(slot.droneId(), slot.targetLat(), slot.targetLng(), missionType));
        }
        return out;
    }

    /**
     * Among setWaypoint waves, run FORM_UP/HOLD before ADVANCE/other. Non-motion and setRoute
     * keep their relative slots so a mis-ordered LLM plan still forms up first on the map and wire.
     */
    static List<PlanAction> prioritizeFormUpWaves(List<PlanAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return actions == null ? List.of() : actions;
        }
        List<Object> segments = new ArrayList<>();
        int i = 0;
        while (i < actions.size()) {
            PlanAction a = actions.get(i);
            if (a instanceof PlanAction.SetWaypoint sw) {
                String waveType = normalizeMission(sw.missionType());
                List<PlanAction> wave = new ArrayList<>();
                while (i < actions.size() && actions.get(i) instanceof PlanAction.SetWaypoint next
                    && Objects.equals(waveType, normalizeMission(next.missionType()))) {
                    wave.add(next);
                    i++;
                }
                segments.add(wave);
            } else {
                segments.add(a);
                i++;
            }
        }

        Deque<List<PlanAction>> formUpQ = new ArrayDeque<>();
        Deque<List<PlanAction>> otherQ = new ArrayDeque<>();
        for (Object seg : segments) {
            if (seg instanceof List<?> wave) {
                @SuppressWarnings("unchecked")
                List<PlanAction> wpWave = (List<PlanAction>) wave;
                if (isFormUp(((PlanAction.SetWaypoint) wpWave.get(0)).missionType())) {
                    formUpQ.add(wpWave);
                } else {
                    otherQ.add(wpWave);
                }
            }
        }
        if (formUpQ.isEmpty() || otherQ.isEmpty()) {
            return actions;
        }

        List<PlanAction> out = new ArrayList<>(actions.size());
        for (Object seg : segments) {
            if (seg instanceof List<?>) {
                List<PlanAction> next = !formUpQ.isEmpty() ? formUpQ.poll() : otherQ.poll();
                out.addAll(next);
            } else {
                out.add((PlanAction) seg);
            }
        }
        return out;
    }

    private static String normalizeMission(String missionType) {
        if (missionType == null || missionType.isBlank()) {
            return "";
        }
        return missionType.trim().toUpperCase();
    }

    /** HOLD_2, HOLD_3, ... (multi-detour) count as form-up waves too, same as bare HOLD. */
    private static boolean isFormUp(String missionType) {
        String m = normalizeMission(missionType);
        return "FORM_UP".equals(m) || m.startsWith("HOLD");
    }
}
