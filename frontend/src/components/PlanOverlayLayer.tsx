import { Fragment } from "react";
import { Circle, CircleMarker, Polyline, Tooltip } from "react-leaflet";

import type { Drone } from "../types/drone";
import type { ExecutionPlan } from "../types/plan";
import type { AcceptedRoute } from "../types/route";
import type { MissionObjective } from "../types/missionObjective";

interface PlanOverlayLayerProps {
    pendingPlan: ExecutionPlan | null;
    acceptedRoutes: AcceptedRoute[];
    activeObjectives: MissionObjective[];
    drones: Map<string, Drone>;
}

const PENDING_COLOR = "#ff7a18";
const ACCEPTED_COLOR = "#3ad17a";
const OBJECTIVE_COLOR = "#1e88e5";
const DASH = "6 8";

function normalizeMission(missionType?: string): string {
    return (missionType ?? "").trim().toUpperCase();
}

/**
 * First contiguous setWaypoint wave in plan order — same grouping the executor publishes first.
 * (Do not cherry-pick FORM_UP out of order; that lied when ADVANCE appeared first.)
 */
function pendingWaypointWave(plan: ExecutionPlan | null) {
    if (!plan) return [];
    const start = plan.actions.findIndex((a) => a.op === "setWaypoint");
    if (start < 0) return [];
    const first = plan.actions[start];
    if (first.op !== "setWaypoint") return [];
    const waveType = normalizeMission(first.mission_type);
    const wave = [];
    for (let i = start; i < plan.actions.length; i++) {
        const a = plan.actions[i];
        if (a.op !== "setWaypoint") break;
        if (normalizeMission(a.mission_type) !== waveType) break;
        wave.push(a);
    }
    return wave;
}

/** Later setWaypoint waves (HOLD / ADVANCE) for ghost detour preview. */
function pendingLaterWaypointWaves(plan: ExecutionPlan | null) {
    if (!plan) return [];
    const firstWave = pendingWaypointWave(plan);
    if (firstWave.length === 0) return [];
    const firstIds = new Set(
        firstWave.map((a) => (a.op === "setWaypoint" ? `${a.droneId}:${a.targetLat}:${a.targetLng}` : "")),
    );
    return plan.actions.filter((a) => {
        if (a.op !== "setWaypoint") return false;
        const key = `${a.droneId}:${a.targetLat}:${a.targetLng}`;
        return !firstIds.has(key);
    });
}

/** Representative multi-wave path for one drone (shows swarm detours as a polyline). */
function pendingDetourSpine(plan: ExecutionPlan | null): [number, number][] {
    if (!plan) return [];
    const byDrone = new Map<string, [number, number][]>();
    for (const a of plan.actions) {
        if (a.op !== "setWaypoint") continue;
        const pts = byDrone.get(a.droneId) ?? [];
        pts.push([a.targetLat, a.targetLng]);
        byDrone.set(a.droneId, pts);
    }
    let best: [number, number][] = [];
    for (const pts of byDrone.values()) {
        if (pts.length > best.length) best = pts;
    }
    return best;
}

