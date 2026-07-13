// Backend map entities. Field names match /api and /ws/entities JSON.

export type Affiliation = "FRIENDLY" | "HOSTILE" | "UNKNOWN";
export type TrackDomain = "AERIAL" | "GROUND";
export type ZoneType = "RESTRICTED" | "PATROL";
export type ZoneShape = "CIRCLE" | "POLYGON";

/** Tracked contact (friendly/hostile/unknown). */
export interface Track {
    id: string;
    name: string;
    affiliation: Affiliation;
    domain: TrackDomain;
    latitude: number;
    longitude: number;
}

/** Labeled map POI. Not the same as a drone tasking waypoint. */
export interface MapWaypoint {
    id: string;
    name: string;
    latitude: number;
    longitude: number;
}

/** CIRCLE: center + radius. POLYGON: parallel vertexLats/vertexLngs. */
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

/** Kind tag on /ws/entities upsert/delete frames. */
export type EntityKind = "track" | "waypoint" | "zone";

// Write DTOs. Zone writes use vertices: [lat,lng][]; reads use vertexLats/vertexLngs.

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
