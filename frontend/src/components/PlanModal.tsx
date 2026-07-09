import type { ExecutionPlan, PlanAction } from "../types/plan";

interface PlanModalProps {
    plan: ExecutionPlan;
    executing: boolean;
    onApprove: () => void;
    onReject: () => void;
}

function describe(action: PlanAction): string {
    switch (action.op) {
        case "upsertSquadron":
            return `Create squadron "${action.name}" (${action.tempId ?? action.id}) in ${action.sectorId}`;
        case "upsertObjective": {
            const where =
                action.centerLatitude != null && action.centerLongitude != null
                    ? ` at (${action.centerLatitude}, ${action.centerLongitude})${action.radiusMeters ? ` · r=${action.radiusMeters}m` : ""}`
                    : action.targetEntityId
                        ? ` tracking ${action.targetEntityId}`
                        : "";
            return `Create objective "${action.name}" (priority ${action.priority})${where}`;
        }
        case "assignDroneToSquadron":
            return `Assign ${action.droneId} → squadron ${action.squadronId}`;
        case "deploySquadronToObjective":
            return `Deploy squadron ${action.squadronId} → objective ${action.objectiveId}`;
        case "removeDroneAssignment":
            return `Unassign ${action.droneId} from its squadron`;
        case "removeSquadronFromObjective":
            return `Undeploy squadron ${action.squadronId}`;
        case "setWaypoint":
            return `Route ${action.droneId} → (${action.targetLat}, ${action.targetLng})${action.mission_type ? ` · ${action.mission_type}` : ""}`;
        case "clearWaypoint":
            return `Clear waypoint for ${action.droneId}`;
    }
}

/**
 * Human-in-the-loop gate. Lists the proposed plan in plain language. Approve POSTs to
 * /api/execute-plan (handled by the parent); Reject just clears the plan + ghost overlay
 * with no network call.
 */
export default function PlanModal({ plan, executing, onApprove, onReject }: PlanModalProps) {
    return (
        <div className="plan-modal">
            <div className="plan-modal__header">
                <span className="plan-modal__title">Proposed plan</span>
                <span className="plan-modal__id">{plan.planId}</span>
            </div>

            <p className="plan-modal__rationale">{plan.rationale}</p>

            <ol className="plan-modal__actions">
                {plan.actions.map((action, i) => (
                    <li key={i} className="plan-modal__action">
                        <span className="plan-modal__op">{action.op}</span>
                        <span>{describe(action)}</span>
                    </li>
                ))}
            </ol>

            <div className="plan-modal__buttons">
                <button className="btn btn--reject" onClick={onReject} disabled={executing}>
                    Reject
                </button>
                <button className="btn btn--approve" onClick={onApprove} disabled={executing}>
                    {executing ? "Dispatching…" : "Approve & Execute"}
                </button>
            </div>
        </div>
    );
}
