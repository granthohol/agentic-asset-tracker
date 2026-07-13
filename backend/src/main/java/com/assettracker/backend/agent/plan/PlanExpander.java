package com.assettracker.backend.agent.plan;

import java.util.ArrayList;
import java.util.List;

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
        return out;
    }
}
