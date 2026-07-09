/** AOI / disturbance circle kept on the map for the life of an approved mission. */
export interface MissionObjective {
    id: string;
    name: string;
    centerLatitude: number;
    centerLongitude: number;
    radiusMeters: number;
}
