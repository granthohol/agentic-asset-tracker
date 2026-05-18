import L from 'leaflet';

import icon from 'leaflet/dist/images/marker-icon.png';
import iconShadow from 'leaflet/dist/images/marker-shadow.png';
import iconRetina from 'leaflet/dist/images/marker-icon-2x.png';

import {MapContainer, TileLayer, Marker, Popup} from 'react-leaflet';
import { useState, useEffect } from 'react';

import type { Drone } from '../types/drone'

delete (L.Icon.Default.prototype as any)._getIconUrl;
L.Icon.Default.mergeOptions({
  iconUrl: icon,
  iconRetinaUrl: iconRetina,
  shadowUrl: iconShadow,
});

type TelemetryMessage =
| { type: 'snapshot'; drones: Drone[] }
| { type: 'droneUpdate'; drone: Drone };

const WEBSOCKET_URL = "ws://localhost:8080/ws/drones";

export default function DroneMap() {
    const [, setConnectionStatus] = useState<'Connecting' | 'Open' | 'Closed' | 'Error'>('Connecting');
    const [drones, setDrones] = useState<Map<string, Drone>>(new Map());
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

            ws.onopen = () => {
                console.log('WebSocket Connection Established');
                setConnectionStatus('Open');
                reconnectAttempt = 0; // healthy connection resets backoff
            };

            ws.onmessage = (event: MessageEvent) => {
                // event is a raw string frame. Parse into JSON
                const message = JSON.parse(event.data) as TelemetryMessage;

                // Application level multiplexer
                switch (message.type) {
                    case 'snapshot':
                        const nextMap = new Map(message.drones.map((drone) => [drone.id, drone]));
                        setDrones(nextMap);
                        break;
                    case 'droneUpdate':
                        setDrones(prevMap => {
                            const nextMap = new Map(prevMap);
                            nextMap.set(message.drone.id, message.drone);
                            return nextMap;
                        });
                        break;
                    default:
                        console.log("Unhandled type in WebSocket TelemetryMessage");

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

    return (
        <MapContainer
            center={[39.0, -77.2]}
            zoom = {11}
            style = {{ height: "100vh", width: "100%" }}
        > 
            <TileLayer
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            /> 
            {Array.from(drones.values())
                .sort((a, b) => a.id.localeCompare(b.id))
                .map((drone) => (
                <Marker
                    key = {drone.id}
                    position = {[drone.latitude, drone.longitude]}
                >
                    <Popup>
                        id: {drone.id} <br />
                        Battery Level: {drone.batteryLevel} <br />
                        Status: {drone.status}
                    </Popup>
                </Marker>
            ))}

        </MapContainer>
    );
}