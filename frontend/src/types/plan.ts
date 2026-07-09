// Mirrors the backend ExecutionPlan v2 contract (docs/PLAN.md). The discriminated union
// on `op` matches the Java sealed PlanAction; field names match the wire JSON exactly
// (note setWaypoint uses snake_case `mission_type`), so a plan from /api/plan can be sent
// straight back to /api/execute-plan with JSON.stringify and no transformation.

export interface UpsertSquadron {
    op: "upsertSquadron";
    id?: string;
    tempId?: string;
    name: string;
    sectorId: string;
}

export interface UpsertObjective {
    op: "upsertObjective";
    id?: string;
    tempId?: string;
    name: string;
    priority: number;
    centerLatitude?: number;
    centerLongitude?: number;
    radiusMeters?: number;
    targetEntityId?: string;
}

export interface AssignDroneToSquadron {
    op: "assignDroneToSquadron";
    droneId: string;
    squadronId: string;
}

export interface DeploySquadronToObjective {
    op: "deploySquadronToObjective";
    squadronId: string;
    objectiveId: string;
}

export interface RemoveDroneAssignment {
    op: "removeDroneAssignment";
    droneId: string;
}

export interface RemoveSquadronFromObjective {
    op: "removeSquadronFromObjective";
    squadronId: string;
}

export interface SetWaypoint {
    op: "setWaypoint";
    droneId: string;
    targetLat: number;
    targetLng: number;
    mission_type?: string;
}

export interface ClearWaypoint {
    op: "clearWaypoint";
    droneId: string;
}

export type PlanAction =
    | UpsertSquadron
    | UpsertObjective
    | AssignDroneToSquadron
    | DeploySquadronToObjective
    | RemoveDroneAssignment
    | RemoveSquadronFromObjective
    | SetWaypoint
    | ClearWaypoint;

export interface ExecutionPlan {
    planId: string;
    rationale: string;
    actions: PlanAction[];
}
