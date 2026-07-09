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

/**
 * Map overlays for HITL plans.
 * - Pending setWaypoint / upsertObjective: orange / blue dashed (proposal).
 * - Accepted routes: green dashed lines whose start always tracks the live drone.
 */
export default function PlanOverlayLayer({
    pendingPlan,
    acceptedRoutes,
    drones,
}: PlanOverlayLayerProps) {
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
                                pathOptions={{ color: ACCEPTED_COLOR, weight: 2, dashArray: DASH }}
                            />
                        )}
                        <CircleMarker
                            center={target}
                            radius={6}
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
                    const target: [number, number] = [action.targetLat, action.targetLng];
                    const drone = drones.get(action.droneId);
                    return (
                        <Fragment key={`pending-wp-${pendingPlan.planId}-${i}`}>
                            {drone && (
                                <Polyline
                                    positions={[[drone.latitude, drone.longitude], target]}
                                    pathOptions={{ color: PENDING_COLOR, weight: 2, dashArray: DASH }}
                                />
                            )}
                            <CircleMarker
                                center={target}
                                radius={6}
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
                    return (
                        <Circle
                            key={`pending-obj-${pendingPlan.planId}-${i}`}
                            center={center}
                            radius={action.radiusMeters ?? 300}
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
