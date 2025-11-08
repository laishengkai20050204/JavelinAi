// src/features/clientTools/storage.ts
import type { SavedToolBundle } from "./types";

const LSK = "javelin.tools.v1";

export function listSavedTools(): SavedToolBundle[] {
    try { return JSON.parse(localStorage.getItem(LSK) || "[]"); }
    catch { return []; }
}

export function saveToolBundle(b: SavedToolBundle) {
    const all = listSavedTools();
    const i = all.findIndex(x => x.id === b.id);
    if (i >= 0) all[i] = b; else all.push(b);
    localStorage.setItem(LSK, JSON.stringify(all));
}

export function removeTool(id: string) {
    const next = listSavedTools().filter(x => x.id !== id);
    localStorage.setItem(LSK, JSON.stringify(next));
}
