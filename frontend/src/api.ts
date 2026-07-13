import type { ExecutionPlan } from "./types/plan";
import type {
    MapWaypoint,
    Track,
    TrackRequest,
    WaypointRequest,
    Zone,
    ZoneRequest,
} from "./types/entity";

const API_BASE = "http://localhost:8080";

// Hit the planner, get a read-only plan back.
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

// Approve and dispatch. Same JSON the planner returned goes straight to the write gate.
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

// Stop mission: clear waypoints for these drones.
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

// Map entity CRUD. Backend 400s include { error: string }; pass that through to forms.

async function readError(res: Response): Promise<string> {
    const text = await res.text();
    try {
        const parsed = JSON.parse(text) as { error?: string };
        if (parsed && typeof parsed.error === "string") {
            return parsed.error;
        }
    } catch {
        // fall through to raw text
    }
    return text || `HTTP ${res.status}`;
}

async function writeEntity<T>(url: string, method: "POST" | "PUT", body: unknown): Promise<T> {
    const res = await fetch(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        throw new Error(await readError(res));
    }
    return (await res.json()) as T;
}

async function deleteEntity(url: string): Promise<void> {
    const res = await fetch(url, { method: "DELETE" });
    if (!res.ok) {
        throw new Error(await readError(res));
    }
}

export function createTrack(req: TrackRequest): Promise<Track> {
    return writeEntity<Track>(`${API_BASE}/api/tracks`, "POST", req);
}

export function updateTrack(id: string, req: TrackRequest): Promise<Track> {
    return writeEntity<Track>(`${API_BASE}/api/tracks/${id}`, "PUT", req);
}

export function deleteTrack(id: string): Promise<void> {
    return deleteEntity(`${API_BASE}/api/tracks/${id}`);
}

export function createWaypoint(req: WaypointRequest): Promise<MapWaypoint> {
    return writeEntity<MapWaypoint>(`${API_BASE}/api/waypoints`, "POST", req);
}

export function updateWaypoint(id: string, req: WaypointRequest): Promise<MapWaypoint> {
    return writeEntity<MapWaypoint>(`${API_BASE}/api/waypoints/${id}`, "PUT", req);
}

export function deleteWaypoint(id: string): Promise<void> {
    return deleteEntity(`${API_BASE}/api/waypoints/${id}`);
}

export function createZone(req: ZoneRequest): Promise<Zone> {
    return writeEntity<Zone>(`${API_BASE}/api/zones`, "POST", req);
}

export function updateZone(id: string, req: ZoneRequest): Promise<Zone> {
    return writeEntity<Zone>(`${API_BASE}/api/zones/${id}`, "PUT", req);
}

export function deleteZone(id: string): Promise<void> {
    return deleteEntity(`${API_BASE}/api/zones/${id}`);
}
