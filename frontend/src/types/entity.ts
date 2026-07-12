// Persistent, backend-owned map entities (Phase 1 ontology). Field names mirror the
// wire JSON from /api/entities and the /ws/entities feed exactly.

export type Affiliation = "FRIENDLY" | "HOSTILE" | "UNKNOWN";
export type TrackDomain = "AERIAL" | "GROUND";
export type ZoneType = "RESTRICTED" | "PATROL";
export type ZoneShape = "CIRCLE" | "POLYGON";

/** A tracked contact (friendly/hostile/unknown). Static in Phase 1. */
export interface Track {
    id: string;
    name: string;
    affiliation: Affiliation;
    domain: TrackDomain;
    latitude: number;
    longitude: number;
}

/** A standalone, labeled point of interest. Distinct from a drone's tasking waypoint. */
export interface MapWaypoint {
    id: string;
    name: string;
    latitude: number;
    longitude: number;
}

/**
 * An area on the map. CIRCLE => center + radiusMeters set (vertex arrays empty).
 * POLYGON => vertexLats/vertexLngs are parallel arrays (center/radius null).
 */
export interface Zone {
    id: string;
    name: string;
    type: ZoneType;
    shape: ZoneShape;
    centerLatitude: number | null;
    centerLongitude: number | null;
    radiusMeters: number | null;
    vertexLats: number[];
    vertexLngs: number[];
}

/** Discriminator used by the /ws/entities upsert/delete frames. */
export type EntityKind = "track" | "waypoint" | "zone";

// ---- Write-request shapes (mirror the backend controller DTOs) ----
// Note: zone WRITES use `vertices: [lat,lng][]`, while zone READS use the
// parallel `vertexLats`/`vertexLngs` arrays (see Zone above).

export interface TrackRequest {
    name: string;
    affiliation: Affiliation;
    domain: TrackDomain;
    latitude: number;
    longitude: number;
}

export interface WaypointRequest {
    name: string;
    latitude: number;
    longitude: number;
}

export interface ZoneRequest {
    name: string;
    type: ZoneType;
    shape: ZoneShape;
    centerLatitude?: number;
    centerLongitude?: number;
    radiusMeters?: number;
    vertices?: [number, number][];
}

export interface EntitySnapshotMessage {
    type: "snapshot";
    tracks: Track[];
    waypoints: MapWaypoint[];
    zones: Zone[];
}

export interface EntityUpsertMessage {
    type: "entityUpsert";
    kind: EntityKind;
    entity: Track | MapWaypoint | Zone;
}

export interface EntityDeleteMessage {
    type: "entityDelete";
    kind: EntityKind;
    id: string;
}

export type EntityMessage =
    | EntitySnapshotMessage
    | EntityUpsertMessage
    | EntityDeleteMessage;
