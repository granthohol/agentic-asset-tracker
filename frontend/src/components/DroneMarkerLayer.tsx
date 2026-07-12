import { useEffect, useRef } from 'react';
import { useMap } from 'react-leaflet';
import L from 'leaflet';

import type { ExecutionPlan } from '../types/plan';
import type { AcceptedRoute } from '../types/route';
import { useDroneStore } from '../store/droneStore';
import { droneIcon, type MissionVisualStatus } from './droneIcons';
import { missionVisualStatus } from '../utils/missionStatus';

interface DroneMarkerLayerProps {
    pendingPlan: ExecutionPlan | null;
    acceptedRoutes: AcceptedRoute[];
    onSelect: (droneId: string) => void;
}

interface MarkerEntry {
    marker: L.Marker;
    mission: MissionVisualStatus;
}

/**
 * Phase 4 render loop: draw the fleet imperatively so 1,000 markers at 20 Hz never touch
 * React reconciliation. A single requestAnimationFrame tick reads the latest drone
 * snapshot from the store, creating each L.Marker once and calling setLatLng per frame
 * (and setIcon only when the mission color actually changes). Mission context is read
 * through refs so prop changes don't tear down and rebuild the loop.
 */
export default function DroneMarkerLayer({
    pendingPlan,
    acceptedRoutes,
    onSelect,
}: DroneMarkerLayerProps) {
    const map = useMap();

    const planRef = useRef(pendingPlan);
    const routesRef = useRef(acceptedRoutes);
    const onSelectRef = useRef(onSelect);
    planRef.current = pendingPlan;
    routesRef.current = acceptedRoutes;
    onSelectRef.current = onSelect;

    useEffect(() => {
        const layer = L.layerGroup().addTo(map);
        const markers = new Map<string, MarkerEntry>();
        let raf = 0;

        const tick = () => {
            const drones = useDroneStore.getState().drones;
            const seen = new Set<string>();

            drones.forEach((drone) => {
                seen.add(drone.id);
                const mission = missionVisualStatus(drone.id, planRef.current, routesRef.current);
                const entry = markers.get(drone.id);
                if (!entry) {
                    const marker = L.marker([drone.latitude, drone.longitude], {
                        icon: droneIcon(mission),
                    });
                    marker.on('click', () => onSelectRef.current(drone.id));
                    marker.addTo(layer);
                    markers.set(drone.id, { marker, mission });
                    return;
                }
                entry.marker.setLatLng([drone.latitude, drone.longitude]);
                if (entry.mission !== mission) {
                    entry.marker.setIcon(droneIcon(mission));
                    entry.mission = mission;
                }
            });

            // Drop markers for drones that disappeared from the feed.
            markers.forEach((entry, id) => {
                if (!seen.has(id)) {
                    layer.removeLayer(entry.marker);
                    markers.delete(id);
                }
            });

            raf = requestAnimationFrame(tick);
        };

        raf = requestAnimationFrame(tick);

        return () => {
            cancelAnimationFrame(raf);
            layer.remove();
            markers.clear();
        };
    }, [map]);

    return null;
}
