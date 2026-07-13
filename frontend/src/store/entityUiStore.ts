import { create } from 'zustand';

import type { EntityKind } from '../types/entity';

/** Selected entity (kind + id). Live data comes from entityStore. */
export interface EntitySelection {
    kind: EntityKind;
    id: string;
}

/** Pending create: click location + entity kind. */
export interface EntityDraft {
    kind: EntityKind;
    lat: number;
    lng: number;
}

interface EntityUiState {
    /** Armed placement tool; null = select/inspect. */
    activeTool: EntityKind | null;
    /** Inspected entity, or null. */
    selected: EntitySelection | null;
    /** Open create form, or null. */
    draft: EntityDraft | null;

    setTool: (kind: EntityKind) => void;
    clearTool: () => void;
    select: (selection: EntitySelection) => void;
    clearSelection: () => void;
    startDraft: (draft: EntityDraft) => void;
    clearDraft: () => void;
}

// Tool mode, selection, draft. Separate from entityStore (WS mirror). Only one mode at a time.
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
