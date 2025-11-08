// apps/frontend/src/features/toolflow/nodes/basic.ts
import { createRoot } from "react-dom/client";
import type { GetSchemes } from "rete";
import { NodeEditor, ClassicPreset } from "rete";
import { AreaPlugin, AreaExtensions } from "rete-area-plugin";
import { registerNode, getContextMenuItems, resolveNodeFactory } from "../core/nodeRegistry";
import {
    ConnectionPlugin,
    Presets as ConnectionPresets
} from "rete-connection-plugin";
import {
    ReactPlugin,
    Presets as ReactPresets
} from "rete-react-plugin";
import type { ReactArea2D } from "rete-react-plugin";
import { ContextMenuPlugin, Presets as CtxMenuPresets } from "rete-context-menu-plugin";
import type { ContextMenuExtra } from "rete-context-menu-plugin";
import { DataflowEngine } from "rete-engine";
import type { GraphJSON } from "../core/graph";

/* ============== Sockets（数据/控制） ============== */
export const stringSocket = new ClassicPreset.Socket("string");
export const numberSocket = new ClassicPreset.Socket("number");
export const booleanSocket = new ClassicPreset.Socket("boolean");
export const jsonSocket = new ClassicPreset.Socket("json");
export const controlSocket = new ClassicPreset.Socket("control"); // 控制线识别

/* ============== Runtime 钩子（由 runner 注入） ============== */
export type RuntimeHooks = {
    getVar?: (name: string) => any;
    httpFetch?: (req: {
        method: string;
        url: string;
        headers?: Record<string, string>;
        body?: any;
        responseType?: "json" | "text";
    }) => Promise<{ status: number; headers: Record<string, string>; body: any }>;
    callTool?: (name: string, args?: any) => Promise<any>;
};
export const Hooks: RuntimeHooks = {};

// side-effect 节点将执行结果写回这里，data() 再读出供数据口输出
export const OutputCache = new Map<any, Record<string, any>>();

