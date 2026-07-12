import { create } from 'zustand';

import type { Drone } from '../types/drone';

interface DroneState {
    drones: Map<string, Drone>;
    /** Replace the whole fleet from a SNAPSHOT frame (sent on connect/reconnect). */
    applySnapshot: (list: Drone[]) => void;
    /** Merge a coalesced BATCH frame of updates into the fleet. */
    applyBatch: (list: Drone[]) => void;
}

/**
 * Phase 4: telemetry lives here, outside React's render path. The WebSocket writes decoded
 * frames straight into this store; the imperative rAF marker layer reads it via
 * {@link useDroneStore.getState} each tick and moves Leaflet markers without re-rendering
 * React. Lightweight overlays (plan lines, inspector, arrival detection) still subscribe.
 */
export const useDroneStore = create<DroneState>((set) => ({
    drones: new Map(),

    applySnapshot: (list) => set({ drones: new Map(list.map((d) => [d.id, d])) }),

    applyBatch: (list) =>
        set((state) => {
            const drones = new Map(state.drones);
            for (const drone of list) {
                drones.set(drone.id, drone);
            }
            return { drones };
        }),
}));
