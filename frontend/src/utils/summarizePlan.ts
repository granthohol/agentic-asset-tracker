import type { ExecutionPlan } from "../types/plan";

export interface PlanDetail {
    label: string;
    value: string;
}

export interface PlanSummary {
    summary: string;
    details: PlanDetail[];
}

function formatCoord(n: number): string {
    return n.toFixed(2);
}

function titleCaseMission(raw: string): string {
    return raw
        .toLowerCase()
        .split(/[_\s]+/)
        .filter(Boolean)
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join(" ");
}

function inferFormation(plan: ExecutionPlan): string | null {
    const text = `${plan.rationale ?? ""}`;
    const m = text.match(/\b(RING|WEDGE|LINE)\b/i);
    return m ? m[1].toUpperCase() : null;
}

function inferMissionKind(plan: ExecutionPlan): string | null {
    const types = new Set<string>();
    for (const a of plan.actions) {
        if (a.op === "setWaypoint" && a.mission_type) {
            types.add(a.mission_type.toUpperCase());
        }
    }
    if (types.has("FORM_UP") && types.has("ADVANCE")) {
        return "Form up → Advance";
    }
    if (types.has("FORM_UP") || types.has("HOLD")) {
        return "Form up";
    }
    if (types.has("ADVANCE")) {
        return "Advance";
    }
    if (types.has("RECON")) {
        return "Recon";
    }
    const first = [...types][0];
    return first ? titleCaseMission(first) : null;
}

/** Title + details for the plan puck. */
export function summarizePlan(plan: ExecutionPlan): PlanSummary {
    const objective = plan.actions.find((a) => a.op === "upsertObjective");

    let summary: string;
    if (objective && objective.op === "upsertObjective" && objective.name?.trim()) {
        const name = objective.name.trim();
        // Strip "(stub)" from planner placeholder names.
        summary = name.replace(/\s*\(stub\)\s*$/i, "").trim() || name;
    } else {
        const rationale = (plan.rationale ?? "").trim().replace(/\s+/g, " ");
        summary = rationale.length > 90 ? `${rationale.slice(0, 87)}…` : rationale || "Mission plan";
    }

    const droneIds = new Set<string>();
    for (const a of plan.actions) {
        if (a.op === "setWaypoint") {
            droneIds.add(a.droneId);
        }
    }

    const details: PlanDetail[] = [];

    if (
        objective
        && objective.op === "upsertObjective"
        && objective.centerLatitude != null
        && objective.centerLongitude != null
    ) {
        details.push({
            label: "Loc",
            value: `${formatCoord(objective.centerLatitude)}, ${formatCoord(objective.centerLongitude)}`,
        });
    }

    if (droneIds.size > 0) {
        details.push({
            label: "Drones",
            value: String(droneIds.size),
        });
    }

    const formation = inferFormation(plan);
    const kind = inferMissionKind(plan);
    if (formation && kind) {
        details.push({ label: "Type", value: `${titleCaseMission(formation)} · ${kind}` });
    } else if (formation) {
        details.push({ label: "Type", value: titleCaseMission(formation) });
    } else if (kind) {
        details.push({ label: "Type", value: kind });
    }

    return { summary, details };
}

/** Group setWaypoints by mission_type, one line each, first-seen order. */
function describeWaypointGroups(plan: ExecutionPlan): string[] {
    const order: string[] = [];
    const counts = new Map<string, number>();
    for (const a of plan.actions) {
        if (a.op !== "setWaypoint") continue;
        const key = (a.mission_type ?? "MOVE").toUpperCase();
        if (!counts.has(key)) order.push(key);
        counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return order.map((key) => {
        const n = counts.get(key) ?? 0;
        return `${titleCaseMission(key)}: ${n} ${n === 1 ? "drone" : "drones"}`;
    });
}

function cleanName(raw: string | undefined, fallback: string): string {
    return (raw ?? "").trim().replace(/\s*\(stub\)\s*$/i, "").trim() || fallback;
}

// Expanded puck: one line per non-waypoint action; waypoints grouped by mission_type.
export function describePlanSteps(plan: ExecutionPlan): string[] {
    const steps: string[] = [];
    let waypointsEmitted = false;

    for (const a of plan.actions) {
        switch (a.op) {
            case "setWaypoint": {
                if (waypointsEmitted) break;
                waypointsEmitted = true;
                steps.push(...describeWaypointGroups(plan));
                break;
            }
            case "upsertObjective": {
                const loc =
                    a.centerLatitude != null && a.centerLongitude != null
                        ? ` @ ${formatCoord(a.centerLatitude)}, ${formatCoord(a.centerLongitude)}`
                        : "";
                steps.push(`Objective "${cleanName(a.name, "objective")}"${loc}`);
                break;
            }
            case "upsertSquadron":
                steps.push(`New squadron "${cleanName(a.name, "squadron")}"`);
                break;
            case "deploySquadronToObjective":
                steps.push("Deploy squadron → objective");
                break;
            case "assignDroneToSquadron":
                steps.push(`Assign ${a.droneId} → squadron`);
                break;
            case "removeDroneAssignment":
                steps.push(`Unassign ${a.droneId}`);
                break;
            case "removeSquadronFromObjective":
                steps.push("Undeploy squadron");
                break;
            case "clearWaypoint":
                steps.push(`Clear waypoint ${a.droneId}`);
                break;
            default:
                steps.push(titleCaseMission((a as { op: string }).op));
                break;
        }
    }
    return steps;
}
