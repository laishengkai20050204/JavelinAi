// src/features/clientTools/compile.ts
import { NodeEditor } from "rete";
import { AreaPlugin } from "rete-area-plugin";

import {
    Engine as DataEngine,
    importGraph,
    type Schemes,
    type AreaExtra
} from "../toolflow/nodes/basic";

import { runFromStart } from "../toolflow/core/runner";
import { autoRegisterAllNodesAsync } from "../toolflow/core/autoNodes";

import type {
    SavedToolBundle,
    ClientTool,
    ClientToolManifestFn
} from "./types";

/** 从图自动推断参数：收集 GetVar("args.xxx")，默认 string，可在节点 data.valueType 中声明类型 */
function inferParamsSchemaFromGraph(graph: any): any {
    const names = new Map<string, string>(); // name -> type
    for (const n of graph?.nodes ?? []) {
        if (n.type === "GetVar" && typeof n.data?.name === "string") {
            const k = n.data.name.trim();
            if (k.startsWith("args.")) {
                const key = k.slice(5);
                const t = typeof n.data?.valueType === "string" ? n.data.valueType : "string";
                names.set(key, t); // "string" | "number" | "boolean" | "json"
            }
        }
    }
    const properties: Record<string, any> = {};
    for (const [k, t] of names) {
        properties[k] =
            t === "number"  ? { type: "number" }  :
                t === "boolean" ? { type: "boolean" } :
                    t === "json"    ? { type: "object" }  :
                        { type: "string" };
    }
    return { type: "object", properties, required: [...names.keys()] };
}

function defaultReturnSchema(): any {
    return { type: "object", properties: { result: {} } };
}

function findStartNodeId(graph: any): string {
    const start = (graph?.nodes ?? []).find((n: any) => n?.type === "Start");
    if (!start?.id) throw new Error("No Start node found in graph");
    return String(start.id);
}

/** 编译图为“function manifest + execute()” */
export async function compileGraphToClientTool(
    graph: any,
    meta: { name: string; description: string; version?: string }
): Promise<ClientTool> {
    const manifest: ClientToolManifestFn = {
        name: meta.name,
        description: meta.description,
        parameters: inferParamsSchemaFromGraph(graph),
        "x-execTarget": "client",
        "x-returns": defaultReturnSchema()
    };

    const execute = async (
        args: Record<string, any>,
        ctx: { userId: string; conversationId: string }
    ) => {
        // 确保节点注册（一次即可，重复调用安全）
        try { await autoRegisterAllNodesAsync(); } catch {}

        // 头less Editor/Area（不挂 UI）
        const engine = new DataEngine();
        const editor = new NodeEditor<Schemes>();
        const host = typeof document !== "undefined" && document.createElement
            ? document.createElement("div")
            : ({} as any);
        const area = new AreaPlugin<Schemes, AreaExtra>(host as HTMLElement);
        editor.use(area);
        editor.use(engine);

        // 正确的三参导入： (graph, editor, area)
        await importGraph(graph, editor, area);

        const startId = findStartNodeId(graph);
        const initialVars = { args, userId: ctx.userId, conversationId: ctx.conversationId };

        // 你的 runner 是 3 参：engine + startNodeId + opts
        const out = await (runFromStart as any)(engine, startId, { initialVars });
        const result = out?.result ?? out ?? null;

        try { (area as any)?.destroy?.(); } catch {}
        return { result };
    };

    return { manifest, execute };
}

export async function buildAndSaveToolBundle(
    graph: any,
    meta: { name: string; description: string; version?: string }
): Promise<SavedToolBundle> {
    const id =
        typeof crypto !== "undefined" && (crypto as any)?.randomUUID
            ? (crypto as any).randomUUID()
            : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

    const tool = await compileGraphToClientTool(graph, meta);
    return {
        id,
        meta: { ...meta, createdAt: Date.now(), updatedAt: Date.now() },
        graph,
        manifest: tool.manifest
    };
}
