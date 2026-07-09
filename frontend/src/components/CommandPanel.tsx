import { useState, type FormEvent } from "react";

import type { ExecutionPlan, PlanAction } from "../types/plan";

interface CommandPanelProps {
    planning: boolean;
    executing: boolean;
    pendingPlan: ExecutionPlan | null;
    toast: { kind: "ok" | "error"; message: string } | null;
    onSubmit: (command: string) => void;
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
 * Left-rail HITL surface: command input + proposed plan (approve/reject).
 * Map overlays are driven by App state; this panel never floats over the map.
 */
export default function CommandPanel({
    planning,
    executing,
    pendingPlan,
    toast,
    onSubmit,
    onApprove,
    onReject,
}: CommandPanelProps) {
    const [command, setCommand] = useState("");

    const submit = (e: FormEvent) => {
        e.preventDefault();
        const trimmed = command.trim();
        if (!trimmed || planning || executing) return;
        onSubmit(trimmed);
    };

    return (
        <aside className="command-panel">
            <header className="command-panel__header">
                <h1 className="command-panel__title">Command</h1>
                <p className="command-panel__subtitle">Plan with the agent, approve before execute.</p>
            </header>

            <form className="command-panel__form" onSubmit={submit}>
                <textarea
                    className="command-panel__input"
                    rows={3}
                    placeholder='e.g. "Observe the disturbance at 39.05,-77.18"'
                    value={command}
                    onChange={(e) => setCommand(e.target.value)}
                    disabled={planning || executing}
                />
                <button
                    className="command-panel__plan-btn"
                    type="submit"
                    disabled={planning || executing || !command.trim()}
                >
                    {planning ? "Planning…" : "Plan"}
                </button>
            </form>

            <div className="command-panel__body">
                {planning && !pendingPlan && (
                    <p className="command-panel__status">Running planner tools…</p>
                )}

                {!planning && !pendingPlan && (
                    <p className="command-panel__status">
                        Type a command above. Proposed plans appear here; the map stays clear until then.
                    </p>
                )}

                {pendingPlan && (
                    <section className="command-panel__plan">
                        <div className="command-panel__plan-header">
                            <span className="command-panel__plan-title">Proposed plan</span>
                            <span className="command-panel__plan-id">{pendingPlan.planId}</span>
                        </div>

                        <p className="command-panel__rationale">{pendingPlan.rationale}</p>

                        <ol className="command-panel__actions">
                            {pendingPlan.actions.map((action, i) => (
                                <li key={i} className="command-panel__action">
                                    <span className="command-panel__op">{action.op}</span>
                                    <span>{describe(action)}</span>
                                </li>
                            ))}
                        </ol>

                        <div className="command-panel__buttons">
                            <button className="btn btn--reject" onClick={onReject} disabled={executing}>
                                Reject
                            </button>
                            <button className="btn btn--approve" onClick={onApprove} disabled={executing}>
                                {executing ? "Dispatching…" : "Approve & Execute"}
                            </button>
                        </div>
                    </section>
                )}
            </div>

            {toast && (
                <div className={`command-panel__toast command-panel__toast--${toast.kind}`}>
                    {toast.message}
                </div>
            )}
        </aside>
    );
}