// Plan overlays: pending (orange), accepted (green, tracks live drone), AOI circles.
export default function PlanOverlayLayer({
    pendingPlan,
    acceptedRoutes,
    activeObjectives,
    drones,
}: PlanOverlayLayerProps) {
    const pendingWaypoints = pendingWaypointWave(pendingPlan);
    const pendingLaterWaypoints = pendingLaterWaypointWaves(pendingPlan);
    const pendingSpine = pendingDetourSpine(pendingPlan);
    const pendingRoutes =
        pendingPlan?.actions.filter((a) => a.op === "setRoute") ?? [];

    const swarmMode =
        pendingWaypoints.length >= 2 || acceptedRoutes.length >= 2;
    const pendingLineWeight = swarmMode ? 1.5 : 2;
    const objectiveRadiusBoost = swarmMode ? 1.15 : 1;

    // Approved objectives win; else show pending AOI.
    const objectives: MissionObjective[] =
        activeObjectives.length > 0
            ? activeObjectives
            : (pendingPlan?.actions.flatMap((action, i) => {
                  if (action.op !== "upsertObjective") return [];
                  if (action.centerLatitude == null || action.centerLongitude == null) {
                      return [];
                  }
                  return [{
                      id: `${pendingPlan.planId}-obj-${i}`,
                      name: action.name,
                      centerLatitude: action.centerLatitude,
                      centerLongitude: action.centerLongitude,
                      radiusMeters: action.radiusMeters ?? 300,
                  }];
              }) ?? []);

    return (
        <>
            {objectives.map((obj) => (
                <Circle
                    key={obj.id}
                    center={[obj.centerLatitude, obj.centerLongitude]}
                    radius={obj.radiusMeters * objectiveRadiusBoost}
                    pathOptions={{
                        color: OBJECTIVE_COLOR,
                        weight: 2,
                        dashArray: DASH,
                        fillOpacity: 0.08,
                    }}
                >
                    <Tooltip permanent direction="top">
                        ◎ {obj.name}
                    </Tooltip>
                </Circle>
            ))}

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

            {pendingWaypoints.map((action, i) => {
                if (action.op !== "setWaypoint" || !pendingPlan) return null;
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
            })}

            {/* Ghost later waves (HOLD / ADVANCE) so avoid detours are visible while proposed. */}
            {pendingLaterWaypoints.map((action, i) => {
                if (action.op !== "setWaypoint" || !pendingPlan) return null;
                return (
                    <CircleMarker
                        key={`pending-later-${pendingPlan.planId}-${i}`}
                        center={[action.targetLat, action.targetLng]}
                        radius={3}
                        pathOptions={{
                            color: PENDING_COLOR,
                            fillColor: PENDING_COLOR,
                            fillOpacity: 0.25,
                            opacity: 0.5,
                        }}
                    >
                        <Tooltip>
                            {action.droneId} → ({action.targetLat.toFixed(3)}, {action.targetLng.toFixed(3)})
                            {action.mission_type ? ` · ${action.mission_type}` : ""}
                        </Tooltip>
                    </CircleMarker>
                );
            })}
            {pendingSpine.length >= 2 && pendingPlan && (
                <Polyline
                    positions={pendingSpine}
                    pathOptions={{
                        color: PENDING_COLOR,
                        weight: 2,
                        dashArray: "2 10",
                        opacity: 0.55,
                    }}
                />
            )}

            {pendingRoutes.map((action, i) => {
                if (action.op !== "setRoute" || !pendingPlan) return null;
                const legs = action.legs ?? [];
                if (legs.length === 0) return null;
                const drone = drones.get(action.droneId);
                const path: [number, number][] = [];
                if (drone) {
                    path.push([drone.latitude, drone.longitude]);
                }
                for (const [lat, lng] of legs) {
                    path.push([lat, lng]);
                }
                return (
                    <Fragment key={`pending-route-${pendingPlan.planId}-${i}`}>
                        {path.length >= 2 && (
                            <Polyline
                                positions={path}
                                pathOptions={{
                                    color: PENDING_COLOR,
                                    weight: 2,
                                    dashArray: DASH,
                                }}
                            />
                        )}
                        {legs.map(([lat, lng], li) => (
                            <CircleMarker
                                key={`pending-route-leg-${i}-${li}`}
                                center={[lat, lng]}
                                radius={li === legs.length - 1 ? 6 : 4}
                                pathOptions={{
                                    color: PENDING_COLOR,
                                    fillColor: PENDING_COLOR,
                                    fillOpacity: 0.6,
                                }}
                            >
                                <Tooltip>
                                    {action.droneId} leg {li + 1}/{legs.length}
                                    {` → (${lat.toFixed(3)}, ${lng.toFixed(3)})`}
                                </Tooltip>
                            </CircleMarker>
                        ))}
                    </Fragment>
                );
            })}
        </>
    );
}
