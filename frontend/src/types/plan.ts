// Matches backend ExecutionPlan v2 (docs/PLAN.md). Same JSON from /api/plan goes to /api/execute-plan.
// setWaypoint uses snake_case mission_type on the wire.

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
