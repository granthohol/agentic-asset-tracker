import { create } from 'zustand';

import type { EntityKind } from '../types/entity';

/** A selected entity reference (kind + id); the live data is read from entityStore. */
export interface EntitySelection {
    kind: EntityKind;
    id: string;
}

/** A pending create: where the user clicked and what kind of entity to place. */
export interface EntityDraft {
    kind: EntityKind;
    lat: number;
    lng: number;
}

interface EntityUiState {
    /** Armed placement tool (map clicks create this kind); null = select/inspect mode. */
    activeTool: EntityKind | null;
    /** Currently inspected entity, or null. */
    selected: EntitySelection | null;
    /** In-progress create (drives the inline create form), or null. */
    draft: EntityDraft | null;

    setTool: (kind: EntityKind) => void;
    clearTool: () => void;
    select: (selection: EntitySelection) => void;
    clearSelection: () => void;
    startDraft: (draft: EntityDraft) => void;
    clearDraft: () => void;
}

/**
 * Transient map-interaction state (tool mode, selection, in-progress create).
 * Kept separate from entityStore, which is a pure mirror of the /ws/entities feed.
 * Arming a tool clears any selection/draft, and vice versa, so only one mode is
 * ever active.
 */
export const useEntityUiStore = create<EntityUiState>((set) => ({
    activeTool: null,
    selected: null,
    draft: null,

    setTool: (kind) => set({ activeTool: kind, selected: null, draft: null }),
    clearTool: () => set({ activeTool: null }),
    select: (selection) => set({ selected: selection, activeTool: null, draft: null }),
    clearSelection: () => set({ selected: null }),
    startDraft: (draft) => set({ draft, activeTool: null, selected: null }),
    clearDraft: () => set({ draft: null }),
}));
