import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { createPortal } from 'react-dom';
import { useMap } from 'react-leaflet';

import type { Drone } from '../types/drone';
import type { MissionVisualStatus } from './droneIcons';

interface DroneInspectorProps {
    drone: Drone;
    role: MissionVisualStatus;
    missionType?: string;
    waypoint?: { lat: number; lng: number };
    onClose: () => void;
}

// Nudge up-right of the marker.
const FOLLOW_OFFSET = { x: 16, y: -12 };

const ROLE_LABEL: Record<MissionVisualStatus, string> = {
    idle: 'Idle',
    proposed: 'Proposed',
    executing: 'Executing',
};

function fmtCoord(value: number): string {
    return value.toFixed(5);
}

// Floating read-out. Follows marker until you drag it away.
export default function DroneInspector({
    drone,
    role,
    missionType,
    waypoint,
    onClose,
}: DroneInspectorProps) {
    const map = useMap();
    // Bump on map move/zoom so follow anchor updates.
    const [, setVersion] = useState(0);
    // null = following; set = pinned at pixel coords.
    const [pinned, setPinned] = useState<{ x: number; y: number } | null>(null);
    const [dragging, setDragging] = useState(false);
    const dragRef = useRef<{ pointerId: number; startX: number; startY: number; originX: number; originY: number } | null>(null);

    // Follow anchor on pan/zoom.
    useEffect(() => {
        if (pinned) return;
        const bump = () => setVersion((v) => v + 1);
        map.on('move zoom resize viewreset', bump);
        return () => {
            map.off('move zoom resize viewreset', bump);
        };
    }, [map, pinned]);

    // New drone → follow again.
    useEffect(() => {
        setPinned(null);
    }, [drone.id]);

    const anchor = map.latLngToContainerPoint([drone.latitude, drone.longitude]);
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

    const batteryClass =
        drone.status === 'OFFLINE'
            ? ' drone-inspector__value--fault'
            : drone.status === 'LOW_BATTERY'
              ? ' drone-inspector__value--warn'
              : '';

    const panel = (
        <div
            className={`drone-inspector${dragging ? ' drone-inspector--dragging' : ''}`}
            style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
        >
            <div
                className="drone-inspector__header"
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
            >
                <span className="drone-inspector__id">{drone.id}</span>
                <button
                    type="button"
                    className="drone-inspector__close"
                    aria-label="Close inspector"
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={onClose}
                >
                    &times;
                </button>
            </div>

            <div className="drone-inspector__body">
                <div className="drone-inspector__row">
                    <span className="drone-inspector__label">Battery</span>
                    <span className={`drone-inspector__value${batteryClass}`}>
                        {drone.batteryLevel}% &middot; {drone.status.replace('_', ' ')}
                    </span>
                </div>
                <div className="drone-inspector__row">
                    <span className="drone-inspector__label">Role</span>
                    <span className={`drone-inspector__pill drone-inspector__pill--${role}`}>
                        {ROLE_LABEL[role]}
                        {missionType ? ` \u00b7 ${missionType}` : ''}
                    </span>
                </div>
                <div className="drone-inspector__row">
                    <span className="drone-inspector__label">Position</span>
                    <span className="drone-inspector__value">
                        {fmtCoord(drone.latitude)}, {fmtCoord(drone.longitude)}
                    </span>
                </div>
                <div className="drone-inspector__row">
                    <span className="drone-inspector__label">Waypoint</span>
                    <span className="drone-inspector__value">
                        {waypoint
                            ? `${fmtCoord(waypoint.lat)}, ${fmtCoord(waypoint.lng)}`
                            : 'Free flight'}
                    </span>
                </div>
            </div>
        </div>
    );

    return createPortal(panel, map.getContainer());
}
