import L from "leaflet";

/** Mission role for marker color (not battery status). */
export type MissionVisualStatus = "idle" | "proposed" | "executing";

const MISSION_COLOR: Record<MissionVisualStatus, string> = {
    idle: "#6b7280",
    proposed: "#ff8a1f",
    executing: "#3ad17a",
};

const iconCache = new Map<MissionVisualStatus, L.DivIcon>();

/** Diamond marker, tinted by mission role. */
export function droneIcon(missionStatus: MissionVisualStatus): L.DivIcon {
    const key = MISSION_COLOR[missionStatus] ? missionStatus : "idle";
    const cached = iconCache.get(key);
    if (cached) return cached;

    const color = MISSION_COLOR[key];
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

export function missionColor(missionStatus: MissionVisualStatus): string {
    return MISSION_COLOR[missionStatus] ?? MISSION_COLOR.idle;
}
