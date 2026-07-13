import { useEffect, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { Circle, useMap } from "react-leaflet";

import { createTrack, createWaypoint, createZone } from "../api";
import { useEntityUiStore } from "../store/entityUiStore";
import type { Affiliation, TrackDomain, ZoneType } from "../types/entity";

const PANEL_OFFSET = { x: 14, y: -10 };
const DEFAULT_RADIUS_M = 500;

const AFFILIATIONS: Affiliation[] = ["FRIENDLY", "HOSTILE", "UNKNOWN"];
const DOMAINS: TrackDomain[] = ["AERIAL", "GROUND"];
const ZONE_TYPES: ZoneType[] = ["RESTRICTED", "PATROL"];

const KIND_TITLE: Record<string, string> = {
    track: "New Track",
    waypoint: "New Waypoint",
    zone: "New Zone",
};

// Popover at click point. Zones get a live radius preview. WS adds the entity after create.
export default function EntityCreateForm() {
    const map = useMap();
    const draft = useEntityUiStore((s) => s.draft);
    const clearDraft = useEntityUiStore((s) => s.clearDraft);

    const [, setVersion] = useState(0);
    const [name, setName] = useState("");
    const [affiliation, setAffiliation] = useState<Affiliation>("UNKNOWN");
    const [domain, setDomain] = useState<TrackDomain>("AERIAL");
    const [zoneType, setZoneType] = useState<ZoneType>("RESTRICTED");
    const [radius, setRadius] = useState(DEFAULT_RADIUS_M);
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    // Reset on new draft.
    useEffect(() => {
        setName("");
        setAffiliation("UNKNOWN");
        setDomain("AERIAL");
        setZoneType("RESTRICTED");
        setRadius(DEFAULT_RADIUS_M);
        setError(null);
        setSaving(false);
    }, [draft?.kind, draft?.lat, draft?.lng]);

    // Stay anchored on pan/zoom.
    useEffect(() => {
        if (!draft) return;
        const bump = () => setVersion((v) => v + 1);
        map.on("move zoom resize viewreset", bump);
        return () => {
            map.off("move zoom resize viewreset", bump);
        };
    }, [map, draft]);

    if (!draft) return null;

    const anchor = map.latLngToContainerPoint([draft.lat, draft.lng]);
    const pos = { x: anchor.x + PANEL_OFFSET.x, y: anchor.y + PANEL_OFFSET.y };

    const submit = async (e: FormEvent) => {
        e.preventDefault();
        setError(null);
        setSaving(true);
        try {
            if (draft.kind === "track") {
                await createTrack({
                    name: name.trim(),
                    affiliation,
                    domain,
                    latitude: draft.lat,
                    longitude: draft.lng,
                });
            } else if (draft.kind === "waypoint") {
                await createWaypoint({
                    name: name.trim(),
                    latitude: draft.lat,
                    longitude: draft.lng,
                });
            } else {
                await createZone({
                    name: name.trim(),
                    type: zoneType,
                    shape: "CIRCLE",
                    centerLatitude: draft.lat,
                    centerLongitude: draft.lng,
                    radiusMeters: radius,
                });
            }
            clearDraft();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Create failed");
            setSaving(false);
        }
    };

    const panel = (
        <form
            className="entity-create-form"
            style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
            onSubmit={submit}
        >
            <div className="entity-panel__header">
                <span className="entity-panel__title">{KIND_TITLE[draft.kind]}</span>
                <button
                    type="button"
                    className="entity-panel__close"
                    aria-label="Cancel"
                    onClick={clearDraft}
                >
                    &times;
                </button>
            </div>

            <div className="entity-panel__body">
                <label className="entity-panel__field">
                    <span className="entity-panel__label">Name</span>
                    <input
                        className="entity-panel__input"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="Label"
                        autoFocus
                    />
                </label>

                {draft.kind === "track" && (
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

                {draft.kind === "zone" && (
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
                    </>
                )}

                {error && <div className="entity-panel__error">{error}</div>}

                <div className="entity-panel__actions">
                    <button
                        type="button"
                        className="c2-btn c2-btn--ghost"
                        onClick={clearDraft}
                    >
                        Cancel
                    </button>
                    <button
                        type="submit"
                        className="c2-btn c2-btn--accept"
                        disabled={saving}
                    >
                        {saving ? "Saving…" : "Create"}
                    </button>
                </div>
            </div>
        </form>
    );

    return (
        <>
            {draft.kind === "zone" && radius > 0 && (
                <Circle
                    center={[draft.lat, draft.lng]}
                    radius={radius}
                    pathOptions={{
                        color: zoneType === "RESTRICTED" ? "#e05858" : "#38c6d1",
                        weight: 1.5,
                        dashArray: "6 8",
                        fillOpacity: 0.08,
                    }}
                />
            )}
            {createPortal(panel, map.getContainer())}
        </>
    );
}
