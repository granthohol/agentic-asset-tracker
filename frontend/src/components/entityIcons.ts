import L from "leaflet";

import type { Affiliation, TrackDomain } from "../types/entity";

// Color = affiliation, shape = domain (diamond vs square).
const AFFILIATION_COLOR: Record<Affiliation, string> = {
    HOSTILE: "#e05858",
    FRIENDLY: "#3aad6e",
    UNKNOWN: "#e0a92f",
};

const WAYPOINT_COLOR = "#c7d0da";

const trackIconCache = new Map<string, L.DivIcon>();
let waypointIconCacheEntry: L.DivIcon | null = null;

/** Contact marker: diamond (aerial) or square (ground). */
export function trackIcon(affiliation: Affiliation, domain: TrackDomain): L.DivIcon {
    const key = `${affiliation}-${domain}`;
    const cached = trackIconCache.get(key);
    if (cached) return cached;

    const color = AFFILIATION_COLOR[affiliation] ?? AFFILIATION_COLOR.UNKNOWN;
    const shapeClass = domain === "GROUND" ? "track-marker__glyph--ground" : "track-marker__glyph--aerial";

    const icon = L.divIcon({
        className: "track-marker",
        html:
            `<span class="track-marker__wrap">` +
            `<span class="track-marker__glyph ${shapeClass}" style="background:${color};box-shadow:0 0 10px ${color}b3"></span>` +
            `</span>`,
        iconSize: [18, 18],
        iconAnchor: [9, 9],
        popupAnchor: [0, -10],
    });
    trackIconCache.set(key, icon);
    return icon;
}

export function affiliationColor(affiliation: Affiliation): string {
    return AFFILIATION_COLOR[affiliation] ?? AFFILIATION_COLOR.UNKNOWN;
}

let zoneHandleIconCacheEntry: L.DivIcon | null = null;

/** Drag handle for a selected circle zone. */
export function zoneHandleIcon(): L.DivIcon {
    if (zoneHandleIconCacheEntry) return zoneHandleIconCacheEntry;
    zoneHandleIconCacheEntry = L.divIcon({
        className: "zone-handle",
        html: `<span class="zone-handle__dot"></span>`,
        iconSize: [14, 14],
        iconAnchor: [7, 7],
    });
    return zoneHandleIconCacheEntry;
}

/** POI dot (label on hover). */
export function waypointIcon(): L.DivIcon {
    if (waypointIconCacheEntry) return waypointIconCacheEntry;
    waypointIconCacheEntry = L.divIcon({
        className: "waypoint-marker",
        html: `<span class="waypoint-marker__dot" style="background:${WAYPOINT_COLOR}"></span>`,
        iconSize: [12, 12],
        iconAnchor: [6, 6],
        popupAnchor: [0, -8],
    });
    return waypointIconCacheEntry;
}
