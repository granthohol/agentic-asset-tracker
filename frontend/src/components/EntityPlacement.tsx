import { useEffect } from "react";
import { useMap, useMapEvents } from "react-leaflet";

import { useEntityUiStore } from "../store/entityUiStore";

// Click map while a tool is armed → create draft. Escape cancels.
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

    // Crosshair while placing.
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

    // Escape backs out of tool or draft.
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
