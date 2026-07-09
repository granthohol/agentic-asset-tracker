import L from "leaflet";

import type { DroneStatus } from "../types/drone";

const STATUS_COLOR: Record<DroneStatus, string> = {
    ACTIVE: "#3ad17a",
    LOW_BATTERY: "#ffb020",
    OFFLINE: "#6b7280",
};

const iconCache = new Map<DroneStatus, L.DivIcon>();

/** Compact diamond marker tinted by drone status — no default Leaflet pin. */
export function droneIcon(status: DroneStatus): L.DivIcon {
    const cached = iconCache.get(status);
    if (cached) return cached;

    const color = STATUS_COLOR[status] ?? STATUS_COLOR.ACTIVE;
    const icon = L.divIcon({
        className: "drone-marker",
        html: `
            <span class="drone-marker__wrap" style="--drone-color:${color}">
                <span class="drone-marker__diamond"></span>
                <span class="drone-marker__core"></span>
            </span>
        `,
        iconSize: [18, 18],
        iconAnchor: [9, 9],
        popupAnchor: [0, -10],
    });
    iconCache.set(status, icon);
    return icon;
}
