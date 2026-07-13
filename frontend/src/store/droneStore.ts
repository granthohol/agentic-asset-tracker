import { create } from 'zustand';

import type { Drone } from '../types/drone';

interface DroneState {
    drones: Map<string, Drone>;
    /** Full fleet from a SNAPSHOT frame (connect/reconnect). */
    applySnapshot: (list: Drone[]) => void;
    /** Merge a BATCH frame into the fleet. */
    applyBatch: (list: Drone[]) => void;
}

// Telemetry lives outside React. WS writes here; the rAF marker layer reads via getState().
// Overlays/inspector still subscribe normally.
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
