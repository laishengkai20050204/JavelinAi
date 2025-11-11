// apps/frontend/src/features/toolflow/core/runner.ts
import type {NodeEditor} from "rete";
import type {Schemes} from "../nodes/basic";
import {getRuntime} from "../core/nodeRegistry";
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


// 新增：拿到同一“控制流端口”的**所有**目标（稳定排序）
function nextTargets(editor: NodeEditor<Schemes>, fromId: any, port: string): any[] {
    const conns = editor.getConnections().filter((c) => c.source === fromId && c.sourceOutput === port);
    const ctrls = conns.filter((c) => isControlConn(editor, c)).sort((a: any, b: any) => String(a.id).localeCompare(String(b.id)));
    return ctrls.map((c) => c.target);
}

// 新增：深拷贝 vars（优先用 structuredClone）
function cloneVars<T>(obj: T): T {
    // @ts-ignore
    return (typeof structuredClone === "function") ? structuredClone(obj) : JSON.parse(JSON.stringify(obj));
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
    // 原来：Hooks.getVar = (name: string) => ctx.vars[name];
    Hooks.getVar = (name: string) => {
        const key = String(name ?? "");
        if (Object.prototype.hasOwnProperty.call(ctx.vars, key)) return ctx.vars[key];
        if (!key) return undefined;
        return key.split(".").reduce((acc: any, k: string) => (acc == null ? undefined : acc[k]), ctx.vars);
    };


    await (engine as any)?.reset?.();

    // 在 runFromEntry 内部，try 之前或刚进入 try 之后：
    const collectedList: any[] = [];
    const collectedNamed: Record<string, any> = {};
    let hasCollected = false;

    const finalizeReturn = () => {
        if (!hasCollected) return undefined;
        const hasNamed = Object.keys(collectedNamed).length > 0;
        if (hasNamed) {
            // 可选：把未命名的也带上（方便调试）
            return {...collectedNamed, __list__: collectedList};
        }
        return collectedList.length <= 1 ? (collectedList[0] ?? null) : collectedList;
    };


    try {
        let cur: any = entryId;
        let guard = 0;
        const invalidate = async () => {
            await (engine as any)?.reset?.();
        };
        const loopStack: any[] = [];
        const advanceOrLoop = () => {
            if (cur !== undefined) {
                return true;
            }
            const loopNodeId = loopStack[loopStack.length - 1];
            if (loopNodeId !== undefined) {
                cur = loopNodeId;
                return true;
            }
            return false;
        };

        // 新增：统一做“多分支并发推进”
        const branchFrom = async (fromNodeId: any): Promise<boolean> => {
            const targets = nextTargets(editor, fromNodeId, "next");
            if (targets.length === 0) return false;
            if (targets.length === 1) {
                cur = targets[0];
                return advanceOrLoop();
            }
            // 多条分支：并发执行，每条分支克隆各自的 vars
            const parentVars = ctx.vars ?? {};
            const tasks = targets.map(async (tid) => {
                const childCtx: RunCtx = {vars: cloneVars(parentVars), logs: []};
                try {
                    const sub = await runFromEntry(editor, engine, tid, childCtx, {allowReturn: false});
                    // 也可以把子分支日志并回父日志（打上 tag）
                    ctx.logs.push(...sub.logs.map((x: any) => ({...x, branchFrom: String(fromNodeId)})));
                } catch (e: any) {
                    ctx.logs.push({
                        type: "branch_error",
                        from: String(fromNodeId),
                        to: String(tid),
                        error: String(e?.message ?? e)
                    });
                }
            });
            await Promise.allSettled(tasks);
            // 分叉后本路径结束（无隐式 join）
            return false;
        };

        while (cur !== undefined) {
            guard++;
            if (guard > 5000) throw new Error("执行步数过多，可能出现死循环");
            const node = ids.get(cur);

            // runtime 分发（你之前的那段）
            const typeOrLabel = String((node as any)?.type ?? (node as any)?.label);
            const rt = getRuntime(typeOrLabel);
            if (rt) {
                const api = {
                    editor, engine, ctx,
                    readInput: (id: any, port: string) => readInput(editor, engine, id, port),
                    readControl: (n: any, name: string) => readControl(n, name),
                    nextBy: (id: any, port: string) => nextBy(editor, id, port),
                    invalidate,
                    setCache: (id: any, v: any) => OutputCache.set(id, v),
                    getCache: (id: any) => OutputCache.get(id),

                    // 新增 ↓↓↓
                    vars: ctx.vars,
                    getVar: (path: string) => Hooks.getVar?.(path),
                };

                await rt(api as any, node); // 仅执行副作用/缓存
                const ok = await branchFrom(node.id);
                if (!ok) break;
                continue;
            }

            // If
            if (node instanceof IfNode) {
                const d: any = await engine.fetch(node.id as any);
                const cond = !!d?.cond;
                ctx.logs.push({type: "branch", node: String(node.id), path: cond ? "then" : "else"});
                cur = nextBy(editor, node.id, cond ? "then" : "else");
                if (!advanceOrLoop()) {
                    break;
                }
                continue;
            }

            // While
            if (node instanceof WhileNode) {
                await invalidate();
                const d: any = await engine.fetch(node.id as any);
                const cond = !!d?.cond;

                if (cond) {
                    ctx.logs.push({type: "loop", node: String(node.id), action: "enter"});
                    if (loopStack[loopStack.length - 1] !== node.id) {
                        loopStack.push(node.id);
                    }
                    const bodyEntry = nextBy(editor, node.id, "body");
                    if (bodyEntry === undefined) {
                        // 没有 body，直接下一轮判断
                        continue;
                    }
                    cur = bodyEntry;
                    if (!advanceOrLoop()) break;
                    continue; // ← 在 cond=true 分支里就结束本轮
                } else {
                    ctx.logs.push({type: "loop", node: String(node.id), action: "exit"});
                    if (loopStack[loopStack.length - 1] === node.id) {
                        loopStack.pop();
                    }
                    // 退出 while：沿 next 口推进（可能多分支并发）
                    const ok = await branchFrom(node.id);
                    if (!ok) break;
                    continue; // ← 在 cond=false 分支里也结束本轮
                }
            }


            // SetVar
            if (node instanceof SetVarNode) {
                const d: any = await engine.fetch(node.id as any);
                const name = d?.name ?? "x";
                // 点号路径写入 a.b.c
                const parts = String(name).split(".");
                let cur = ctx.vars;
                for (let i = 0; i < parts.length - 1; i++) {
                    const k = parts[i];
                    // 若中间层不存在或不是对象，则建一个空对象占位
                    if (!cur[k] || typeof cur[k] !== "object") cur[k] = {};
                    cur = cur[k];
                }
                cur[parts[parts.length - 1]] = d?.value;

                ctx.logs.push({type: "setvar", name, value: d?.value});
                await invalidate();
                const ok = await branchFrom(node.id);
                if (!ok) break;
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

                const cred = String(readControl(node, "credentials") ?? "same-origin");
                const init: RequestInit = {method, headers};
                if (cred === "include" || cred === "omit" || cred === "same-origin") {
                    (init as any).credentials = cred as RequestCredentials;
                }


                let status = 0, outHeaders: Record<string, string> = {}, body: any = null;
                try {
                    if (Hooks.httpFetch) {
                        const r = await Hooks.httpFetch({
                            method, url, headers, body: bodyIn,
                            responseType: respType === "text" ? "text" : "json"
                        });
                        status = r.status;
                        outHeaders = r.headers || {};
                        body = r.body;
                    } else {

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
                    status = -1;
                    body = {error: e?.message || String(e)};
                }

                OutputCache.set(node.id, {status, headers: outHeaders, body});
                await invalidate();
                const ok = await branchFrom(node.id);
                if (!ok) break;
                continue;
            }

            // ToolCall
            if (node instanceof ToolCallNode) {
                const name = String(readControl(node, "name") ?? "");
                const [args] = await readInput(editor, engine, node.id, "args");
                let result: any = null;
                try {
                    result = Hooks.callTool ? await Hooks.callTool(name, args) : {tool: name, args};
                } catch (e: any) {
                    result = {error: e?.message || String(e)};
                }
                OutputCache.set(node.id, {result});
                await invalidate();
                const ok = await branchFrom(node.id);
                if (!ok) break;
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
                    ctx.logs.push({type: "error", node: String(node.id), message: `Function not found: ${fnName}`});
                    OutputCache.set(node.id, {result: null});
                    await invalidate();
                    const ok = await branchFrom(node.id);
                    if (!ok) break;
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

                ctx.logs.push({type: "fn_enter", node: String(node.id), name: fnName});

                const bodyEntry = nextBy(editor, def.id, "body");
                let retValue: any = undefined;
                if (bodyEntry !== undefined) {
                    const sub = await runFromEntry(editor, engine, bodyEntry, localCtx, {allowReturn: true});
                    retValue = sub.returnValue;
                    // 合并函数内部日志（打标记 fn）
                    ctx.logs.push(...sub.logs.map((x: any) => ({...x, fn: fnName})));
                }

                ctx.logs.push({type: "fn_exit", node: String(node.id), name: fnName, value: retValue});

                OutputCache.set(node.id, {result: retValue});
                await invalidate();

                const ok = await branchFrom(node.id);
                if (!ok) break;
                continue;
            }

            // 函数返回节点（仅在 allowReturn 下生效）
            if (options?.allowReturn && node instanceof FuncReturnNode) {
                const d: any = await engine.fetch(node.id as any);
                const value = d?.value;
                ctx.logs.push({type: "return", node: String(node.id), value});
                return {ok: true, logs: ctx.logs, vars: ctx.vars, returnValue: value};
            }

            // Logger
            if (node instanceof LoggerNode) {
                const d: any = await engine.fetch(node.id as any);
                if (d?.text !== undefined) {
                    console.log("[Logger]", d.text);
                    ctx.logs.push({type: "log", text: String(d.text)});
                } else {
                    console.log("[Logger]", d?.json);
                    ctx.logs.push({type: "log", json: d?.json});
                }
                const ok = await branchFrom(node.id);
                if (!ok) break;
                continue;
            }

// Output —— 聚合输出，但不立刻 return；让流程继续到 End / 终止点
            if ((node as any)?.label === "Output") {
                const d: any = await engine.fetch(node.id as any);
                const value = d?.out ?? d?.text ?? null;

                // 记录缓存，便于面板查看
                OutputCache.set(node.id, {result: value});
                await invalidate();

                // 读取可选 name
                const key = String(readControl(node, "name") ?? "").trim();
                if (key) collectedNamed[key] = value;
                else collectedList.push(value);
                hasCollected = true;

                // 继续沿 next
                const targets = nextTargets(editor, node.id, "next");
                if (targets.length === 1) {
                    cur = targets[0];
                    continue;
                }
                if (targets.length > 1) {
                    await Promise.allSettled(targets.map((tid) => {
                        const childCtx: RunCtx = {vars: cloneVars(ctx.vars ?? {}), logs: []};
                        return runFromEntry(editor, engine, tid, childCtx, {allowReturn: false}).catch((e: any) => {
                            ctx.logs.push({
                                type: "branch_error",
                                from: String(node.id),
                                to: String(tid),
                                error: String(e?.message ?? e)
                            });
                        });
                    }));
                    break; // 分叉后本路径结束
                }

                // 没有 next 就让调度逻辑去找其它控制口/结束
            }


            // End
            if (node instanceof EndNode) {
                ctx.logs.push({type: "end", node: String(node.id)});
                break;
            }

            const targets = nextTargets(editor, node.id, "next");
            if (targets.length === 1) {
                cur = targets[0];
                continue;
            }
            if (targets.length > 1) {
                await Promise.allSettled(targets.map((tid) => {
                    const childCtx: RunCtx = {vars: cloneVars(ctx.vars ?? {}), logs: []};
                    return runFromEntry(editor, engine, tid, childCtx, {allowReturn: false}).catch((e: any) => {
                        ctx.logs.push({
                            type: "branch_error",
                            from: String(node.id),
                            to: String(tid),
                            error: String(e?.message ?? e)
                        });
                    });
                }));
                break; // 分叉后本路径结束（无隐式 join）
            }
            // 或任何控制输出
            const cons = editor.getConnections().filter((c) => c.source === node.id);
            const ctrl = cons.find((c) => isControlConn(editor, c));
            if (ctrl) {
                cur = ctrl.target;
                continue;
            }

            if (loopStack.length) {
                const loopNodeId = loopStack[loopStack.length - 1];
                cur = loopNodeId;
                continue;
            }

            break;
        }

        const rv = finalizeReturn();
        return rv === undefined
            ? {ok: true, logs: ctx.logs, vars: ctx.vars}
            : {ok: true, logs: ctx.logs, vars: ctx.vars, returnValue: rv};
    } finally {
        Hooks.getVar = prevGetVar;
    }
}


// 之前：export async function runFromStart(editor: NodeEditor<Schemes>, engine: Engine) {
export async function runFromStart(
    editor: NodeEditor<Schemes>,
    engine: Engine,
    opts?: { initialVars?: Record<string, any>; vars?: Record<string, any>; context?: Record<string, any> }
) {
    const starts = editor.getNodes().filter((n: any) => n.label === "Start");
    if (starts.length === 0) throw new Error("没有 Start 节点");
    const start = starts[0] as StartNode;

    const seed =
        (opts?.initialVars ?? opts?.vars ?? opts?.context ?? {}) as Record<string, any>;

    const ctx: RunCtx = {
        vars: Object.assign(Object.create(null), seed),
        logs: [] as any[],
    };

    const targets = nextTargets(editor, start.id, "next");
    if (targets.length === 0) {
        return {ok: true, logs: ctx.logs, vars: ctx.vars};
    }
    if (targets.length === 1) {
        return await runFromEntry(editor, engine, targets[0], ctx, {allowReturn: false});
    }

    // 多入口：并发跑每条分支，vars 各自拷贝，日志合并回父级
    await Promise.allSettled(
        targets.map(async (tid) => {
            const childCtx: RunCtx = {vars: cloneVars(ctx.vars ?? {}), logs: []};
            try {
                const r = await runFromEntry(editor, engine, tid, childCtx, {allowReturn: false});
                ctx.logs.push(...r.logs.map((x: any) => ({...x, branchFrom: String(start.id)})));
            } catch (e: any) {
                ctx.logs.push({
                    type: "branch_error",
                    from: String(start.id),
                    to: String(tid),
                    error: String(e?.message ?? e)
                });
            }
        })
    );

    // 分叉后没有隐式 join，这里直接返回父 ctx（vars 不合并）
    return {ok: true, logs: ctx.logs, vars: ctx.vars};
}

