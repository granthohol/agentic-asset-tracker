import { Circle, CircleMarker, Polyline, Tooltip } from "react-leaflet";

import type { Drone } from "../types/drone";
import type { ExecutionPlan } from "../types/plan";

interface PlanGhostLayerProps {
    plan: ExecutionPlan | null;
    drones: Map<string, Drone>;
}

const GHOST_COLOR = "#ff7a18";
const OBJECTIVE_COLOR = "#1e88e5";
const DASH = "6 8";

/**
 * Transient overlay for a *proposed* plan. Derived purely from the active plan's actions —
 * never merged into the live drones map, so the next telemetry tick can't overwrite it and
 * rejecting a plan simply removes this layer.
 *
 * - setWaypoint  -> dashed line from the drone's current position to the target + a target dot
 * - upsertObjective (with a center) -> dashed circle (radiusMeters) labeled with its name
 */
export default function PlanGhostLayer({ plan, drones }: PlanGhostLayerProps) {
    if (!plan) return null;

    return (
        <>
            {plan.actions.map((action, i) => {
                if (action.op === "setWaypoint") {
                    const target: [number, number] = [action.targetLat, action.targetLng];
                    const drone = drones.get(action.droneId);
                    return (
                        <div key={`wp-${i}`}>
                            {drone && (
                                <Polyline
                                    positions={[[drone.latitude, drone.longitude], target]}
                                    pathOptions={{ color: GHOST_COLOR, weight: 2, dashArray: DASH }}
                                />
                            )}
                            <CircleMarker
                                center={target}
                                radius={6}
                                pathOptions={{ color: GHOST_COLOR, fillColor: GHOST_COLOR, fillOpacity: 0.6 }}
                            >
                                <Tooltip>
                                    {action.droneId} → ({action.targetLat.toFixed(3)}, {action.targetLng.toFixed(3)})
                                    {action.mission_type ? ` · ${action.mission_type}` : ""}
                                </Tooltip>
                            </CircleMarker>
                        </div>
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
                            key={`obj-${i}`}
                            center={center}
                            radius={action.radiusMeters ?? 300}
                            pathOptions={{ color: OBJECTIVE_COLOR, weight: 2, dashArray: DASH, fillOpacity: 0.08 }}
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
