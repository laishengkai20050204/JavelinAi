// src/features/clientTools/compile.ts
import { NodeEditor } from "rete";
import { AreaPlugin } from "rete-area-plugin";
import { Hooks } from "../toolflow/nodes/basic";

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
        if (n.type === "GetVar") {
            // ✅ 先看 controls.name，再兜底 data.name
            const nameFromControls = (n as any)?.controls?.name;
            const nameFromData = (n as any)?.data?.name;
            const raw = String(
                typeof nameFromControls === "string" ? nameFromControls :
                    typeof nameFromData === "string" ? nameFromData : ""
            ).trim();

            if (raw.startsWith("args.") && raw.length > 5) {
                const key = raw.slice(5);

                // ✅ 类型同理：controls.valueType 优先，兜底 data.valueType
                const vtCtrl = (n as any)?.controls?.valueType;
                const vtData = (n as any)?.data?.valueType;
                const t =
                    typeof vtCtrl === "string" ? vtCtrl :
                        typeof vtData === "string" ? vtData : "string"; // "string" | "number" | "boolean" | "json"

                names.set(key, t);
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

    // 你之前是把所有收集到的参数都设为必填；保持原行为即可
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
        try { await autoRegisterAllNodesAsync(); } catch {}

        const engine = new DataEngine();
        const editor = new NodeEditor<Schemes>();
        const host = typeof document !== "undefined" && document.createElement
            ? document.createElement("div")
            : ({} as any);
        const area = new AreaPlugin<Schemes, AreaExtra>(host as HTMLElement);
        editor.use(area);
        editor.use(engine);

        await importGraph(graph, editor, area);

        const startId = findStartNodeId(graph);

        // ====== 关键：注入 getVar（支持点号路径）======
        const scope = {
            ...args,           // 直接把实参摊平
            args,              // 同时也挂到 args.* 下面
            userId: ctx.userId,
            conversationId: ctx.conversationId
        };
        const getByPath = (obj: any, path: string) =>
            path.split(".").reduce((acc, k) => (acc == null ? undefined : acc[k]), obj);

        const prevGetVar = Hooks.getVar;
        Hooks.getVar = (name: string) => {
            const v = getByPath(scope, name);
            console.log("[Hooks.getVar]", name, "=>", v);
            return v;
        };
        // ============================================

        const initialVars = { args, userId: ctx.userId, conversationId: ctx.conversationId };
        console.log("[ClientTool.execute] initialVars =", initialVars);

// 兜底：给全局挂一份，防止模块实例不一致导致 Hooks 失效
        ;(globalThis as any).__JAVELIN_SCOPE__ = initialVars;

        const opts: any = {
            initialVars,
            vars: initialVars,       // 一些 runner / api 会从这里读
            context: initialVars     // 有的实现从 context 取
        };
        console.log("[ClientTool.execute] runFromStart opts =", opts);

        try {
            const out = await (runFromStart as any)(editor, engine, opts);
            console.log("[ClientTool.execute] runFromStart out =", out);
            try { (area as any)?.destroy?.(); } catch {}
            return { result: out?.result ?? out ?? null };
        } finally {
            Hooks.getVar = prevGetVar;
        }

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
