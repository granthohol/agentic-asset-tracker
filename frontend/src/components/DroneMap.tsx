import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import { useState, useEffect, memo } from 'react';

import type { ExecutionPlan } from '../types/plan'
import type { AcceptedRoute } from '../types/route'
import type { MissionObjective } from '../types/missionObjective'
import PlanOverlayLayer from './PlanOverlayLayer'
import EntityLayer from './EntityLayer'
import EntityPlacement from './EntityPlacement'
import EntityCreateForm from './EntityCreateForm'
import EntityInspector from './EntityInspector'
import DroneInspector from './DroneInspector'
import DroneMarkerLayer from './DroneMarkerLayer'
import { decodeTelemetryFrame } from '../telemetry/decodeTelemetryFrame'
import { useDroneStore } from '../store/droneStore'
import { missionVisualStatus, droneMissionInfo } from '../utils/missionStatus'

const WEBSOCKET_URL = "ws://localhost:8080/ws/drones";

// These overlays take no props and read their own stores. Memoizing them stops the 20 Hz
// telemetry batch re-renders of DroneMap from cascading into them.
const EntityLayerMemo = memo(EntityLayer);
const EntityPlacementMemo = memo(EntityPlacement);
const EntityCreateFormMemo = memo(EntityCreateForm);
const EntityInspectorMemo = memo(EntityInspector);

/** Matches edge/producer.py STEER_STEP_DEG arrival snap (with slack for telemetry lag). */
const ARRIVAL_DEG = 0.002;

function distanceDegrees(
    a: { latitude: number; longitude: number },
    b: { targetLat: number; targetLng: number },
): number {
    const dlat = a.latitude - b.targetLat;
    const dlng = a.longitude - b.targetLng;
    return Math.hypot(dlat, dlng);
}

/** Leaflet needs invalidateSize when the map sits in a flex pane instead of full viewport. */
function MapResizeFix() {
    const map = useMap();
    useEffect(() => {
        const container = map.getContainer();
        const observer = new ResizeObserver(() => {
            map.invalidateSize({ animate: false });
        });
        observer.observe(container);
        map.invalidateSize({ animate: false });
        return () => observer.disconnect();
    }, [map]);
    return null;
}

interface DroneMapProps {
    pendingPlan: ExecutionPlan | null;
    acceptedRoutes: AcceptedRoute[];
    activeObjectives: MissionObjective[];
    onRoutesCompleted: (completedIds: string[]) => void;
}

