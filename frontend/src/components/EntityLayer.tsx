import { Fragment } from "react";
import type { DragEndEvent } from "leaflet";
import { Circle, CircleMarker, Marker, Polygon, Tooltip } from "react-leaflet";

import { updateTrack, updateWaypoint, updateZone } from "../api";
import { useEntityStore } from "../store/entityStore";
import { useEntityUiStore } from "../store/entityUiStore";
import type { Zone, ZoneType } from "../types/entity";
import { trackIcon, waypointIcon, zoneHandleIcon } from "./entityIcons";

const ZONE_COLOR: Record<ZoneType, string> = {
    RESTRICTED: "#e05858",
    PATROL: "#38c6d1",
};

const DASH = "6 8";
const HIGHLIGHT_COLOR = "#e6ebf0";

/** Zip parallel lat/lng arrays into [lat, lng] pairs. */
function polygonPositions(zone: Zone): [number, number][] {
    const count = Math.min(zone.vertexLats.length, zone.vertexLngs.length);
    const positions: [number, number][] = [];
    for (let i = 0; i < count; i += 1) {
        positions.push([zone.vertexLats[i], zone.vertexLngs[i]]);
    }
    return positions;
}

// Tracks/waypoints/zones from store. Click to select, drag to move. REST writes, WS refreshes.
export default function EntityLayer() {
    const tracks = useEntityStore((s) => s.tracks);
    const waypoints = useEntityStore((s) => s.waypoints);
    const zones = useEntityStore((s) => s.zones);

    const activeTool = useEntityUiStore((s) => s.activeTool);
    const selected = useEntityUiStore((s) => s.selected);
    const select = useEntityUiStore((s) => s.select);

    // No select/drag while placing.
    const interactive = activeTool === null;

    const isSelected = (kind: string, id: string) =>
        selected?.kind === kind && selected.id === id;

    return (
        <>
            {Array.from(zones.values()).map((zone) => {
                const color = ZONE_COLOR[zone.type] ?? ZONE_COLOR.RESTRICTED;
                const selectedZone = isSelected("zone", zone.id);
                const pathOptions = {
                    color: selectedZone ? HIGHLIGHT_COLOR : color,
                    weight: selectedZone ? 2.5 : 1.5,
                    dashArray: DASH,
                    fillColor: color,
                    fillOpacity: selectedZone ? 0.16 : 0.08,
                };
                const zoneEvents = interactive
                    ? { click: () => select({ kind: "zone", id: zone.id }) }
                    : {};

                if (
                    zone.shape === "CIRCLE" &&
                    zone.centerLatitude != null &&
                    zone.centerLongitude != null &&
                    zone.radiusMeters != null
                ) {
                    return (
                        <Fragment key={zone.id}>
                            <Circle
                                center={[zone.centerLatitude, zone.centerLongitude]}
                                radius={zone.radiusMeters}
                                pathOptions={pathOptions}
                                eventHandlers={zoneEvents}
                            >
                                <Tooltip direction="top">{zone.name}</Tooltip>
                            </Circle>
                            {selectedZone && (
                                <Marker
                                    position={[zone.centerLatitude, zone.centerLongitude]}
                                    icon={zoneHandleIcon()}
                                    draggable
                                    eventHandlers={{
                                        dragend: (e: DragEndEvent) => {
                                            const { lat, lng } = e.target.getLatLng();
                                            void updateZone(zone.id, {
                                                name: zone.name,
                                                type: zone.type,
                                                shape: "CIRCLE",
                                                centerLatitude: lat,
                                                centerLongitude: lng,
                                                radiusMeters: zone.radiusMeters ?? undefined,
                                            }).catch(() => {});
                                        },
                                    }}
                                />
                            )}
                        </Fragment>
                    );
                }

                const positions = polygonPositions(zone);
                if (positions.length < 3) return null;
                return (
                    <Polygon
                        key={zone.id}
                        positions={positions}
                        pathOptions={pathOptions}
                        eventHandlers={zoneEvents}
                    >
                        <Tooltip direction="top">{zone.name}</Tooltip>
                    </Polygon>
                );
            })}

            {Array.from(tracks.values()).map((track) => (
                <Fragment key={track.id}>
                    {isSelected("track", track.id) && (
                        <CircleMarker
                            center={[track.latitude, track.longitude]}
                            radius={13}
                            pathOptions={{ color: HIGHLIGHT_COLOR, weight: 1.5, fillOpacity: 0 }}
                        />
                    )}
                    <Marker
                        position={[track.latitude, track.longitude]}
                        icon={trackIcon(track.affiliation, track.domain)}
                        draggable={interactive}
                        eventHandlers={{
                            click: () => interactive && select({ kind: "track", id: track.id }),
                            dragend: (e: DragEndEvent) => {
                                const { lat, lng } = e.target.getLatLng();
                                void updateTrack(track.id, {
                                    name: track.name,
                                    affiliation: track.affiliation,
                                    domain: track.domain,
                                    latitude: lat,
                                    longitude: lng,
                                }).catch(() => {});
                            },
                        }}
                    >
                        <Tooltip direction="top">
                            {track.name} · {track.affiliation} · {track.domain}
                        </Tooltip>
                    </Marker>
                </Fragment>
            ))}

            {Array.from(waypoints.values()).map((waypoint) => (
                <Fragment key={waypoint.id}>
                    {isSelected("waypoint", waypoint.id) && (
                        <CircleMarker
                            center={[waypoint.latitude, waypoint.longitude]}
                            radius={12}
                            pathOptions={{ color: HIGHLIGHT_COLOR, weight: 1.5, fillOpacity: 0 }}
                        />
                    )}
                    <Marker
                        position={[waypoint.latitude, waypoint.longitude]}
                        icon={waypointIcon()}
                        draggable={interactive}
                        eventHandlers={{
                            click: () => interactive && select({ kind: "waypoint", id: waypoint.id }),
                            dragend: (e: DragEndEvent) => {
                                const { lat, lng } = e.target.getLatLng();
                                void updateWaypoint(waypoint.id, {
                                    name: waypoint.name,
                                    latitude: lat,
                                    longitude: lng,
                                }).catch(() => {});
                            },
                        }}
                    >
                        <Tooltip direction="top">{waypoint.name}</Tooltip>
                    </Marker>
                </Fragment>
            ))}
        </>
    );
}