/* ============== 基本节点实现 ============== */
export class StartNode extends ClassicPreset.Node {
    constructor() {
        super("Start");
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data() { return { next: 1 }; }
}
registerNode({
    type: "Start",
    title: "Start",
    category: "control",
    create: () => new StartNode()
});

export class EndNode extends ClassicPreset.Node {
    constructor() {
        super("End");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
    }
    data() { return {}; }
}
registerNode({
    type: "End",
    title: "End",
    category: "control",
    create: () => new EndNode()
});

export class IfNode extends ClassicPreset.Node {
    constructor() {
        super("If");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addInput("cond", new ClassicPreset.Input(booleanSocket, "cond"));
        this.addOutput("then", new ClassicPreset.Output(controlSocket, "then"));
        this.addOutput("else", new ClassicPreset.Output(controlSocket, "else"));
    }
    data(inputs: { cond?: any[] }) {
        const v = !!inputs.cond?.[0];
        return { cond: v, then: 1, else: 1 };
    }
}
registerNode({
    type: "If",
    title: "If",
    category: "control",
    create: () => new IfNode()
});

export class WhileNode extends ClassicPreset.Node {
    constructor() {
        super("While");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addInput("cond", new ClassicPreset.Input(booleanSocket, "cond"));
        this.addOutput("body", new ClassicPreset.Output(controlSocket, "body"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data(inputs: { cond?: any[] }) {
        const v = !!inputs.cond?.[0];
        return { cond: v, body: 1, next: 1 };
    }

}
registerNode({
    type: "While",
    title: "While",
    category: "control",
    create: () => new WhileNode()
});

export class NumberNode extends ClassicPreset.Node {
    constructor() {
        super("Number");
        this.addControl("value", new ClassicPreset.InputControl("number", { initial: 0 }));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data() {
        const ctrl: any = (this as any).controls?.get?.("value") ?? (this as any).controls?.["value"];
        const n = Number(ctrl?.value ?? 0);
        return { out: isFinite(n) ? n : 0 };
    }
}
registerNode({
    type: "Number",
    title: "Number",
    category: "literal",
    create: () => new NumberNode()
});

export class StringNode extends ClassicPreset.Node {
    constructor() {
        super("String");
        this.addControl("value", new ClassicPreset.InputControl("text", { initial: "Hello" }));
        this.addOutput("out", new ClassicPreset.Output(stringSocket, "out"));
    }
    data() {
        const ctrl: any = (this as any).controls?.get?.("value") ?? (this as any).controls?.["value"];
        return { out: String(ctrl?.value ?? "") };
    }
}
registerNode({
    type: "String",
    title: "String",
    category: "literal",
    create: () => new StringNode()
});

export class BooleanNode extends ClassicPreset.Node {
    constructor() {
        super("Boolean");
        this.addControl("value", new ClassicPreset.InputControl("text", { initial: "true" }));
        this.addOutput("out", new ClassicPreset.Output(booleanSocket, "out"));
    }
    data() {
        const ctrl: any = (this as any).controls?.get?.("value") ?? (this as any).controls?.["value"];
        const s = String(ctrl?.value ?? "").trim().toLowerCase();
        return { out: s === "true" || s === "1" || s === "yes" };
    }
}
registerNode({
    type: "Boolean",
    title: "Boolean",
    category: "literal",
    create: () => new BooleanNode()
});

export class AddNode extends ClassicPreset.Node {
    constructor() {
        super("Add");
        this.addInput("a", new ClassicPreset.Input(numberSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(numberSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: { a?: any[]; b?: any[] }) {
        const a = Number(inputs.a?.[0] ?? 0);
        const b = Number(inputs.b?.[0] ?? 0);
        return { out: a + b };
    }
}
registerNode({
    type: "Add",
    title: "Add",
    category: "logic",
    create: () => new AddNode()
});

export class PromptNode extends ClassicPreset.Node {
    constructor() {
        super("Prompt");
        this.addControl("template", new ClassicPreset.InputControl("text", { initial: "Hello, ${name}!" }));
        this.addInput("name", new ClassicPreset.Input(stringSocket, "name"));
        this.addOutput("out", new ClassicPreset.Output(stringSocket, "text"));
    }
    data(inputs: { name?: any[] }) {
        const tplCtrl: any = (this as any).controls?.get?.("template") ?? (this as any).controls?.["template"];
        const tpl = String(tplCtrl?.value ?? "");
        const name = inputs.name?.[0] ?? "";
        return { out: tpl.replaceAll("${name}", String(name)) };
    }
}
registerNode({
    type: "Prompt",
    title: "Prompt",
    category: "io",
    create: () => new PromptNode()
});

export class LoggerNode extends ClassicPreset.Node {
    constructor() {
        super("Logger");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addInput("text", new ClassicPreset.Input(stringSocket, "text"));
        this.addInput("json", new ClassicPreset.Input(jsonSocket, "json"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data(inputs: { text?: any[]; json?: any[] }) {
        const text = inputs.text?.[0];
        const json = inputs.json?.[0];
        if (text !== undefined) return { text: String(text), next: 1 };
        return { json, next: 1 };
    }
}
registerNode({
    type: "Logger",
    title: "Logger",
    category: "io",
    create: () => new LoggerNode()
});

export class OutputNode extends ClassicPreset.Node {
    constructor() {
        super("Output");
        this.addInput("text", new ClassicPreset.Input(stringSocket, "text"));
        this.addOutput("out", new ClassicPreset.Output(stringSocket, "out"));
    }
    data(inputs: { text?: any[] }) {
        return { out: inputs.text?.[0] ?? "" };
    }
}
registerNode({
    type: "Output",
    title: "Output",
    category: "io",
    create: () => new OutputNode()
});

/* —— 变量 —— */
export class SetVarNode extends ClassicPreset.Node {
    constructor() {
        super("SetVar");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addControl("name", new ClassicPreset.InputControl("text", { initial: "x" }));
        this.addInput("value", new ClassicPreset.Input(jsonSocket, "value"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data(inputs: { value?: any[] }) {
        const nameCtrl: any = (this as any).controls?.get?.("name") ?? (this as any).controls?.["name"];
        const name = String(nameCtrl?.value ?? "x");
        const value = inputs.value?.[0];
        return { name, value, next: 1 };
    }
}
registerNode({
    type: "SetVar",
    title: "SetVar",
    category: "variable",
    create: () => new SetVarNode()
});

export class GetVarNode extends ClassicPreset.Node {
    constructor() {
        super("GetVar");
        this.addControl("name", new ClassicPreset.InputControl("text", { initial: "x" }));
        this.addOutput("out", new ClassicPreset.Output(jsonSocket, "out"));
    }
    data() {
        const nameCtrl: any = (this as any).controls?.get?.("name") ?? (this as any).controls?.["name"];
        const name = String(nameCtrl?.value ?? "x");
        const v = Hooks.getVar ? Hooks.getVar(name) : undefined;
        return { out: v };
    }
}
registerNode({
    type: "GetVar",
    title: "GetVar",
    category: "variable",
    create: () => new GetVarNode()
});

/* —— JSON —— */
export class JsonParseNode extends ClassicPreset.Node {
    constructor() {
        super("JSON Parse");
        this.addInput("text", new ClassicPreset.Input(stringSocket, "text"));
        this.addOutput("out", new ClassicPreset.Output(jsonSocket, "json"));
    }
    data(inputs: { text?: any[] }) {
        const s = String(inputs.text?.[0] ?? "");
        try { return { out: s ? JSON.parse(s) : null }; }
        catch { return { out: null }; }
    }
}
registerNode({
    type: "JSON Parse",
    title: "JSON Parse",
    category: "collection",
    create: () => new JsonParseNode()
});

export class JsonStringifyNode extends ClassicPreset.Node {
    constructor() {
        super("JSON Stringify");
        this.addInput("json", new ClassicPreset.Input(jsonSocket, "json"));
        this.addControl("space", new ClassicPreset.InputControl("number", { initial: 0 }));
        this.addOutput("text", new ClassicPreset.Output(stringSocket, "text"));
    }
    data(inputs: { json?: any[] }) {
        const spaceCtrl: any = (this as any).controls?.get?.("space") ?? (this as any).controls?.["space"];
        const space = Number(spaceCtrl?.value ?? 0);
        try {
            const text = JSON.stringify(inputs.json?.[0] ?? null, null, isFinite(space) ? space : 0);
            return { text };
        } catch { return { text: "" }; }
    }
}
registerNode({
    type: "JSON Stringify",
    title: "JSON Stringify",
    category: "collection",
    create: () => new JsonStringifyNode()
});

/* —— HTTP Fetch —— */
export class HttpFetchNode extends ClassicPreset.Node {
    constructor() {
        super("HTTP Fetch");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addControl("method", new ClassicPreset.InputControl("text", { initial: "GET" }));
        this.addControl("url", new ClassicPreset.InputControl("text", { initial: "https://httpbin.org/get" }));
        this.addInput("headers", new ClassicPreset.Input(jsonSocket, "headers"));
        this.addInput("body", new ClassicPreset.Input(jsonSocket, "body"));
        this.addControl("respType", new ClassicPreset.InputControl("text", { initial: "json" }));

        this.addOutput("status", new ClassicPreset.Output(numberSocket, "status"));
        this.addOutput("headersOut", new ClassicPreset.Output(jsonSocket, "headers"));
        this.addOutput("bodyOut", new ClassicPreset.Output(jsonSocket, "body"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data() {
        const cached = OutputCache.get(this.id) ?? { status: 0, headers: {}, body: null };
        return { status: cached.status, headersOut: cached.headers, bodyOut: cached.body, next: 1 };
    }
}
registerNode({
    type: "HTTP Fetch",
    title: "HTTP Fetch",
    category: "io",
    create: () => new HttpFetchNode()
});

/* —— ToolCall —— */
export class ToolCallNode extends ClassicPreset.Node {
    constructor() {
        super("ToolCall");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addControl("name", new ClassicPreset.InputControl("text", { initial: "echo" }));
        this.addInput("args", new ClassicPreset.Input(jsonSocket, "args"));
        this.addOutput("result", new ClassicPreset.Output(jsonSocket, "result"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data() {
        const cached = OutputCache.get(this.id) ?? { result: null };
        return { result: cached.result, next: 1 };
    }
}
registerNode({
    type: "ToolCall",
    title: "ToolCall",
    category: "io",
    create: () => new ToolCallNode()
});

/* —— Function 定义 —— */
export class FuncDefNode extends ClassicPreset.Node {
    constructor() {
        super("FuncDef");
        this.addControl("name", new ClassicPreset.InputControl("text", { initial: "fn" }));
        this.addControl("params", new ClassicPreset.InputControl("text", { initial: "" })); // 逗号分隔：a,b,c
        this.addOutput("body", new ClassicPreset.Output(controlSocket, "body"));
    }
    data() {
        const nameCtrl: any = (this as any).controls?.get?.("name") ?? (this as any).controls?.["name"];
        const paramsCtrl: any = (this as any).controls?.get?.("params") ?? (this as any).controls?.["params"];
        const name = String(nameCtrl?.value ?? "fn");
        const paramsStr = String(paramsCtrl?.value ?? "").trim();
        const params = paramsStr
            ? paramsStr.split(",").map((s) => s.trim()).filter(Boolean)
            : [];
        // body:1 只是告诉 engine 这个输出口存在
        return { fnName: name, params, body: 1 };
    }
}
registerNode({
    type: "FuncDef",
    title: "FuncDef",
    category: "control",
    create: () => new FuncDefNode()
});

/* —— Function Return —— */
export class FuncReturnNode extends ClassicPreset.Node {
    constructor() {
        super("FuncReturn");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addInput("value", new ClassicPreset.Input(jsonSocket, "value"));
    }
    data(inputs: { value?: any[] }) {
        return { value: inputs.value?.[0] };
    }
}
registerNode({
    type: "FuncReturn",
    title: "FuncReturn",
    category: "control",
    create: () => new FuncReturnNode()
});

/* —— FunctionCall —— */
export class FunctionCallNode extends ClassicPreset.Node {
    constructor() {
        super("FunctionCall");
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addControl("name", new ClassicPreset.InputControl("text", { initial: "fn" }));
        // args: { a: 1, b: 2 } 这样的对象
        this.addInput("args", new ClassicPreset.Input(jsonSocket, "args"));
        this.addOutput("result", new ClassicPreset.Output(jsonSocket, "result"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));
    }
    data() {
        const cached = OutputCache.get(this.id) ?? { result: null };
        return { result: cached.result, next: 1 };
    }
}
registerNode({
    type: "FunctionCall",
    title: "FunctionCall",
    category: "control",
    create: () => new FunctionCallNode()
});


/* ============== Schemes / Engine / AreaExtra ============== */
export type NodeU =
    | StartNode | EndNode
    | IfNode | WhileNode
    | NumberNode | StringNode | BooleanNode
    | AddNode | PromptNode
    | LoggerNode | OutputNode
    | SetVarNode | GetVarNode
    | JsonParseNode | JsonStringifyNode
    | HttpFetchNode | ToolCallNode
    | FuncDefNode | FuncReturnNode | FunctionCallNode;

export class Conn<A extends NodeU, B extends NodeU> extends ClassicPreset.Connection<A, B> {}
export type ConnU = Conn<NodeU, NodeU>;
export type Schemes = GetSchemes<NodeU, ConnU>;
export type AreaExtra = ReactArea2D<Schemes> | ContextMenuExtra;
export class Engine extends DataflowEngine<Schemes> {}

/* ============== Graph 导出/导入（保存/加载） ============== */
function nodeTypeOf(n: ClassicPreset.Node): string { return (n as any).label as string; }

function collectControlValues(n: ClassicPreset.Node): Record<string, unknown> {
    const res: Record<string, unknown> = {};
    const c: any = (n as any).controls;
    const setFrom = (k: any, ctrl: any) => {
        const v = typeof ctrl?.getValue === "function" ? ctrl.getValue() : ctrl?.value;
        res[String(k)] = v ?? null;
    };
    if (!c) return res;
    if (typeof c.forEach === "function") c.forEach((ctrl: any, k: any) => setFrom(k, ctrl));
    else if (typeof c.entries === "function") for (const [k, v] of c.entries()) setFrom(k, v);
    else if (typeof c === "object") for (const k of Object.keys(c)) setFrom(k, (c as any)[k]);
    return res;
}

export function exportGraph(
    editor: NodeEditor<Schemes>,
    area: AreaPlugin<Schemes, AreaExtra>
): GraphJSON {
    const nodes = editor.getNodes().map((n) => {
        const view =
            (area as any).nodeViews?.get?.(n.id as any) ||
            (area as any).area?.nodeViews?.get?.(n.id as any);
        return {
            id: n.id,
            type: nodeTypeOf(n),
            position: (view?.position as any) ?? { x: 0, y: 0 },
            controls: collectControlValues(n)
        };
    });

    const ids = new Set(nodes.map((n) => n.id));
    const connections = editor.getConnections()
        .filter((c) => ids.has(c.source as any) && ids.has(c.target as any))
        .map((c) => ({
            from: { id: c.source as any, port: c.sourceOutput },
            to: { id: c.target as any, port: c.targetInput }
        }));

    return { version: 1, nodes, connections };
}

export async function importGraph(
    graph: GraphJSON,
    editor: NodeEditor<Schemes>,
    area: AreaPlugin<Schemes, AreaExtra>
) {
    // 清空
    for (const c of [...editor.getConnections()]) {
        try { await (editor as any).removeConnection?.(c); } catch {}
    }
    for (const n of [...editor.getNodes()]) {
        try { await (editor as any).removeNode?.(n.id as any); }
        catch { try { await (editor as any).removeNode?.(n as any); } catch {} }
    }


    const map = new Map<string | number, NodeU>();
    for (const g of graph.nodes) {
        const factory = resolveNodeFactory(g.type);
        const node = factory ? (factory() as NodeU) : (new StringNode() as NodeU);

        // 后面恢复控件值 / addNode / translate 的逻辑保持不变
        for (const [k, v] of Object.entries(g.controls || {})) {
            const ctrl: any = (node.controls as any)?.get?.(k) ?? (node.controls as any)?.[k];
            if (!ctrl) continue;
            ctrl?.setValue?.(v as any);
            if (!ctrl?.setValue) ctrl.value = v as any;
        }

        await editor.addNode(node);
        map.set(g.id, node);
        await area.translate(node.id, g.position || { x: 0, y: 0 });
    }

    for (const c of graph.connections) {
        const a = map.get(c.from.id);
        const b = map.get(c.to.id);
        if (!a || !b) continue;
        await editor.addConnection(new Conn(a, c.from.port, b, c.to.port));
    }

    AreaExtensions.zoomAt(area, editor.getNodes());
}

/* ============== 右键/面板工厂（供 ContextMenu & 左侧面板） ============== */
// Removed static BUILTIN_MENU; use dynamic registry at installation time


/* ============== 可选 Demo 创建器（未使用可删） ============== */
export async function createMinimalEditor(container: HTMLElement) {
    const editor = new NodeEditor<Schemes>();
    const area = new AreaPlugin<Schemes, AreaExtra>(container);
    const render = new ReactPlugin<Schemes, AreaExtra>({ createRoot });
    render.addPreset(ReactPresets.classic.setup());
    render.addPreset(ReactPresets.contextMenu.setup());
    editor.use(area);
    area.use(render);

    const connection = new ConnectionPlugin<Schemes, AreaExtra>();
    connection.addPreset(ConnectionPresets.classic.setup());
    area.use(connection);

    const dynamicItems = (getContextMenuItems() as Array<[string, () => ClassicPreset.Node]>).map(
        ([title, create]) => [title, () => create() as NodeU] as [string, () => NodeU]
    );
    const ctx = new ContextMenuPlugin<Schemes>({
        items: CtxMenuPresets.classic.setup(dynamicItems)
    });
    area.use(ctx);

    const engine = new Engine();
    editor.use(engine);

    AreaExtensions.zoomAt(area, editor.getNodes());
    return { editor, area, engine };
}

