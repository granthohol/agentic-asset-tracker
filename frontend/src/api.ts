import type { ExecutionPlan } from "./types/plan";

const API_BASE = "http://localhost:8080";

/** Run the LLM planner loop and get back a proposed (read-only) ExecutionPlan. */
export async function requestPlan(command: string): Promise<ExecutionPlan> {
    const res = await fetch(`${API_BASE}/api/plan`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ command }),
    });
    if (!res.ok) {
        throw new Error(`/api/plan failed (${res.status}): ${await res.text()}`);
    }
    return (await res.json()) as ExecutionPlan;
}

export interface ExecuteResult {
    planId: string;
    status: string;
    receivedAt: number;
}

/**
 * Approve a plan: send it to the CQRS write gate. Returns 202 + {planId,status,receivedAt}.
 * The plan object is round-tripped verbatim (same JSON the planner produced).
 */
export async function executePlan(plan: ExecutionPlan): Promise<ExecuteResult> {
    const res = await fetch(`${API_BASE}/api/execute-plan`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(plan),
    });
    const text = await res.text();
    if (!res.ok) {
        throw new Error(`/api/execute-plan rejected (${res.status}): ${text}`);
    }
    return JSON.parse(text) as ExecuteResult;
}

export interface CancelResult {
    status: string;
    cleared: number;
}

/** HITL Stop: clear waypoints for the given drones (graph + edge CLEAR_WAYPOINT). */
export async function cancelMission(droneIds: string[]): Promise<CancelResult> {
    const res = await fetch(`${API_BASE}/api/cancel-mission`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ droneIds }),
    });
    const text = await res.text();
    if (!res.ok) {
        throw new Error(`/api/cancel-mission failed (${res.status}): ${text}`);
    }
    return JSON.parse(text) as CancelResult;
}
