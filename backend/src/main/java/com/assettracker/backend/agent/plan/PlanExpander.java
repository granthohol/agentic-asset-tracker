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

/**
 * Expands applyFormation macros into setWaypoint actions the rest of the system already knows.
 * Runs in the orchestrator (before the plan leaves the server) and again in PlanExecutor (defensive).
 */
@Component
public class PlanExpander {

    private final FormationService formations;

    public PlanExpander(FormationService formations) {
        this.formations = formations;
    }

    /** Flatten every applyFormation to setWaypoints. No-op if there are none. */
    public ExecutionPlan expand(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new ExecutionPlan(plan.planId(), plan.rationale(), expandActions(plan.actions()));
    }

    /** Replace each applyFormation with per-drone setWaypoints. Other actions pass through in order. */
    public List<PlanAction> expandActions(List<PlanAction> actions) {
        if (actions == null) {
            return List.of();
        }
        List<PlanAction> out = new ArrayList<>(actions.size());
        for (PlanAction action : actions) {
            if (action instanceof PlanAction.ApplyFormation af) {
                FormationPreview preview = formations.preview(
                    af.formationType(),
                    af.centerLat(),
                    af.centerLng(),
                    af.droneIds(),
                    af.spacingMeters(),
                    af.facingLat(),
                    af.facingLng());
                for (FormationSlot slot : preview.slots()) {
                    out.add(new PlanAction.SetWaypoint(
                        slot.droneId(), slot.targetLat(), slot.targetLng(), af.missionType()));
                }
            } else {
                out.add(action);
            }
        }
        return prioritizeFormUpWaves(out);
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

    private static boolean isFormUp(String missionType) {
        String m = normalizeMission(missionType);
        return "FORM_UP".equals(m) || "HOLD".equals(m);
    }
}
