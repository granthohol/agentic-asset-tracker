import { create } from 'zustand';

import type {
    EntityKind,
    EntitySnapshotMessage,
    MapWaypoint,
    Track,
    Zone,
} from '../types/entity';

interface EntityState {
    tracks: Map<string, Track>;
    waypoints: Map<string, MapWaypoint>;
    zones: Map<string, Zone>;
    /** Replace all entities from a WS snapshot (sent on connect/reconnect). */
    applySnapshot: (snap: EntitySnapshotMessage) => void;
    /** Insert or update a single entity from an entityUpsert frame. */
    applyUpsert: (kind: EntityKind, entity: Track | MapWaypoint | Zone) => void;
    /** Remove a single entity from an entityDelete frame. */
    applyDelete: (kind: EntityKind, id: string) => void;
}

function toMap<T extends { id: string }>(items: T[]): Map<string, T> {
    return new Map(items.map((item) => [item.id, item]));
}

export const useEntityStore = create<EntityState>((set) => ({
    tracks: new Map(),
    waypoints: new Map(),
    zones: new Map(),

    applySnapshot: (snap) =>
        set({
            tracks: toMap(snap.tracks),
            waypoints: toMap(snap.waypoints),
            zones: toMap(snap.zones),
        }),

    applyUpsert: (kind, entity) =>
        set((state) => {
            if (kind === 'track') {
                const tracks = new Map(state.tracks);
                tracks.set(entity.id, entity as Track);
                return { tracks };
            }
            if (kind === 'waypoint') {
                const waypoints = new Map(state.waypoints);
                waypoints.set(entity.id, entity as MapWaypoint);
                return { waypoints };
            }
            const zones = new Map(state.zones);
            zones.set(entity.id, entity as Zone);
            return { zones };
        }),

    applyDelete: (kind, id) =>
        set((state) => {
            if (kind === 'track') {
                const tracks = new Map(state.tracks);
                tracks.delete(id);
                return { tracks };
            }
            if (kind === 'waypoint') {
                const waypoints = new Map(state.waypoints);
                waypoints.delete(id);
                return { waypoints };
            }
            const zones = new Map(state.zones);
            zones.delete(id);
            return { zones };
        }),
}));
