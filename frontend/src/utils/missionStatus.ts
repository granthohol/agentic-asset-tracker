import type { ExecutionPlan } from '../types/plan';
import type { AcceptedRoute } from '../types/route';
import type { MissionVisualStatus } from '../components/droneIcons';

/** Marker tint: executing beats proposed beats idle. */
export function missionVisualStatus(
    droneId: string,
    pendingPlan: ExecutionPlan | null,
    acceptedRoutes: AcceptedRoute[],
): MissionVisualStatus {
    if (acceptedRoutes.some((r) => r.droneId === droneId)) {
        return 'executing';
    }
    if (
        pendingPlan?.actions.some(
            (a) => a.op === 'setWaypoint' && a.droneId === droneId,
        )
    ) {
        return 'proposed';
    }
    return 'idle';
}

export interface DroneMissionInfo {
    missionType?: string;
    waypoint?: { lat: number; lng: number };
}

/** Active or proposed waypoint + mission type for one drone. */
export function droneMissionInfo(
    droneId: string,
    pendingPlan: ExecutionPlan | null,
    acceptedRoutes: AcceptedRoute[],
): DroneMissionInfo {
    const route = acceptedRoutes.find((r) => r.droneId === droneId);
    if (route) {
        return {
            missionType: route.missionType,
            waypoint: { lat: route.targetLat, lng: route.targetLng },
        };
    }
    const proposed = pendingPlan?.actions.find(
        (a) => a.op === 'setWaypoint' && a.droneId === droneId,
    );
    if (proposed && proposed.op === 'setWaypoint') {
        return {
            missionType: proposed.mission_type,
            waypoint: { lat: proposed.targetLat, lng: proposed.targetLng },
        };
    }
    return {};
}
