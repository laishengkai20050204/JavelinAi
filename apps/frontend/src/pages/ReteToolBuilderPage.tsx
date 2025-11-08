// apps/frontend/src/pages/ReteToolBuilderPage.tsx
import React from "react";
import { createRoot } from "react-dom/client";

import { NodeEditor } from "rete";
import { AreaPlugin, AreaExtensions } from "rete-area-plugin";
import { ConnectionPlugin, Presets as ConnectionPresets } from "rete-connection-plugin";
import { ReactPlugin, Presets, useRete } from "rete-react-plugin";
import { ContextMenuPlugin, Presets as ContextMenuPresets } from "rete-context-menu-plugin";

import {
  Engine as DataEngine,
  exportGraph,
  importGraph,
  type Schemes,
  type AreaExtra,
  type NodeU
} from "../features/toolflow/nodes/basic";
import { autoRegisterAllNodesAsync } from "../features/toolflow/core/autoNodes";
import { runFromStart } from "../features/toolflow/core/runner";
import { cleanupDanglingConnections, type GraphJSON } from "../features/toolflow/core/graph";
import { getContextMenuItems, getNodeDefsByCategory } from "../features/toolflow/core/nodeRegistry";
import type { ToolNodeCategory, ToolNodeDefinition } from "../features/toolflow/core/nodeRegistry";

const LSK = "javelin.rete.graph.v1";

// Minimal block palette item for drag preview
type BlockDef = { name: string; label: string };

// Preview helpers
const PREVIEW_CACHE = new Map<string, { inputs: string[]; outputs: string[]; controls: string[] }>();
function extractNamesFromMapOrObj(maybe: any): string[] {
  if (!maybe) return [];
  try { if (typeof maybe.keys === 'function') return Array.from(maybe.keys()); } catch {}
  if (typeof maybe === 'object') return Object.keys(maybe);
  return [];
}
function extractControlKeys(maybeControls: any): string[] {
  const keys: string[] = [];
  if (!maybeControls) return keys;
  try {
    if (typeof maybeControls.forEach === 'function') {
      maybeControls.forEach((_v: any, k: any) => keys.push(String(k)));
      return keys;
    }
  } catch {}
  try {
    if (typeof maybeControls.entries === 'function') {
      for (const [k] of maybeControls.entries()) keys.push(String(k));
      return keys;
    }
  } catch {}
  if (typeof maybeControls === 'object') keys.push(...Object.keys(maybeControls));
  return keys;
}
function getNodePreview(factories: Map<string, () => any>, name: string) {
  if (PREVIEW_CACHE.has(name)) return PREVIEW_CACHE.get(name)!;
  const fn = factories.get(name);
  if (!fn) return { inputs: [], outputs: [], controls: [] };
  try {
    const inst: any = fn();
    const inputs = extractNamesFromMapOrObj(inst?.inputs);
    const outputs = extractNamesFromMapOrObj(inst?.outputs);
    const controls = extractControlKeys(inst?.controls);
    const res = { inputs, outputs, controls };
    PREVIEW_CACHE.set(name, res);
    return res;
  } catch {
    return { inputs: [], outputs: [], controls: [] };
  }
}

function BlockItem({ name, label, onDragStart }: { name: string; label: string; onDragStart: (name: string) => (e: React.DragEvent) => void }) {
  return (
    <div
      draggable
      onDragStart={onDragStart(name)}
      className="flex items-center justify-between rounded-md border px-2 py-1 text-xs hover:bg-neutral-50 dark:hover:bg-neutral-700"
      title={name}
    >
      <span className="truncate max-w-[200px]">{label}</span>
      <span className="text-[10px] text-neutral-500 ml-2">{name}</span>
    </div>
  );
}

