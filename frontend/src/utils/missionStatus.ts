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
            (a) =>
                (a.op === 'setWaypoint' || a.op === 'setRoute')
                && a.droneId === droneId,
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
    const proposedWp = pendingPlan?.actions.find(
        (a) => a.op === 'setWaypoint' && a.droneId === droneId,
    );
    if (proposedWp && proposedWp.op === 'setWaypoint') {
        return {
            missionType: proposedWp.mission_type,
            waypoint: { lat: proposedWp.targetLat, lng: proposedWp.targetLng },
        };
    }
    const proposedRoute = pendingPlan?.actions.find(
        (a) => a.op === 'setRoute' && a.droneId === droneId,
    );
    if (proposedRoute && proposedRoute.op === 'setRoute' && proposedRoute.legs?.length) {
        const [lat, lng] = proposedRoute.legs[0];
        return {
            missionType: proposedRoute.mission_type ?? 'TRANSIT',
            waypoint: { lat, lng },
        };
    }
    return {};
}
