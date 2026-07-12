import { useEffect } from "react";
import { useMap, useMapEvents } from "react-leaflet";

import { useEntityUiStore } from "../store/entityUiStore";

/**
 * Bridges the armed placement tool to the map: a background click while a tool is
 * armed opens a create draft at that point. Also shows a crosshair cursor while
 * arming and cancels the tool/draft on Escape. Renders nothing.
 */
export default function EntityPlacement() {
    const map = useMap();
    const activeTool = useEntityUiStore((s) => s.activeTool);
    const startDraft = useEntityUiStore((s) => s.startDraft);
    const clearTool = useEntityUiStore((s) => s.clearTool);
    const clearDraft = useEntityUiStore((s) => s.clearDraft);

    useMapEvents({
        click(e) {
            if (!activeTool) return;
            startDraft({ kind: activeTool, lat: e.latlng.lat, lng: e.latlng.lng });
        },
    });

    // Crosshair cursor on the map container while a tool is armed.
    useEffect(() => {
        const container = map.getContainer();
        if (activeTool) {
            container.classList.add("placing");
        } else {
            container.classList.remove("placing");
        }
        return () => {
            container.classList.remove("placing");
        };
    }, [map, activeTool]);

    // Escape backs out of placement / an open draft.
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape") {
                clearTool();
                clearDraft();
            }
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [clearTool, clearDraft]);

    return null;
}
