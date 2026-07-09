/** A waypoint route kept on the map after the user approves a plan. */
export interface AcceptedRoute {
    id: string;
    droneId: string;
    targetLat: number;
    targetLng: number;
    missionType?: string;
}