async function createEditor(container: HTMLElement) {
  const editor = new NodeEditor<Schemes>();
  const area = new AreaPlugin<Schemes, AreaExtra>(container);
  const render = new ReactPlugin<Schemes, AreaExtra>({ createRoot });
  render.addPreset(Presets.classic.setup());
  render.addPreset(Presets.contextMenu.setup());
  editor.use(area);
  area.use(render);

  const connection = new ConnectionPlugin<Schemes, AreaExtra>();
  connection.addPreset(ConnectionPresets.classic.setup());
  area.use(connection);

  const engine = new DataEngine();
  editor.use(engine);

  // restore
  const raw = localStorage.getItem(LSK);
  if (raw) {
    try {
      const g = JSON.parse(raw) as GraphJSON;
      await importGraph(g, editor, area);
    } catch (e) {
      console.warn("Restore graph failed:", e);
      AreaExtensions.zoomAt(area, editor.getNodes());
    }
  } else {
    AreaExtensions.zoomAt(area, editor.getNodes());
  }

  return { destroy: () => area.destroy(), editor, area, engine };
}

export default function ReteToolBuilderPage() {
  const [ref, api] = useRete(createEditor);
  const [result, setResult] = React.useState<string>("");
  const [panelOpen, setPanelOpen] = React.useState(false);
  const [menuOpen, setMenuOpen] = React.useState(false);
  const menuRef = React.useRef<HTMLDivElement | null>(null);
  const cmRef = React.useRef<ContextMenuPlugin<Schemes> | null>(null);
  const lastNodeRef = React.useRef<any>(null);

  const [search, setSearch] = React.useState("");
  const [factories, setFactories] = React.useState(new Map<string, () => any>());
  const [categories, setCategories] = React.useState<ToolNodeCategory[]>([]);
  const [activeTab, setActiveTab] = React.useState<ToolNodeCategory | null>(null);
  const [catMap, setCatMap] = React.useState<Map<ToolNodeCategory, ToolNodeDefinition[]>>(new Map());

  const getNodeView = React.useCallback(
    (id: any) => {
      if (!api) return null as any;
      const areaAny: any = api.area as any;
      return areaAny?.nodeViews?.get?.(id) || areaAny?.area?.nodeViews?.get?.(id) || null;
    },
    [api]
  );

  const screenToAreaPoint = React.useCallback(
    (host: HTMLElement | null, clientX: number, clientY: number) => {
      const rect = host?.getBoundingClientRect();
      const ax = rect ? clientX - rect.left : clientX;
      const ay = rect ? clientY - rect.top : clientY;
      const areaAny = api?.area as any;
      const transform =
        areaAny?.area?.transform ||
        areaAny?.transform ||
        { x: 0, y: 0, k: 1, scale: 1 };
      const k = typeof transform.k === "number" ? transform.k : (transform.scale ?? 1);
      return {
        x: (ax - (transform.x ?? 0)) / k,
        y: (ay - (transform.y ?? 0)) / k
      };
    },
    [api]
  );

  const getNodeViewPos = React.useCallback(
    (id: any): { x: number; y: number } => {
      const view: any = getNodeView(id);
      return (view?.position as any) ?? { x: 0, y: 0 };
    },
    [getNodeView]
  );

  const findNearestNode = React.useCallback(
    (clientX: number, clientY: number, host: HTMLElement | null) => {
      if (!api) return null;
      const pt = screenToAreaPoint(host, clientX, clientY);
      let best: any = null;
      let bestD = Infinity;
      for (const n of api.editor.getNodes()) {
        const p = getNodeViewPos(n.id);
        const d = Math.hypot((p?.x ?? 0) - pt.x, (p?.y ?? 0) - pt.y);
        if (d < bestD) { bestD = d; best = n; }
      }
      return bestD <= 40 ? best : null;
    },
    [api, screenToAreaPoint, getNodeViewPos]
  );

  const deleteCurrentNode = React.useCallback(async () => {
    if (!api) return;
    const node = api.editor.getNodes().find((n) => n.id === lastNodeRef.current);
    if (!node) return;
    try {
      await (api.editor as any).removeNode?.(node.id);
      lastNodeRef.current = null;
    } catch {
      try {
        (api.editor as any).removeNode?.(node.id);
        lastNodeRef.current = null;
      } catch {}
    }
  }, [api]);

  React.useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === "Delete" || ev.key === "Backspace") {
        ev.preventDefault();
        deleteCurrentNode();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [deleteCurrentNode]);

  React.useEffect(() => {
    const onMove = (ev: MouseEvent) => {
      const host = ref.current;
      if (!host) return;
      const n = findNearestNode(ev.clientX, ev.clientY, host);
      if (n) lastNodeRef.current = n.id;
    };
    document.addEventListener("mousemove", onMove);
    return () => document.removeEventListener("mousemove", onMove);
  }, [findNearestNode]);

  React.useEffect(() => {
    if (!api) return;
    const areaPipe = (api.area as any)?.addPipe?.((ctx: any) => {
      if (ctx && ctx.type === "nodepointerdown" && ctx.data?.id !== undefined) {
        lastNodeRef.current = ctx.data.id;
      }
      if (ctx && ctx.type === "nodeselect" && ctx.data?.id !== undefined) {
        lastNodeRef.current = ctx.data.id;
      }
      return ctx;
    });
    return () => {
      try { areaPipe?.(); } catch {}
    };
  }, [api]);

  // install nodes and dynamic context menu once api ready
  React.useEffect(() => {
    let mounted = true;
    (async () => {
      await autoRegisterAllNodesAsync();
      const byCat = getNodeDefsByCategory();
      if (!mounted) return;
      // factories by type
      const fac = new Map<string, () => any>();
      for (const defs of byCat.values()) {
        for (const d of defs) fac.set(d.type, () => d.create());
      }
      setFactories(fac);
      // categories in fixed order if present
      const ORDER: ToolNodeCategory[] = ['control','variable','literal','logic','collection','io','other'];
      const present = ORDER.filter((c) => byCat.has(c));
      setCategories(present);
      setCatMap(byCat);
      setActiveTab(present[0] ?? null);
      // menu plugin installed separately
    })();
    return () => { mounted = false; };
  }, [api]);

  // drag from palette
  const onBlockDragStart = (name: string) => (e: React.DragEvent) => {
    e.dataTransfer.setData("application/x-node-type", name);
    e.dataTransfer.effectAllowed = "copy";
  };

  // canvas dnd
  const onCanvasDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "copy";
  };
  const onCanvasDrop = async (e: React.DragEvent) => {
    if (!api) return;
    const { editor, area } = api;
    const name = e.dataTransfer.getData("application/x-node-type");
    const factory = factories.get(name);
    if (!factory) return;
    const node = factory();
    await editor.addNode(node);
    const pos = screenToAreaPoint(ref.current, e.clientX, e.clientY);
    await area.translate(node.id, pos);
  };

  // file ops
  const save = React.useCallback(() => {
    if (!api) return;
    const { editor, area } = api;
    cleanupDanglingConnections(editor);
    const g = exportGraph(editor, area);
    localStorage.setItem(LSK, JSON.stringify(g));
  }, [api]);

  const load = React.useCallback(async () => {
    if (!api) return;
    const { editor, area } = api;
    const raw = localStorage.getItem(LSK);
    if (!raw) return;
    const g = JSON.parse(raw) as GraphJSON;
    await importGraph(g, editor, area);
  }, [api]);

  const exportJson = React.useCallback(async () => {
    if (!api) return;
    const { editor, area } = api;
    const g = exportGraph(editor as any, area as any);
    try {
      await navigator.clipboard.writeText(JSON.stringify(g, null, 2));
      alert("Copied to clipboard");
    } catch {
      const blob = new Blob([JSON.stringify(g, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank");
    }
  }, [api]);

  const importFromClipboard = React.useCallback(async () => {
    if (!api) return;
    const { editor, area } = api;
    try {
      const text = await navigator.clipboard.readText();
      const g = JSON.parse(text);
      await importGraph(g, editor as any, area as any);
    } catch (e: any) {
      alert("Import failed: " + (e?.message || String(e)));
    }
  }, [api]);

  const resetCanvas = React.useCallback(async () => {
    if (!api) return;
    const { editor, area } = api;
    for (const c of [...editor.getConnections()]) {
      try { await (editor as any).removeConnection?.(c); }
      catch { try { await (editor as any).removeConnection?.((c as any).id); } catch { } }
    }
    for (const n of [...editor.getNodes()]) {
      let ok = false;
      try { await (editor as any).removeNode?.(n.id as any); ok = true; } catch { }
      if (!ok) { try { await (editor as any).removeNode?.(n as any); } catch { } }
    }
    try { (area as any)?.area?.update?.(); } catch { }
    try { (area as any)?.update?.(); } catch { }
    try { AreaExtensions.zoomAt(area, editor.getNodes()); } catch { }
  }, [api]);

  React.useEffect(() => {
    if (!api || cmRef.current) return;
    const items = getContextMenuItems().map(([t, f]) => [t, () => f() as NodeU]) as Array<[string, () => NodeU]>;
    const base = ContextMenuPresets.classic.setup(items as any);
    const withExtras = (context: any, plugin: ContextMenuPlugin<Schemes>) => {
      const col = base(context, plugin);
      const list = Array.isArray((col as any)?.list) ? [...(col as any).list] : [];
      if (context === "root") {
        list.unshift(
          { label: "Clear Canvas", key: "clear-canvas", handler: async () => { await resetCanvas(); } },
          { label: "Export JSON", key: "export-json", handler: async () => { await exportJson(); } },
          { label: "Import from Clipboard", key: "import-json", handler: async () => { await importFromClipboard(); } }
        );
        (col as any).searchBar = true;
      } else if (context && typeof context === "object" && "id" in context) {
        const node = context as any;
        list.unshift({
          label: "Delete Node",
          key: "delete-node",
          handler: async () => {
            try { await (api.editor as any).removeNode?.(node); }
            catch { try { await (api.editor as any).removeNode?.(node); } catch {} }
          }
        });
      }
      return { ...(col as any), list };
    };
    const plugin = new ContextMenuPlugin<Schemes>({ items: withExtras as any });
    api.area.use(plugin);
    cmRef.current = plugin;
    return () => {
      try { (api.area as any)?.remove?.(plugin); } catch {}
      cmRef.current = null;
    };
  }, [api, resetCanvas, exportJson, importFromClipboard]);


  const diagnose = React.useCallback(() => {
    if (!api) return;
    const { editor } = api;
    const nodes = editor.getNodes().map((n) => ({
      id: n.id,
      label: (n as any).label,
      inputs: [...(((n as any).inputs?.keys?.() as any) ?? [])],
      outputs: [...(((n as any).outputs?.keys?.() as any) ?? [])]
    }));
    const cons = editor.getConnections().map((c) => ({
      from: { id: c.source, port: c.sourceOutput },
      to: { id: c.target, port: c.targetInput }
    }));
    console.log("[DIAG]", { nodes, cons });
    alert(`DIAG\nnodes=${nodes.length}\nconns=${cons.length}`);
  }, [api]);

  const runFlow = React.useCallback(async () => {
    if (!api) return;
    const { editor, engine } = api;
    try {
      await (engine as any)?.reset?.();
      const r = await runFromStart(editor, engine);
      setResult(JSON.stringify(r, null, 2));
    } catch (e: any) {
      setResult("ERROR: " + (e?.message || String(e)));
    }
  }, [api]);

  // palette grouped items
  const paletteGrouped = React.useMemo(() => {
    const q = search.trim().toLowerCase();
    const result: Record<string, BlockDef[]> = {};
    for (const c of categories) {
      const defs = catMap.get(c) ?? [];
      const arr: BlockDef[] = defs
        .map((d) => ({ name: d.type, label: d.title }))
        .filter((b) => b.name.toLowerCase().includes(q) || b.label.toLowerCase().includes(q));
      if (arr.length) result[c] = arr;
    }
    return result;
  }, [categories, catMap, search]);

  // close menu on outside click
  React.useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!menuRef.current) return;
      if (!menuRef.current.contains(e.target as any)) setMenuOpen(false);
    };
    document.addEventListener("click", onDoc);
    return () => document.removeEventListener("click", onDoc);
  }, []);

  return (
    <div className="w-full h-[calc(100vh-120px)] min-h-[600px] relative bg-neutral-50 dark:bg-neutral-900">
      {/* Canvas */}
      <div
        ref={ref}
        className="absolute inset-0"
        onDragOver={onCanvasDragOver}
        onDrop={onCanvasDrop}
        onClickCapture={(e) => {
          const host = ref.current;
          const n = findNearestNode(e.clientX, e.clientY, host);
          if (n) lastNodeRef.current = n.id;
        }}
      />

      {/* Left palette (grouped) */}
      <div className="pointer-events-auto absolute left-4 top-4 z-20 w-[340px] max-h-[84vh] overflow-hidden rounded-2xl border bg-white/95 shadow-md backdrop-blur dark:border-neutral-700 dark:bg-neutral-800/90">
        {/* Tabs */}
        <div className="flex items-center gap-1 overflow-auto border-b px-2 py-1 text-xs dark:border-neutral-700">
          {categories.map((c) => (
            <button
              key={c}
              onClick={() => setActiveTab(c)}
              className={`whitespace-nowrap rounded-md px-2 py-1 ${activeTab === c ? 'bg-neutral-900 text-white dark:bg-neutral-100 dark:text-black' : 'hover:bg-neutral-100 dark:hover:bg-neutral-700'}`}
            >
              {c}
            </button>
          ))}
        </div>
        {/* Search */}
        <div className="flex items-center gap-2 px-2 py-2 text-xs">
          <input
            placeholder="Search nodes..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-md border px-2 py-1 text-xs dark:bg-neutral-800"
          />
        </div>
        {/* Blocks */}
        <div className="max-h-[66vh] overflow-auto p-2 space-y-2">
          {activeTab && (paletteGrouped[activeTab] ?? []).map((def) => {
            const p = getNodePreview(factories, def.name);
            return (
              <div key={def.name} draggable onDragStart={onBlockDragStart(def.name)} className="rounded-lg border p-2 text-xs hover:bg-neutral-50 dark:hover:bg-neutral-700">
                <div className="flex items-center justify-between">
                  <div className="font-medium">{def.label}</div>
                  <div className="text-[10px] text-neutral-500">{def.name}</div>
                </div>
                {(p.inputs.length > 0 || p.outputs.length > 0) && (
                  <div className="mt-1 grid grid-cols-2 gap-2">
                    <div>
                      <div className="text-[10px] text-neutral-500 mb-1">inputs</div>
                      <div className="flex flex-wrap gap-1">
                        {p.inputs.map((n) => (
                          <span key={n} className="rounded border px-1 py-0.5">{n}</span>
                        ))}
                      </div>
                    </div>
                    <div>
                      <div className="text-[10px] text-neutral-500 mb-1">outputs</div>
                      <div className="flex flex-wrap gap-1">
                        {p.outputs.map((n) => (
                          <span key={n} className="rounded border px-1 py-0.5">{n}</span>
                        ))}
                      </div>
                    </div>
                  </div>
                )}
                {p.controls.length > 0 && (
                  <div className="mt-1">
                    <div className="text-[10px] text-neutral-500 mb-1">controls</div>
                    <div className="flex flex-wrap gap-1">
                      {p.controls.map((n) => (
                        <span key={n} className="rounded border px-1 py-0.5">{n}</span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Top menu */}
      <div className="pointer-events-auto absolute left-[344px] top-4 z-20 flex items-center gap-2 rounded-2xl bg-white/90 p-2 text-sm shadow-md backdrop-blur dark:bg-neutral-800/80">
        <div className="relative" ref={menuRef}>
          <button
            onClick={(e) => { e.stopPropagation(); setMenuOpen((v) => !v); }}
            className="rounded-lg border px-3 py-1 hover:bg-neutral-50 dark:hover:bg-neutral-700"
          >
            Menu
          </button>
          {menuOpen && (
            <div className="absolute left-0 mt-2 w-[300px] rounded-xl border bg-white shadow-lg dark:border-neutral-700 dark:bg-neutral-800">
              <div className="px-3 py-2 text-[11px] font-medium text-neutral-500 dark:text-neutral-400">File</div>
              <div className="flex flex-col px-2 pb-2">
                <button onClick={() => { setMenuOpen(false); save(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover:bg-neutral-700">Save (Ctrl+S)</button>
                <button onClick={() => { setMenuOpen(false); load(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover:bg-neutral-700">Load (Ctrl+O)</button>
                <button onClick={() => { setMenuOpen(false); exportJson(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover:bg-neutral-700">Export JSON (copy)</button>
                <button onClick={() => { setMenuOpen(false); importFromClipboard(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover:bg-neutral-700">Import from clipboard</button>
                <button onClick={() => { setMenuOpen(false); resetCanvas(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover:bg-neutral-700">Clear canvas</button>
              </div>
              <div className="px-3 py-2 text-[11px] font-medium text-neutral-500 dark:text-neutral-400">Run</div>
              <div className="flex flex-col px-2 pb-2">
                <button onClick={() => { setMenuOpen(false); runFlow(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover bg-neutral-700">Run flow (Ctrl+Shift+Enter)</button>
                <button onClick={() => { setMenuOpen(false); diagnose(); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover bg-neutral-700">Diagnose (print nodes & links)</button>
              </div>
              <div className="px-3 py-2 text-[11px] font-medium text-neutral-500 dark:text-neutral-400">View</div>
              <div className="flex flex-col px-2 pb-3">
                <button onClick={() => { setMenuOpen(false); setPanelOpen((v) => !v); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover bg-neutral-700">{panelOpen ? "Hide result (Ctrl+`)" : "Show result (Ctrl+`)"}</button>
                <button onClick={() => { setMenuOpen(false); setResult(""); }} className="rounded-md px-3 py-1.5 text-left hover:bg-neutral-50 dark:hover bg-neutral-700">Clear result</button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Result panel */}
      <div
        className={`pointer-events-auto absolute right-4 top-4 z-10 max-h-[60vh] overflow-hidden rounded-2xl shadow-md backdrop-blur transition-all duration-200 ${panelOpen ? "w-[520px] bg-white/90 dark:bg-neutral-800/80" : "w-[44px] bg-white/70 dark:bg-neutral-800/60"}`}
        style={{ resize: panelOpen ? ("horizontal" as const) : "none" }}
      >
        <div className="flex items-center justify-between gap-2 border-b border-neutral-200/60 dark:border-neutral-700/60 px-2 py-1">
          <div className="flex items-center gap-2">
            <button onClick={() => setPanelOpen((v) => !v)} title={panelOpen ? "Hide result" : "Show result"} className="inline-flex h-6 w-6 items-center justify-center rounded-md hover:bg-neutral-100 dark:hover:bg-neutral-700">{panelOpen ? "<" : ">"}</button>
            {panelOpen && <span className="text-xs text-neutral-500">Results</span>}
          </div>
          {panelOpen && (
            <div className="flex items-center gap-2">
              <button onClick={() => setResult("")} className="rounded-md border px-2 py-0.5 text-xs hover:bg-neutral-50 dark:hover:bg-neutral-700">Clear</button>
            </div>
          )}
        </div>
        {panelOpen && (
          <pre className="max-h-[52vh] w-full overflow-auto p-3 text-xs">{result || "(No result yet)"}</pre>
        )}
      </div>
    </div>
  );
}
