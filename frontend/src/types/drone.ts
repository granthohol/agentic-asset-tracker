export type DroneStatus = "ACTIVE" | "LOW_BATTERY" | "OFFLINE";

export interface Drone{
    id: string;
    latitude: number;
    longitude: number;
    batteryLevel: number;
    status: DroneStatus;
}