export default function DroneMap({
    pendingPlan,
    acceptedRoutes,
    activeObjectives,
    onRoutesCompleted,
}: DroneMapProps) {
    const [, setConnectionStatus] = useState<'Connecting' | 'Open' | 'Closed' | 'Error'>('Connecting');
    // Live fleet lives in the Zustand store; the imperative marker layer reads it via
    // getState() in rAF. Lightweight consumers here (arrival detection, overlays,
    // inspector) subscribe for their occasional updates.
    const drones = useDroneStore((s) => s.drones);
    const [selectedId, setSelectedId] = useState<string | null>(null);

    // Clear overlays on arrival. FORM_UP/HOLD loiter — treat "all form-up drones arrived"
    // as phase-complete so App can swap in ADVANCE routes. ADVANCE clears per-route.
    useEffect(() => {
        if (acceptedRoutes.length === 0 || drones.size === 0) return;

        const isFormUp = (m?: string) => {
            const t = (m ?? "").toUpperCase();
            return t === "FORM_UP" || t === "HOLD";
        };

        const formUps = acceptedRoutes.filter((r) => isFormUp(r.missionType));
        if (formUps.length > 0) {
            const allFormed = formUps.every((route) => {
                const drone = drones.get(route.droneId);
                return drone != null && distanceDegrees(drone, route) <= ARRIVAL_DEG;
            });
            if (allFormed) {
                onRoutesCompleted(formUps.map((r) => r.id));
            }
            return;
        }

        const completed = acceptedRoutes
            .filter((route) => {
                const drone = drones.get(route.droneId);
                if (!drone) return false;
                return distanceDegrees(drone, route) <= ARRIVAL_DEG;
            })
            .map((route) => route.id);
        if (completed.length > 0) {
            onRoutesCompleted(completed);
        }
    }, [drones, acceptedRoutes, onRoutesCompleted]);

    useEffect(() => {
        // Reconnect-with-backoff state, scoped to this effect run.
        let cancelled = false;
        let ws: WebSocket | null = null;
        let reconnectAttempt = 0;
        let reconnectTimer: number | null = null;

        const RECONNECT_BASE_MS = 1000;
        const RECONNECT_MAX_MS = 30000;

        const scheduleReconnect = () => {
            if (cancelled || reconnectTimer !== null) return;
            const delay = Math.min(
                RECONNECT_BASE_MS * Math.pow(2, reconnectAttempt),
                RECONNECT_MAX_MS,
            );
            reconnectAttempt += 1;
            console.log(`Reconnecting in ${delay}ms (attempt ${reconnectAttempt})`);
            reconnectTimer = window.setTimeout(() => {
                reconnectTimer = null;
                connect();
            }, delay);
        };

        const connect = () => {
            if (cancelled) return;

            ws = new WebSocket(WEBSOCKET_URL);
            // Phase 4: frames are binary protobuf (proto/telemetry.proto), not JSON text.
            ws.binaryType = 'arraybuffer';

            ws.onopen = () => {
                console.log('WebSocket Connection Established');
                setConnectionStatus('Open');
                reconnectAttempt = 0; // healthy connection resets backoff
            };

            ws.onmessage = (event: MessageEvent) => {
                if (!(event.data instanceof ArrayBuffer)) {
                    console.warn('Expected binary telemetry frame, got', typeof event.data);
                    return;
                }
                const frame = decodeTelemetryFrame(event.data);
                const store = useDroneStore.getState();
                if (frame.kind === 'snapshot') {
                    store.applySnapshot(frame.drones);
                } else {
                    store.applyBatch(frame.drones);
                }
            };

            ws.onclose = (event: CloseEvent) => {
                console.log(`WebSocket Disconnected. Code: ${event.code}, Reason: ${event.reason}`);
                setConnectionStatus('Closed');
                scheduleReconnect();
            };

            ws.onerror = (error: Event) => {
                console.error('WebSocket encountered an error', error);
                setConnectionStatus('Error');
                // onclose fires after onerror in browsers; reconnect is scheduled there.
            };
        };

        // actual starting point
        connect();

        return () => {
            console.log('Component unmounting, tearing down WebSocket...');
            cancelled = true;
            if (reconnectTimer !== null) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
                ws.close(); // Sends the TCP FIN packet to your Java server
            }
        };

    }, []); // [] -> runs once on mount, never again

    const selectedDrone = selectedId ? drones.get(selectedId) : undefined;
    const selectedInfo = selectedDrone
        ? droneMissionInfo(selectedDrone.id, pendingPlan, acceptedRoutes)
        : {};

    return (
        <MapContainer
            className="drone-map"
            center={[39.0, -77.2]}
            zoom={11}
            style={{ height: "100%", width: "100%", background: "#0a0a0a" }}
        >
            <MapResizeFix />
            <TileLayer
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>'
                url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
                subdomains="abcd"
                maxZoom={20}
            />
            <DroneMarkerLayer
                pendingPlan={pendingPlan}
                acceptedRoutes={acceptedRoutes}
                onSelect={setSelectedId}
            />

            <PlanOverlayLayer
                pendingPlan={pendingPlan}
                acceptedRoutes={acceptedRoutes}
                activeObjectives={activeObjectives}
                drones={drones}
            />

            <EntityLayerMemo />
            <EntityPlacementMemo />
            <EntityCreateFormMemo />
            <EntityInspectorMemo />

            {selectedDrone && (
                <DroneInspector
                    drone={selectedDrone}
                    role={missionVisualStatus(selectedDrone.id, pendingPlan, acceptedRoutes)}
                    missionType={selectedInfo.missionType}
                    waypoint={selectedInfo.waypoint}
                    onClose={() => setSelectedId(null)}
                />
            )}

        </MapContainer>
    );
}
