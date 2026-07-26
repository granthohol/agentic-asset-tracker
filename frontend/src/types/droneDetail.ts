export interface SquadronRef {
    id: string;
    name: string;
    sectorId: string;
}

export interface DroneDetail {
    drone: {
        id: string;
        latitude: number;
        longitude: number;
        batteryLevel: number;
        status: string;
    };
    squadron: SquadronRef | null;
}
