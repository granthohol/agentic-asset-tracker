import {
    useEffect,
    useRef,
    useState,
    type PointerEvent as ReactPointerEvent,
} from "react";
import { createPortal } from "react-dom";
import { useMap } from "react-leaflet";

import {
    deleteTrack,
    deleteWaypoint,
    deleteZone,
    updateTrack,
    updateWaypoint,
    updateZone,
} from "../api";
import { useEntityStore } from "../store/entityStore";
import { useEntityUiStore } from "../store/entityUiStore";
import type {
    Affiliation,
    MapWaypoint,
    Track,
    TrackDomain,
    Zone,
    ZoneRequest,
    ZoneType,
} from "../types/entity";

const FOLLOW_OFFSET = { x: 16, y: -12 };

const AFFILIATIONS: Affiliation[] = ["FRIENDLY", "HOSTILE", "UNKNOWN"];
const DOMAINS: TrackDomain[] = ["AERIAL", "GROUND"];
const ZONE_TYPES: ZoneType[] = ["RESTRICTED", "PATROL"];

function fmtCoord(value: number): string {
    return value.toFixed(5);
}

/** Representative anchor point for a zone (circle center, else first vertex). */
function zoneAnchor(zone: Zone): [number, number] | null {
    if (zone.centerLatitude != null && zone.centerLongitude != null) {
        return [zone.centerLatitude, zone.centerLongitude];
    }
    if (zone.vertexLats.length > 0 && zone.vertexLngs.length > 0) {
        return [zone.vertexLats[0], zone.vertexLngs[0]];
    }
    return null;
}

function zoneVertices(zone: Zone): [number, number][] {
    const count = Math.min(zone.vertexLats.length, zone.vertexLngs.length);
    const out: [number, number][] = [];
    for (let i = 0; i < count; i += 1) out.push([zone.vertexLats[i], zone.vertexLngs[i]]);
    return out;
}

/**
 * Inspector for a selected entity: view fields, toggle Edit to change them (PUT),
 * or Delete. Follows the entity anchor until dragged, then pins (mirrors
 * DroneInspector). Reads live entity data from the store by the selection ref.
 */
