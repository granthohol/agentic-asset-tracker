import type { Drone, DroneStatus } from "../types/drone";
import { assettracker } from "../proto/telemetry.js";

const { TelemetryFrame, FrameType, DroneStatus: ProtoStatus } = assettracker.telemetry;

export interface DecodedFrame {
    kind: "snapshot" | "batch";
    drones: Drone[];
}

function toStatus(status: number): DroneStatus {
    switch (status) {
        case ProtoStatus.LOW_BATTERY:
            return "LOW_BATTERY";
        case ProtoStatus.OFFLINE:
            return "OFFLINE";
        default:
            return "ACTIVE";
    }
}

// Binary /ws/drones frame → Drone[]. SNAPSHOT = full fleet; BATCH = tick updates.
export function decodeTelemetryFrame(buffer: ArrayBuffer): DecodedFrame {
    const frame = TelemetryFrame.decode(new Uint8Array(buffer));
    const drones: Drone[] = (frame.drones ?? []).map((d) => ({
        id: d.id ?? "",
        latitude: d.lat ?? 0,
        longitude: d.lng ?? 0,
        batteryLevel: d.battery ?? 0,
        status: toStatus(d.status ?? 0),
    }));
    return {
        kind: frame.type === FrameType.SNAPSHOT ? "snapshot" : "batch",
        drones,
    };
}
