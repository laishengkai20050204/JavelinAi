// apps/frontend/src/features/toolflow/core/runner.ts
import type { NodeEditor } from "rete";
import type { Schemes } from "../nodes/basic";
import { getRuntime } from "../core/nodeRegistry";
import {
    Engine,
    controlSocket,
    Hooks,
    OutputCache,
    IfNode,
    WhileNode,
    LoggerNode,
    StartNode,
    EndNode,
    SetVarNode,
    HttpFetchNode,
    ToolCallNode,
    FuncReturnNode,
    FunctionCallNode
} from "../nodes/basic";

// 读取某节点某输入端口的上游值（可能多值，返回数组）
async function readInput(editor: NodeEditor<Schemes>, engine: Engine, nodeId: any, inputName: string) {
    const conns = editor.getConnections().filter((c) => c.target === nodeId && c.targetInput === inputName);
    const values: any[] = [];
    for (const c of conns) {
        const up = await engine.fetch(c.source as any);
        values.push(up?.[c.sourceOutput]);
    }
    return values;
}

// 读控件值
function readControl(node: any, key: string) {
    const ctrl: any = node?.controls?.get?.(key) ?? node?.controls?.[key];
    return ctrl?.getValue?.() ?? ctrl?.value;
}

function idMap(editor: NodeEditor<Schemes>) {
    const m = new Map<any, any>();
    editor.getNodes().forEach((n: any) => m.set(n.id, n));
    return m;
}

function isControlConn(editor: NodeEditor<Schemes>, c: any) {
    const src = idMap(editor).get(c.source) as any;
    const out = src?.outputs?.get?.(c.sourceOutput) ?? src?.outputs?.[c.sourceOutput];
    const sock = out?.socket;
    return !!sock && sock.name === controlSocket.name;
}

function nextBy(editor: NodeEditor<Schemes>, fromId: any, port: string) {
    const conns = editor.getConnections().filter((c) => c.source === fromId && c.sourceOutput === port);
    const ctrl = conns.find((c) => isControlConn(editor, c));
    return ctrl ? ctrl.target : undefined;
}

type RunCtx = { vars: Record<string, any>; logs: any[] };
type RunResult = { ok: boolean; logs: any[]; vars: Record<string, any>; returnValue?: any };

