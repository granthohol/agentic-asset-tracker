import { useEntityUiStore } from "../store/entityUiStore";
import type { EntityKind } from "../types/entity";

interface ToolDef {
    kind: EntityKind;
    label: string;
}

const TOOLS: ToolDef[] = [
    { kind: "track", label: "Track" },
    { kind: "waypoint", label: "Waypoint" },
    { kind: "zone", label: "Zone" },
];

// Floating toolbar: arm a tool to click-to-place, Escape to disarm.
export default function EntityToolbar() {
    const activeTool = useEntityUiStore((s) => s.activeTool);
    const setTool = useEntityUiStore((s) => s.setTool);
    const clearTool = useEntityUiStore((s) => s.clearTool);

    return (
        <div className="entity-toolbar" role="toolbar" aria-label="Place map entities">
            <span className="entity-toolbar__label">Place</span>
            {TOOLS.map((tool) => (
                <button
                    key={tool.kind}
                    type="button"
                    className={`c2-btn${activeTool === tool.kind ? " c2-btn--active" : ""}`}
                    onClick={() => (activeTool === tool.kind ? clearTool() : setTool(tool.kind))}
                    aria-pressed={activeTool === tool.kind}
                >
                    {tool.label}
                </button>
            ))}
        </div>
    );
}
