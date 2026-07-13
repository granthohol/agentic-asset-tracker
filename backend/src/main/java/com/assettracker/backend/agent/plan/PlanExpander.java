package com.assettracker.backend.agent.plan;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.assettracker.backend.agent.formation.FormationPreview;
import com.assettracker.backend.agent.formation.FormationService;
import com.assettracker.backend.agent.formation.FormationSlot;

/**
 * Expands compact {@link PlanAction.ApplyFormation} macros into the concrete
 * {@link PlanAction.SetWaypoint} actions the rest of the system already understands.
 *
 * <p>The macro only shrinks what the <b>LLM emits</b> (~2 actions for a two-phase swarm instead
 * of ~100). Everything downstream — {@code /api/plan} responses, the frontend overlays and
 * mission card, {@code /api/execute-plan}, and {@link com.assettracker.backend.execution.PlanExecutor}
 * — keeps seeing per-drone {@code setWaypoint}s. So this runs twice: once in the orchestrator
 * (before a plan leaves the server) and defensively at the top of the executor.
 */
@Component
public class PlanExpander {

    private final FormationService formations;

    public PlanExpander(FormationService formations) {
        this.formations = formations;
    }

    /** Return a copy of the plan with every {@code applyFormation} flattened to {@code setWaypoint}s. */
    public ExecutionPlan expand(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new ExecutionPlan(plan.planId(), plan.rationale(), expandActions(plan.actions()));
    }

    /**
     * Replace each {@link PlanAction.ApplyFormation} with N {@link PlanAction.SetWaypoint}s
     * (one per slot, carrying the macro's mission type). Non-formation actions pass through
     * unchanged and in order. Idempotent: a plan with no macros is returned equivalently.
     */
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