async function runFromEntry(
    editor: NodeEditor<Schemes>,
    engine: Engine,
    entryId: any,
    ctx: RunCtx,
    options?: { allowReturn?: boolean }
): Promise<RunResult> {
    const ids = idMap(editor);
    const prevGetVar = Hooks.getVar;
    Hooks.getVar = (name: string) => ctx.vars[name];

    await (engine as any)?.reset?.();

    try {
        let cur: any = entryId;
        let guard = 0;
        const invalidate = async () => { await (engine as any)?.reset?.(); };
        const loopStack: any[] = [];
        const advanceOrLoop = () => {
            if (cur !== undefined) { return true; }
            const loopNodeId = loopStack[loopStack.length - 1];
            if (loopNodeId !== undefined) {
                cur = loopNodeId;
                return true;
            }
            return false;
        };

        while (cur !== undefined) {
            guard++; if (guard > 5000) throw new Error("执行步数过多，可能出现死循环");
            const node = ids.get(cur);

            // runtime 分发（你之前的那段）
            const typeOrLabel = String((node as any)?.type ?? (node as any)?.label);
            const rt = getRuntime(typeOrLabel);
            if (rt) {
                const api = {
                    editor, engine,
                    ctx,
                    readInput: (id: any, port: string) => readInput(editor, engine, id, port),
                    readControl: (n: any, name: string) => readControl(n, name),
                    nextBy: (id: any, port: string) => nextBy(editor, id, port),
                    invalidate,
                    setCache: (id: any, v: any) => OutputCache.set(id, v),
                    getCache: (id: any) => OutputCache.get(id),
                };
                const res: any = await rt(api as any, node);
                cur = res?.next ?? nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) break;
                continue;
            }

            // If
            if (node instanceof IfNode) {
                const d: any = await engine.fetch(node.id as any);
                const cond = !!d?.cond;
                ctx.logs.push({ type: "branch", node: String(node.id), path: cond ? "then" : "else" });
                cur = nextBy(editor, node.id, cond ? "then" : "else");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // While
            if (node instanceof WhileNode) {
                await invalidate();
                const d: any = await engine.fetch(node.id as any);
                const cond = !!d?.cond;
                if (cond) {
                    ctx.logs.push({ type: "loop", node: String(node.id), action: "enter" });
                    if (loopStack[loopStack.length - 1] !== node.id) {
                        loopStack.push(node.id);
                    }
                    const bodyEntry = nextBy(editor, node.id, "body");
                    if (bodyEntry === undefined) {
                        continue;
                    }
                    cur = bodyEntry;
                } else {
                    ctx.logs.push({ type: "loop", node: String(node.id), action: "exit" });
                    if (loopStack[loopStack.length - 1] === node.id) {
                        loopStack.pop();
                    }
                    cur = nextBy(editor, node.id, "next");
                }
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // SetVar
            if (node instanceof SetVarNode) {
                const d: any = await engine.fetch(node.id as any);
                const name = d?.name ?? "x";
                ctx.vars[name] = d?.value;
                ctx.logs.push({ type: "setvar", name, value: d?.value });
                await invalidate();
                cur = nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // HTTP Fetch
            if (node instanceof HttpFetchNode) {
                const method = String(readControl(node, "method") ?? "GET").toUpperCase();
                const url = String(readControl(node, "url") ?? "");
                const respType = String(readControl(node, "respType") ?? "json").toLowerCase();

                const [headersIn] = await readInput(editor, engine, node.id, "headers");
                const [bodyIn] = await readInput(editor, engine, node.id, "body");
                const headers: Record<string, string> =
                    (headersIn && typeof headersIn === "object") ? headersIn : {};

                let status = 0, outHeaders: Record<string, string> = {}, body: any = null;
                try {
                    if (Hooks.httpFetch) {
                        const r = await Hooks.httpFetch({
                            method, url, headers, body: bodyIn,
                            responseType: respType === "text" ? "text" : "json"
                        });
                        status = r.status; outHeaders = r.headers || {}; body = r.body;
                    } else {
                        const init: RequestInit = { method, headers };
                        if (bodyIn !== undefined && method !== "GET") {
                            const hasCT = Object.keys(headers).some(k => k.toLowerCase() === "content-type");
                            init.body = (typeof bodyIn === "string" || bodyIn instanceof Blob)
                                ? bodyIn : JSON.stringify(bodyIn);
                            if (!hasCT && typeof bodyIn !== "string" && !(bodyIn instanceof Blob)) {
                                (init.headers as any)["Content-Type"] = "application/json";
                            }
                        }
                        const res = await fetch(url, init);
                        status = res.status;
                        outHeaders = {};
                        res.headers.forEach((v, k) => (outHeaders[k] = v));
                        body = (respType === "text") ? await res.text() : await res.json().catch(() => null);
                    }
                } catch (e: any) {
                    status = -1; body = { error: e?.message || String(e) };
                }

                OutputCache.set(node.id, { status, headers: outHeaders, body });
                await invalidate();
                cur = nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // ToolCall
            if (node instanceof ToolCallNode) {
                const name = String(readControl(node, "name") ?? "");
                const [args] = await readInput(editor, engine, node.id, "args");
                let result: any = null;
                try {
                    result = Hooks.callTool ? await Hooks.callTool(name, args) : { tool: name, args };
                } catch (e: any) {
                    result = { error: e?.message || String(e) };
                }
                OutputCache.set(node.id, { result });
                await invalidate();
                cur = nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // FunctionCall —— 这里是函数调用逻辑
            if (node instanceof FunctionCallNode) {
                const fnName = String(readControl(node, "name") ?? "");
                const [argsObj] = await readInput(editor, engine, node.id, "args");
                const args = (argsObj && typeof argsObj === "object") ? argsObj : {};

                const allNodes = editor.getNodes() as any[];
                const def = allNodes.find((n: any) =>
                    String(n.label) === "FuncDef" &&
                    String(readControl(n, "name") ?? "") === fnName
                );

                if (!def) {
                    ctx.logs.push({ type: "error", node: String(node.id), message: `Function not found: ${fnName}` });
                    OutputCache.set(node.id, { result: null });
                    await invalidate();
                    cur = nextBy(editor, node.id, "next");
                    if (!advanceOrLoop()) { break; }
                    continue;
                }

                const paramsStr = String(readControl(def, "params") ?? "");
                const paramNames = paramsStr
                    .split(",")
                    .map((s) => s.trim())
                    .filter(Boolean);

                const localCtx: RunCtx = {
                    vars: Object.create(ctx.vars),
                    logs: []
                };
                for (const p of paramNames) {
                    if (Object.prototype.hasOwnProperty.call(args, p)) {
                        localCtx.vars[p] = args[p];
                    }
                }

                ctx.logs.push({ type: "fn_enter", node: String(node.id), name: fnName });

                const bodyEntry = nextBy(editor, def.id, "body");
                let retValue: any = undefined;
                if (bodyEntry !== undefined) {
                    const sub = await runFromEntry(editor, engine, bodyEntry, localCtx, { allowReturn: true });
                    retValue = sub.returnValue;
                    // 合并函数内部日志（打标记 fn）
                    ctx.logs.push(...sub.logs.map((x: any) => ({ ...x, fn: fnName })));
                }

                ctx.logs.push({ type: "fn_exit", node: String(node.id), name: fnName, value: retValue });

                OutputCache.set(node.id, { result: retValue });
                await invalidate();

                cur = nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // 函数返回节点（仅在 allowReturn 下生效）
            if (options?.allowReturn && node instanceof FuncReturnNode) {
                const d: any = await engine.fetch(node.id as any);
                const value = d?.value;
                ctx.logs.push({ type: "return", node: String(node.id), value });
                return { ok: true, logs: ctx.logs, vars: ctx.vars, returnValue: value };
            }

            // Logger
            if (node instanceof LoggerNode) {
                const d: any = await engine.fetch(node.id as any);
                if (d?.text !== undefined) { console.log("[Logger]", d.text); ctx.logs.push({ type: "log", text: String(d.text) }); }
                else { console.log("[Logger]", d?.json); ctx.logs.push({ type: "log", json: d?.json }); }
                cur = nextBy(editor, node.id, "next");
                if (!advanceOrLoop()) { break; }
                continue;
            }

            // End
            if (node instanceof EndNode) {
                ctx.logs.push({ type: "end", node: String(node.id) });
                break;
            }

            // 其它节点：尝试 next 控制口推进
            const nxt = nextBy(editor, node.id, "next");
            if (nxt !== undefined) { cur = nxt; continue; }

            // 或任何控制输出
            const cons = editor.getConnections().filter((c) => c.source === node.id);
            const ctrl = cons.find((c) => isControlConn(editor, c));
            if (ctrl) { cur = ctrl.target; continue; }

            if (loopStack.length) {
                const loopNodeId = loopStack[loopStack.length - 1];
                cur = loopNodeId;
                continue;
            }

            break;
        }

        return { ok: true, logs: ctx.logs, vars: ctx.vars };
    } finally {
        Hooks.getVar = prevGetVar;
    }
}


export async function runFromStart(editor: NodeEditor<Schemes>, engine: Engine) {
    const ids = idMap(editor);
    const starts = editor.getNodes().filter((n: any) => n.label === "Start");
    if (starts.length === 0) throw new Error("没有 Start 节点");
    const start = starts[0] as StartNode;

    const ctx: RunCtx = { vars: Object.create(null) as Record<string, any>, logs: [] as any[] };

    const entry = nextBy(editor, start.id, "next");
    if (entry === undefined) {
        return { ok: true, logs: ctx.logs, vars: ctx.vars };
    }

    const res = await runFromEntry(editor, engine, entry, ctx, { allowReturn: false });
    return res;
}
