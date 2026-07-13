/** AOI circle for an approved mission. */
export interface MissionObjective {
    id: string;
    name: string;
    centerLatitude: number;
    centerLongitude: number;
    radiusMeters: number;
}
