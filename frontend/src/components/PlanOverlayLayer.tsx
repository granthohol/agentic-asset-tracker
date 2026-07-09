import { Fragment } from "react";
import { Circle, CircleMarker, Polyline, Tooltip } from "react-leaflet";

import type { Drone } from "../types/drone";
import type { ExecutionPlan } from "../types/plan";
import type { AcceptedRoute } from "../types/route";

interface PlanOverlayLayerProps {
    pendingPlan: ExecutionPlan | null;
    acceptedRoutes: AcceptedRoute[];
    drones: Map<string, Drone>;
}

const PENDING_COLOR = "#ff7a18";
const ACCEPTED_COLOR = "#3ad17a";
const OBJECTIVE_COLOR = "#1e88e5";
const DASH = "6 8";

function isFormUpMission(missionType?: string): boolean {
    const m = (missionType ?? "").toUpperCase();
    return m === "FORM_UP" || m === "HOLD";
}

/**
 * Map overlays for HITL plans.
 * - Pending: orange FORM_UP wedge + blue AOI (hide ADVANCE until after approve/form-up).
 * - Accepted: green dashed lines whose start always tracks the live drone.
 */
export default function PlanOverlayLayer({
    pendingPlan,
    acceptedRoutes,
    drones,
}: PlanOverlayLayerProps) {
    // Two-phase plans include FORM_UP + ADVANCE; proposal mode only shows the form-up
    // assembly so we don't draw two identical wedges (standoff + AOI) at once.
    const pendingHasFormUp =
        pendingPlan?.actions.some(
            (a) => a.op === "setWaypoint" && isFormUpMission(a.mission_type),
        ) ?? false;

    const pendingWaypoints =
        pendingPlan?.actions.filter((a) => {
            if (a.op !== "setWaypoint") return false;
            if (pendingHasFormUp && !isFormUpMission(a.mission_type)) return false;
            return true;
        }) ?? [];

    const swarmMode = pendingWaypoints.length >= 2;
    const pendingLineWeight = swarmMode ? 1.5 : 2;
    const objectiveRadiusBoost = swarmMode ? 1.15 : 1;

    return (
        <>
            {acceptedRoutes.map((route) => {
                const target: [number, number] = [route.targetLat, route.targetLng];
                const drone = drones.get(route.droneId);
                return (
                    <Fragment key={route.id}>
                        {drone && (
                            <Polyline
                                positions={[[drone.latitude, drone.longitude], target]}
                                pathOptions={{ color: ACCEPTED_COLOR, weight: 1.5, dashArray: DASH }}
                            />
                        )}
                        <CircleMarker
                            center={target}
                            radius={5}
                            pathOptions={{
                                color: ACCEPTED_COLOR,
                                fillColor: ACCEPTED_COLOR,
                                fillOpacity: 0.6,
                            }}
                        >
                            <Tooltip>
                                {route.droneId} → ({route.targetLat.toFixed(3)}, {route.targetLng.toFixed(3)})
                                {route.missionType ? ` · ${route.missionType}` : ""}
                            </Tooltip>
                        </CircleMarker>
                    </Fragment>
                );
            })}

            {pendingPlan?.actions.map((action, i) => {
                if (action.op === "setWaypoint") {
                    if (pendingHasFormUp && !isFormUpMission(action.mission_type)) {
                        return null;
                    }
                    const target: [number, number] = [action.targetLat, action.targetLng];
                    const drone = drones.get(action.droneId);
                    return (
                        <Fragment key={`pending-wp-${pendingPlan.planId}-${i}`}>
                            {drone && (
                                <Polyline
                                    positions={[[drone.latitude, drone.longitude], target]}
                                    pathOptions={{
                                        color: PENDING_COLOR,
                                        weight: pendingLineWeight,
                                        dashArray: DASH,
                                    }}
                                />
                            )}
                            <CircleMarker
                                center={target}
                                radius={swarmMode ? 5 : 6}
                                pathOptions={{
                                    color: PENDING_COLOR,
                                    fillColor: PENDING_COLOR,
                                    fillOpacity: 0.6,
                                }}
                            >
                                <Tooltip>
                                    {action.droneId} → ({action.targetLat.toFixed(3)}, {action.targetLng.toFixed(3)})
                                    {action.mission_type ? ` · ${action.mission_type}` : ""}
                                </Tooltip>
                            </CircleMarker>
                        </Fragment>
                    );
                }

                if (
                    action.op === "upsertObjective" &&
                    action.centerLatitude != null &&
                    action.centerLongitude != null
                ) {
                    const center: [number, number] = [action.centerLatitude, action.centerLongitude];
                    const baseRadius = action.radiusMeters ?? 300;
                    return (
                        <Circle
                            key={`pending-obj-${pendingPlan.planId}-${i}`}
                            center={center}
                            radius={baseRadius * objectiveRadiusBoost}
                            pathOptions={{
                                color: OBJECTIVE_COLOR,
                                weight: 2,
                                dashArray: DASH,
                                fillOpacity: 0.08,
                            }}
                        >
                            <Tooltip permanent direction="top">
                                ◎ {action.name}
                            </Tooltip>
                        </Circle>
                    );
                }

                return null;
            })}
        </>
    );
}
