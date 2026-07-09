import L from "leaflet";

import type { DroneStatus } from "../types/drone";

const STATUS_COLOR: Record<DroneStatus, string> = {
    ACTIVE: "#3ad17a",
    LOW_BATTERY: "#ffb020",
    OFFLINE: "#6b7280",
};

const iconCache = new Map<DroneStatus, L.DivIcon>();

/** Compact diamond marker tinted by drone status — color baked into HTML (not CSS vars). */
export function droneIcon(status: DroneStatus): L.DivIcon {
    const key = STATUS_COLOR[status] ? status : "ACTIVE";
    const cached = iconCache.get(key);
    if (cached) return cached;

    const color = STATUS_COLOR[key];
    const icon = L.divIcon({
        className: "drone-marker",
        html:
            `<span class="drone-marker__wrap">` +
            `<span class="drone-marker__diamond" style="background:${color};box-shadow:0 0 10px ${color}b3"></span>` +
            `<span class="drone-marker__core"></span>` +
            `</span>`,
        iconSize: [18, 18],
        iconAnchor: [9, 9],
        popupAnchor: [0, -10],
    });
    iconCache.set(key, icon);
    return icon;
}

export function statusColor(status: DroneStatus): string {
    return STATUS_COLOR[status] ?? STATUS_COLOR.ACTIVE;
}