export default function EntityInspector() {
    const map = useMap();
    const selected = useEntityUiStore((s) => s.selected);
    const clearSelection = useEntityUiStore((s) => s.clearSelection);
    const tracks = useEntityStore((s) => s.tracks);
    const waypoints = useEntityStore((s) => s.waypoints);
    const zones = useEntityStore((s) => s.zones);

    const [, setVersion] = useState(0);
    const [pinned, setPinned] = useState<{ x: number; y: number } | null>(null);
    const [dragging, setDragging] = useState(false);
    const dragRef = useRef<{ pointerId: number; startX: number; startY: number; originX: number; originY: number } | null>(null);

    const [editing, setEditing] = useState(false);
    const [name, setName] = useState("");
    const [affiliation, setAffiliation] = useState<Affiliation>("UNKNOWN");
    const [domain, setDomain] = useState<TrackDomain>("AERIAL");
    const [zoneType, setZoneType] = useState<ZoneType>("RESTRICTED");
    const [radius, setRadius] = useState(0);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const kind = selected?.kind ?? null;
    const id = selected?.id ?? null;

    const track: Track | undefined = kind === "track" && id ? tracks.get(id) : undefined;
    const waypoint: MapWaypoint | undefined = kind === "waypoint" && id ? waypoints.get(id) : undefined;
    const zone: Zone | undefined = kind === "zone" && id ? zones.get(id) : undefined;
    const entity = track ?? waypoint ?? zone;

    // New selection: reset positioning + seed the edit fields from the entity.
    useEffect(() => {
        setPinned(null);
        setEditing(false);
        setError(null);
        setBusy(false);
        if (track) {
            setName(track.name);
            setAffiliation(track.affiliation);
            setDomain(track.domain);
        } else if (waypoint) {
            setName(waypoint.name);
        } else if (zone) {
            setName(zone.name);
            setZoneType(zone.type);
            setRadius(zone.radiusMeters ?? 0);
        }
        // Only re-seed when the selection identity changes.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [kind, id]);

    // Re-anchor while following as the map view changes.
    useEffect(() => {
        if (pinned) return;
        const bump = () => setVersion((v) => v + 1);
        map.on("move zoom resize viewreset", bump);
        return () => {
            map.off("move zoom resize viewreset", bump);
        };
    }, [map, pinned]);

    if (!selected) return null;
    // Selection points at an entity that no longer exists (e.g. deleted elsewhere).
    if (!entity) return null;

    const anchorLatLng: [number, number] | null = track
        ? [track.latitude, track.longitude]
        : waypoint
          ? [waypoint.latitude, waypoint.longitude]
          : zone
            ? zoneAnchor(zone)
            : null;
    if (!anchorLatLng) return null;

    const anchor = map.latLngToContainerPoint(anchorLatLng);
    const pos = pinned ?? { x: anchor.x + FOLLOW_OFFSET.x, y: anchor.y + FOLLOW_OFFSET.y };

    const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
        if (e.button !== 0) return;
        e.preventDefault();
        e.stopPropagation();
        e.currentTarget.setPointerCapture(e.pointerId);
        map.dragging.disable();
        dragRef.current = {
            pointerId: e.pointerId,
            startX: e.clientX,
            startY: e.clientY,
            originX: pos.x,
            originY: pos.y,
        };
        setDragging(true);
    };

    const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
        const drag = dragRef.current;
        if (!drag || drag.pointerId !== e.pointerId) return;
        e.preventDefault();
        setPinned({
            x: drag.originX + (e.clientX - drag.startX),
            y: drag.originY + (e.clientY - drag.startY),
        });
    };

    const endDrag = (e: ReactPointerEvent<HTMLDivElement>) => {
        const drag = dragRef.current;
        if (!drag || drag.pointerId !== e.pointerId) return;
        dragRef.current = null;
        setDragging(false);
        map.dragging.enable();
        if (e.currentTarget.hasPointerCapture(e.pointerId)) {
            e.currentTarget.releasePointerCapture(e.pointerId);
        }
    };

    const title = track ? "Track" : waypoint ? "Waypoint" : "Zone";

    const save = async () => {
        setError(null);
        setBusy(true);
        try {
            if (track) {
                await updateTrack(track.id, {
                    name: name.trim(),
                    affiliation,
                    domain,
                    latitude: track.latitude,
                    longitude: track.longitude,
                });
            } else if (waypoint) {
                await updateWaypoint(waypoint.id, {
                    name: name.trim(),
                    latitude: waypoint.latitude,
                    longitude: waypoint.longitude,
                });
            } else if (zone) {
                const req: ZoneRequest =
                    zone.shape === "CIRCLE"
                        ? {
                              name: name.trim(),
                              type: zoneType,
                              shape: "CIRCLE",
                              centerLatitude: zone.centerLatitude ?? undefined,
                              centerLongitude: zone.centerLongitude ?? undefined,
                              radiusMeters: radius,
                          }
                        : {
                              name: name.trim(),
                              type: zoneType,
                              shape: "POLYGON",
                              vertices: zoneVertices(zone),
                          };
                await updateZone(zone.id, req);
            }
            setEditing(false);
            setBusy(false);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Save failed");
            setBusy(false);
        }
    };

    const remove = async () => {
        setError(null);
        setBusy(true);
        try {
            if (track) await deleteTrack(track.id);
            else if (waypoint) await deleteWaypoint(waypoint.id);
            else if (zone) await deleteZone(zone.id);
            clearSelection();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Delete failed");
            setBusy(false);
        }
    };

    const panel = (
        <div
            className={`entity-inspector${dragging ? " entity-inspector--dragging" : ""}`}
            style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
        >
            <div
                className="entity-panel__header"
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
            >
                <span className="entity-panel__title">{title}</span>
                <button
                    type="button"
                    className="entity-panel__close"
                    aria-label="Close inspector"
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={clearSelection}
                >
                    &times;
                </button>
            </div>

            <div className="entity-panel__body">
                {editing ? (
                    <>
                        <label className="entity-panel__field">
                            <span className="entity-panel__label">Name</span>
                            <input
                                className="entity-panel__input"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                            />
                        </label>
                        {track && (
                            <>
                                <label className="entity-panel__field">
                                    <span className="entity-panel__label">Affiliation</span>
                                    <select
                                        className="entity-panel__select"
                                        value={affiliation}
                                        onChange={(e) => setAffiliation(e.target.value as Affiliation)}
                                    >
                                        {AFFILIATIONS.map((a) => (
                                            <option key={a} value={a}>{a}</option>
                                        ))}
                                    </select>
                                </label>
                                <label className="entity-panel__field">
                                    <span className="entity-panel__label">Domain</span>
                                    <select
                                        className="entity-panel__select"
                                        value={domain}
                                        onChange={(e) => setDomain(e.target.value as TrackDomain)}
                                    >
                                        {DOMAINS.map((d) => (
                                            <option key={d} value={d}>{d}</option>
                                        ))}
                                    </select>
                                </label>
                            </>
                        )}
                        {zone && (
                            <>
                                <label className="entity-panel__field">
                                    <span className="entity-panel__label">Type</span>
                                    <select
                                        className="entity-panel__select"
                                        value={zoneType}
                                        onChange={(e) => setZoneType(e.target.value as ZoneType)}
                                    >
                                        {ZONE_TYPES.map((t) => (
                                            <option key={t} value={t}>{t}</option>
                                        ))}
                                    </select>
                                </label>
                                {zone.shape === "CIRCLE" && (
                                    <label className="entity-panel__field">
                                        <span className="entity-panel__label">Radius (m)</span>
                                        <input
                                            className="entity-panel__input"
                                            type="number"
                                            min={1}
                                            value={radius}
                                            onChange={(e) => setRadius(Number(e.target.value))}
                                        />
                                    </label>
                                )}
                            </>
                        )}
                    </>
                ) : (
                    <>
                        <div className="entity-panel__row">
                            <span className="entity-panel__label">Name</span>
                            <span className="entity-panel__value">{entity.name}</span>
                        </div>
                        {track && (
                            <>
                                <div className="entity-panel__row">
                                    <span className="entity-panel__label">Affiliation</span>
                                    <span className="entity-panel__value">{track.affiliation}</span>
                                </div>
                                <div className="entity-panel__row">
                                    <span className="entity-panel__label">Domain</span>
                                    <span className="entity-panel__value">{track.domain}</span>
                                </div>
                            </>
                        )}
                        {zone && (
                            <>
                                <div className="entity-panel__row">
                                    <span className="entity-panel__label">Type</span>
                                    <span className="entity-panel__value">{zone.type}</span>
                                </div>
                                <div className="entity-panel__row">
                                    <span className="entity-panel__label">Shape</span>
                                    <span className="entity-panel__value">{zone.shape}</span>
                                </div>
                                {zone.shape === "CIRCLE" && zone.radiusMeters != null && (
                                    <div className="entity-panel__row">
                                        <span className="entity-panel__label">Radius</span>
                                        <span className="entity-panel__value">{Math.round(zone.radiusMeters)} m</span>
                                    </div>
                                )}
                            </>
                        )}
                        {anchorLatLng && (
                            <div className="entity-panel__row">
                                <span className="entity-panel__label">Position</span>
                                <span className="entity-panel__value">
                                    {fmtCoord(anchorLatLng[0])}, {fmtCoord(anchorLatLng[1])}
                                </span>
                            </div>
                        )}
                    </>
                )}

                {error && <div className="entity-panel__error">{error}</div>}

                <div className="entity-panel__actions">
                    {editing ? (
                        <>
                            <button
                                type="button"
                                className="c2-btn c2-btn--ghost"
                                onClick={() => setEditing(false)}
                                disabled={busy}
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                className="c2-btn c2-btn--accept"
                                onClick={save}
                                disabled={busy}
                            >
                                {busy ? "Saving…" : "Save"}
                            </button>
                        </>
                    ) : (
                        <>
                            <button
                                type="button"
                                className="c2-btn c2-btn--abort"
                                onClick={remove}
                                disabled={busy}
                            >
                                Delete
                            </button>
                            <button
                                type="button"
                                className="c2-btn"
                                onClick={() => setEditing(true)}
                                disabled={busy}
                            >
                                Edit
                            </button>
                        </>
                    )}
                </div>
            </div>
        </div>
    );

    return createPortal(panel, map.getContainer());
}
