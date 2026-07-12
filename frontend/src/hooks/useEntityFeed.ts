import { useEffect } from 'react';

import { useEntityStore } from '../store/entityStore';
import type { EntityMessage } from '../types/entity';

const WEBSOCKET_URL = 'ws://localhost:8080/ws/entities';

/**
 * Subscribes to the persistent-entity feed and mirrors it into the entity store.
 * Mount once (e.g. in App). Reconnect-with-backoff matches the drone telemetry
 * socket in DroneMap; the server replays a full snapshot on every (re)connect.
 */
export function useEntityFeed(): void {
    useEffect(() => {
        const { applySnapshot, applyUpsert, applyDelete } = useEntityStore.getState();

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
            reconnectTimer = window.setTimeout(() => {
                reconnectTimer = null;
                connect();
            }, delay);
        };

        const connect = () => {
            if (cancelled) return;
            ws = new WebSocket(WEBSOCKET_URL);

            ws.onopen = () => {
                reconnectAttempt = 0;
            };

            ws.onmessage = (event: MessageEvent) => {
                let message: EntityMessage;
                try {
                    message = JSON.parse(event.data) as EntityMessage;
                } catch {
                    console.warn('Unparseable entity frame', event.data);
                    return;
                }

                switch (message.type) {
                    case 'snapshot':
                        applySnapshot(message);
                        break;
                    case 'entityUpsert':
                        applyUpsert(message.kind, message.entity);
                        break;
                    case 'entityDelete':
                        applyDelete(message.kind, message.id);
                        break;
                    default:
                        console.log('Unhandled entity message type', message);
                }
            };

            ws.onclose = () => {
                scheduleReconnect();
            };

            ws.onerror = () => {
                // onclose follows onerror in browsers; reconnect is scheduled there.
            };
        };

        connect();

        return () => {
            cancelled = true;
            if (reconnectTimer !== null) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
                ws.close();
            }
        };
    }, []);
}
