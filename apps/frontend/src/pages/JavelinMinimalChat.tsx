// src/pages/JavelinMinimalChat.tsx
import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { listSavedTools } from "../features/clientTools/storage";
import { compileGraphToClientTool } from "../features/clientTools/compile";
import type { ClientTool } from "../features/clientTools/types";
import SafeMarkdown from "../components/SafeMarkdown";
import {
    Bot,
    Send,
    Sparkles,
    Loader2,
    Bug,
    Menu,
    Plus,
    Pencil,
    Search,
    Trash2,
    AlertTriangle,
} from "lucide-react";

/**
 * Javelin — Minimal NDJSON Chat UI (SSE-only accumulation)
 * --------------------------------------------------------
 * • NDJSON 仅用于获取 stepId
 * • SSE 才是唯一的增量来源（空 delta 忽略/视为 keep-alive）
 * • 用“覆盖式”写入草稿，避免重复叠加
 */

// === Quick toggles ==========================================================
const USE_DEMO = false; // ← false：连接你的后端
const BASE_URL = "/"; // e.g. "http://localhost:8080" (no trailing slash)
const NDJSON_PATH = "/ai/v3/chat/step/ndjson"; // your NDJSON endpoint
const STORAGE_KEY = "javelin.chat.v3";
const UNTITLED = "新会话";

// Utilities
const newId = () => Math.random().toString(36).slice(2, 10);
const joinUrl = (a: string, b: string) => (a.endsWith("/") ? a.slice(0, -1) : a) + b;
const isRecord = (v: unknown): v is Record<string, unknown> =>
    !!v && typeof v === "object" && !Array.isArray(v);

// === Types =================================================================
export type Role = "user" | "assistant" | "tool" | "system";
export interface ChatMessage {
    id: string;
    role: Role;
    content: string;
    ts: number;
}
export interface ConversationMeta {
    id: string;
    title: string;
    createdAt: number;
    updatedAt: number;
}
export interface StepEventLine {
    event?: string;
    ts?: string | number;
    data?: Record<string, unknown>;
    [k: string]: unknown;
}



// === NDJSON reader ==========================================================
async function* ndjsonIterator(res: Response) {
    if (!res.body) return;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    const NL = "\n";
    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx = buf.indexOf(NL);
        while (idx >= 0) {
            const line = buf.slice(0, idx).trim();
            buf = buf.slice(idx + 1);
            if (line) {
                try {
                    yield JSON.parse(line) as StepEventLine;
                } catch {
                    // ignore bad line
                }
            }
            idx = buf.indexOf(NL);
        }
    }
    const tail = buf.trim();
    if (tail) {
        try {
            yield JSON.parse(tail) as StepEventLine;
        } catch {
            // ignore
        }
    }
}

// === Demo stream（仅在 USE_DEMO=true 时才会用到） ============================
async function* demoNdjson(userText: string) {
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
    yield { event: "started", ts: Date.now(), data: { stepId: newId() } };
    await wait(200);
    yield { event: "step", ts: Date.now(), data: { type: "decision" } };
    await wait(150);
    // demo 里我们也只演示 NDJSON 的事件，不做真正拼字（保持与真实模式一致）
    yield { event: "final", ts: Date.now(), data: { type: "final" } };
}

// === SSE 解析（OpenAI 风格） ===============================================
type OpenAiDelta = { content?: string; role?: string; [k: string]: unknown };
type OpenAiChoice = { index: number; delta?: OpenAiDelta };
type OpenAiSse = { choices?: OpenAiChoice[] };

function sseIsEmptyDelta(u: unknown): boolean {
    if (!isRecord(u)) return false;
    const choices = (u as OpenAiSse).choices;
    if (!Array.isArray(choices) || choices.length === 0) return false;
    const first = choices[0];
    if (!isRecord(first)) return false;
    const delta = (first as OpenAiChoice).delta;
    if (!isRecord(delta)) return false;
    return Object.keys(delta).length === 0;
}

function sseExtractDeltaContent(u: unknown): string | null {
    if (!isRecord(u)) return null;
    const choices = (u as OpenAiSse).choices;
    if (!Array.isArray(choices) || choices.length === 0) return null;
    const first = choices[0];
    if (!isRecord(first)) return null;
    const delta = (first as OpenAiChoice).delta;
    if (!isRecord(delta)) return null;
    const content = (delta as OpenAiDelta).content;
    return typeof content === "string" ? content : null;
}

// === Dialogs ================================================================
function DialogBase({
                        open,
                        children,
                        onClose,
                    }: {
    open: boolean;
    children: React.ReactNode;
    onClose?: () => void;
}) {
    return (
        <AnimatePresence>
            {open && (
                <motion.div
                    className="fixed inset-0 z-50 flex items-center justify-center"
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                >
                    <motion.div
                        className="absolute inset-0 bg-black/40"
                        onClick={onClose}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                    />
                    <motion.div
                        role="dialog"
                        aria-modal="true"
                        className="relative z-10 w-[92vw] max-w-sm rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-xl"
                        initial={{ y: 14, scale: 0.98, opacity: 0 }}
                        animate={{
                            y: 0,
                            scale: 1,
                            opacity: 1,
                            transition: { type: "spring", stiffness: 320, damping: 28 },
                        }}
                        exit={{ y: 6, scale: 0.98, opacity: 0, transition: { duration: 0.15 } }}
                    >
                        {children}
                    </motion.div>
                </motion.div>
            )}
        </AnimatePresence>
    );
}

function ConfirmDialog({
                           open,
                           title,
                           message,
                           confirmText = "确认",
                           cancelText = "取消",
                           danger = false,
                           onConfirm,
                           onCancel,
                       }: {
    open: boolean;
    title: string;
    message: string;
    confirmText?: string;
    cancelText?: string;
    danger?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}) {
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div
                    className={`p-2 rounded-xl ${
                        danger ? "bg-red-600 text-white" : "bg-slate-900 text-white"
                    } dark:bg-white dark:text-slate-900`}
                >
                    <AlertTriangle className="h-4 w-4" />
                </div>
                <div className="font-semibold">{title}</div>
            </div>
            <div className="text-sm text-slate-600 dark:text-slate-300 mb-4">{message}</div>
            <div className="flex justify-end gap-2">
                <button
                    onClick={onCancel}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                    {cancelText}
                </button>
                <button
                    onClick={onConfirm}
                    className={`px-3 py-1.5 text-sm rounded-xl border ${
                        danger
                            ? "border-red-600 text-red-600 hover:bg-red-50"
                            : "border-slate-900 text-slate-900 hover:bg-slate-100"
                    } dark:border-white dark:text-white dark:hover:bg-white/10`}
                >
                    {confirmText}
                </button>
            </div>
        </DialogBase>
    );
}

function RenameDialog({
                          open,
                          initial,
                          onSave,
                          onCancel,
                      }: {
    open: boolean;
    initial: string;
    onSave: (t: string) => void;
    onCancel: () => void;
}) {
    const [val, setVal] = useState(initial);
    useEffect(() => {
        setVal(initial);
    }, [initial, open]);
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900">
                    <Pencil className="h-4 w-4" />
                </div>
                <div className="font-semibold">重命名会话</div>
            </div>
            <input
                value={val}
                onChange={(e) => setVal(e.target.value)}
                className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 bg-transparent px-3 py-2 text-sm outline-none"
                placeholder="会话名称"
            />
            <div className="flex justify-end gap-2 mt-3">
                <button
                    onClick={onCancel}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                    取消
                </button>
                <button
                    onClick={() => {
                        const t = val.trim();
                        if (t) onSave(t);
                    }}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-900 text-slate-900 hover:bg-slate-100 dark:border-white dark:text-white dark:hover:bg-white/10"
                >
                    保存
                </button>
            </div>
        </DialogBase>
    );
}

// === Main Component =========================================================
export default function JavelinMinimalChat() {
    // Conversations
    const [conversations, setConversations] = useState<ConversationMeta[]>([]);
    const [activeId, setActiveId] = useState<string>("");
    const [convMessages, setConvMessages] = useState<Record<string, ChatMessage[]>>({});

    // UI
    const [sidebarOpen, setSidebarOpen] = useState(true);
    const [query, setQuery] = useState("");
    const [busy, setBusy] = useState(false);
    const [events, setEvents] = useState<StepEventLine[]>([]);
    const [showDebug, setShowDebug] = useState(false);
    const [input, setInput] = useState("");
    const [confirmDelId, setConfirmDelId] = useState<string | null>(null);
    const [renameId, setRenameId] = useState<string | null>(null);
    // NEW: userId 输入
    const [userId, setUserId] = useState<string>("u1");

    const abortRef = useRef<AbortController | null>(null);
    const listRef = useRef<HTMLDivElement | null>(null);
    const esRef = useRef<EventSource | null>(null);
    const sendingRef = useRef(false);
    const streamRef = useRef<string | null>(null);

    // —— 与 Demo 同步的关键引用
    const accumulatedRef = useRef<string>(""); // 仅由 SSE 构建的累积文本
    const currentSseStepIdRef = useRef<string | null>(null); // 防止同 step 重复订阅

    // Derived
    const messages = useMemo(() => convMessages[activeId] ?? [], [convMessages, activeId]);

    // 内置一个简易 debug_tool，方便后端测试 clientCalls
    const debugClientTool: ClientTool = {
        manifest: {
            name: "client_debug_tool",
            description: "前端打印并回传参数的调试工具",
            "x-execTarget": "client",
            parameters: { type: "object", properties: { note: { type: "string", default: "" } }, required: [] }
        },
        async execute(args, ctx) {
            console.log("[client] debug_tool execute:", { args, ctx });
            return { result: { ok: true, echo: args, ctx } };
        }
    };

    const [clientTools, setClientTools] = useState<ClientTool[]>([]);
    const processedCallIdsRef = useRef<Set<string>>(new Set());

    useEffect(() => {
        (async () => {
            const saved = listSavedTools();
            const compiled: ClientTool[] = [];
            for (const b of saved) {
                compiled.push(
                    await compileGraphToClientTool(b.graph, {
                        name: b.meta.name,
                        description: b.meta.description
                    })
                );
            }
            setClientTools([debugClientTool, ...compiled]);
        })();
    }, []);

    // Auto-scroll
    useEffect(() => {
        listRef.current?.scrollTo({
            top: listRef.current.scrollHeight,
            behavior: "smooth",
        });
    }, [messages, busy, activeId]);

    // Load from localStorage
    useEffect(() => {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const st = JSON.parse(raw);
                setConversations(st.conversations ?? []);
                setConvMessages(st.convMessages ?? {});
                setActiveId(st.activeId ?? "");
                setUserId(st.userId ?? "u1"); // NEW
            } else {
                const id = `c_${newId()}`;
                const meta: ConversationMeta = { id, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
                setConversations([meta]);
                setConvMessages({ [id]: [] });
                setActiveId(id);
            }
        } catch {
            // ignore
        }
    }, []);
    // Save to localStorage
    useEffect(() => {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ conversations, convMessages, activeId, userId })); // NEW
        } catch {
            // ignore
        }
    }, [conversations, convMessages, activeId, userId]); // NEW

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            abortRef.current?.abort();
            if (esRef.current) {
                esRef.current.close();
                esRef.current = null;
            }
        };
    }, []);

    // Helpers
    const touchActive = () =>
        setConversations((prev) =>
            prev.map((c) => (c.id === activeId ? { ...c, updatedAt: Date.now() } : c)),
        );

    const setMsgs = (fn: (m: ChatMessage[]) => ChatMessage[]) =>
        setConvMessages((prev) => ({ ...prev, [activeId]: fn(prev[activeId] ?? []) }));

    const addMessage = (m: ChatMessage) => {
        setMsgs((prev) => [...prev, m]);
        touchActive();
    };

    // —— 覆盖式写入草稿（根因修复，避免重复叠加）
    const replaceDraftContent = (text: string) => {
        setMsgs((prev) => {
            const next = [...prev];
            const last = next[next.length - 1];
            if (last && last.role === "assistant" && last.id === "draft") {
                if (last.content === text) return next; // 相同内容避免重渲染
                last.content = text;
                last.ts = Date.now();
                return next;
            }
            // 尚无草稿，则创建一条
            next.push({ id: "draft", role: "assistant", content: text, ts: Date.now() });
            return next;
        });
    };

    // 先确保有一个空草稿槽位（发起请求后立即调用）
    const ensureDraftExists = () => replaceDraftContent("");

    const finalizeDraft = () => {
        setMsgs((prev) => prev.map((m) => (m.id === "draft" ? { ...m, id: newId() } : m)));
    };

    function scheduleClientCall(stepId: string, call: any) {
        const name = call?.name;
        const callId = call?.id || call?.callId || (crypto as any)?.randomUUID?.() || String(Date.now());
        if (!name) return;
        if (processedCallIdsRef.current.has(callId)) return;
        processedCallIdsRef.current.add(callId);

        const args = call?.arguments ?? call?.args ?? {};
        void executeClientTool(stepId, name, callId, args);
    }

    function createConversation() {
        abortRef.current?.abort();
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        currentSseStepIdRef.current = null;
        accumulatedRef.current = "";

        const id = `c_${newId()}`;
        const meta: ConversationMeta = { id, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
        setConversations((prev) => [meta, ...prev]);
        setConvMessages((prev) => ({ ...prev, [id]: [] }));
        setActiveId(id);
        setEvents([]);
        setInput("");
        processedCallIdsRef.current.clear();
    }

    function requestRename(id: string) {
        setRenameId(id);
    }
    function doRename(id: string, title: string) {
        setConversations((prev) => prev.map((c) => (c.id === id ? { ...c, title, updatedAt: Date.now() } : c)));
    }

    function requestDelete(id: string) {
        setConfirmDelId(id);
    }
    function doDelete(id: string) {
        const idx = conversations.findIndex((c) => c.id === id);
        const after = conversations.filter((c) => c.id !== id);
        setConvMessages((prev) => {
            const { [id]: _removed, ...rest } = prev;
            return rest;
        });

        let next = activeId;
        let needBootstrap = false;
        if (activeId === id) {
            if (after.length > 0) next = after[Math.min(idx, after.length - 1)].id;
            else {
                next = `c_${newId()}`;
                needBootstrap = true;
            }
        }

        if (needBootstrap) {
            const meta: ConversationMeta = { id: next, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
            setConversations([meta]);
            setConvMessages((prev) => ({ ...prev, [next]: [] }));
        } else {
            setConversations(after);
        }
        setActiveId(next);
        setEvents([]);
        setInput("");

        // 清理订阅
        abortRef.current?.abort();
        abortRef.current = null;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        currentSseStepIdRef.current = null;
        accumulatedRef.current = "";
    }

    function handleStepNdjsonLine(line: StepEventLine) {
        setEvents((prev) => [...prev, line]);

        // 取 stepId（顶层或 data 内）
        const sidTop = (isRecord(line) && typeof line["stepId"] === "string") ? String(line["stepId"]) : null;
        const sidData = (isRecord(line?.data) && typeof (line.data as any)["stepId"] === "string") ? String((line.data as any)["stepId"]) : null;
        const sid = sidTop || sidData || null;

        // 打开 SSE（只开一次）
        if (!USE_DEMO && sid && currentSseStepIdRef.current !== sid) {
            currentSseStepIdRef.current = sid;
            startSSE(sid);
        }

        // 顶层 clientCalls：{ type:"clientCalls", calls:[...] }
        if (isRecord(line) && line["type"] === "clientCalls" && Array.isArray((line as any).calls)) {
            const calls = (line as any).calls as Array<any>;
            for (const c of calls) scheduleClientCall(sid || "", c);
        }
        // data 内的 clientCalls
        if (isRecord(line?.data) && (line.data as any)["type"] === "clientCalls" && Array.isArray((line.data as any)["calls"])) {
            const calls = (line.data as any)["calls"] as Array<any>;
            for (const c of calls) scheduleClientCall(sid || "", c);
        }

        // 终态收尾
        if (isRecord(line?.data) && (line.event === "final" || line.event === "completed" ||
            ["final", "assistant_final", "done"].includes(String(line.data["type"] ?? "")))) {
            finalizeDraft();
        }
    }


    // Send（NDJSON 只拿 stepId；真正拼字走 SSE）
    async function handleSend(text?: string) {
        if (sendingRef.current) return;
        const content = (text ?? input).trim();
        if (!content || busy || !activeId) return;
        sendingRef.current = true;
        setInput("");
        setBusy(true);
        setEvents([]);

        // 用户消息
        addMessage({ id: newId(), role: "user", content, ts: Date.now() });

        // 会话命名
        const meta = conversations.find((c) => c.id === activeId);
        if (meta && (!meta.title || meta.title === UNTITLED)) {
            const short = content.split(" ").filter(Boolean).join(" ").slice(0, 18);
            doRename(activeId, short || UNTITLED);
        }

        // 先准备一个空草稿（UI 立刻进入“正在输入”）
        ensureDraftExists();

        const controller = new AbortController();
        abortRef.current = controller;
        const streamId = newId();
        streamRef.current = streamId;

        try {
            const iterable = USE_DEMO ? demoNdjson(content) : fetchNdjson(content, activeId, controller.signal);
            for await (const line of iterable as AsyncIterable<StepEventLine>) {
                if (streamRef.current !== streamId) break;
                handleStepNdjsonLine(line);
            }
        } catch (err: unknown) {
            const msg = err instanceof Error ? err.message : String(err);
            replaceDraftContent(`(stream error) ${msg}`);
            finalizeDraft();
        } finally {
            if (USE_DEMO || !esRef.current) setBusy(false);
            abortRef.current = null;
            sendingRef.current = false;
        }
    }

    async function* fetchNdjson(userText: string, conversationId: string, signal?: AbortSignal) {
        const payload = buildRequest(userText, conversationId);
        const res = await fetch(joinUrl(BASE_URL, NDJSON_PATH), {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "application/x-ndjson" },
            body: JSON.stringify(payload),
            signal,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        for await (const line of ndjsonIterator(res)) yield line;
    }

    function buildRequest(userText: string, conversationId: string) {
        return {
            userId: userId.trim() || "u1",
            conversationId,
            q: userText,
            toolChoice: "auto",
            responseMode: "step-json-ndjson",
            clientTools: clientTools.map(t => ({
                type: "function",
                function: t.manifest // { name, description, parameters, "x-execTarget":"client" }
            }))
        };
    }

    const safeParseJson = (s?: string) => { try { return s ? JSON.parse(s) : {}; } catch { return {}; } };

    function manifestList() {
        return clientTools.map(t => ({ type: "function", function: t.manifest }));
    }

    function toClientDataPayload(out: any) {
        // 尽量兼容：string→text；已有 payload 原样；否则当 json
        if (out && typeof out === "object" && out.payload) return { payload: out.payload };
        if (typeof out === "string") return { payload: { type: "text", value: out } };
        if (out && typeof out === "object" && out.result !== undefined) {
            return { payload: { type: "json", value: out.result } };
        }
        return { payload: { type: "json", value: out } };
    }

    async function continueViaNdjson(stepId: string, clientResult: any) {
        const body = {
            userId: userId.trim() || "u1",
            conversationId: activeId,
            resumeStepId: stepId,
            toolChoice: "auto",
            responseMode: "step-json-ndjson",
            clientTools: manifestList(),
            clientResults: [clientResult]
        };

        const res = await fetch(joinUrl(BASE_URL, NDJSON_PATH), {
            method: "POST",
            headers: { "Content-Type": "application/json", "Accept": "application/x-ndjson" },
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        for await (const line of ndjsonIterator(res)) {
            handleStepNdjsonLine(line);
        }
    }


    async function executeClientTool(stepId: string, name: string, callId: string, rawArgs: any) {
        const tool = clientTools.find(t => t.manifest.name === name);
        if (!tool) {
            const clientResult = {
                callId, name,
                    status: "ERROR",
                    reused: false,
                    arguments: rawArgs || {},
                data: { payload: { type: "text", value: `client tool not found: ${name}` } }
            };
            await continueViaNdjson(stepId, clientResult);
            return;
        }
        try {
            const out = await tool.execute(rawArgs ?? {}, { userId, conversationId: activeId });
            const clientResult = {
                callId, name,
                    status: "SUCCESS",
                    reused: false,
                    arguments: rawArgs || {},
                data: toClientDataPayload(out)
            };
            await continueViaNdjson(stepId, clientResult);
        } catch (e: any) {
            const clientResult = {
                callId, name,
                status: "ERROR",
                reused: false,
                arguments: rawArgs || {},
                data: { payload: { type: "text", value: String(e?.message || e) } }
            };
            await continueViaNdjson(stepId, clientResult);
        }
    }

    /** 解析两种可能的前端工具调用信号，并执行 */
    function handleToolMessage(stepId: string, obj: any): boolean {
        // A) OpenAI 风格
        if (obj?.type === "tool_call" && (obj?.xExecTarget === "client" || obj?.["x-execTarget"] === "client")) {
            const callId = obj.tool_call_id || (crypto as any)?.randomUUID?.() || String(Date.now());
            const args = typeof obj.arguments === "string" ? safeParseJson(obj.arguments) : (obj.arguments || {});
            void executeClientTool(stepId, obj.name, callId, args);
            return true;
        }
        // B) 自定义：client_tool_call
        if (obj?.type === "client_tool_call") {
            const callId = obj.callId || (crypto as any)?.randomUUID?.() || String(Date.now());
            void executeClientTool(stepId, obj.name, callId, obj.args || {});
            return true;
        }
        // ✅ C) “裸 call 对象”（SSE/NDJSON 的 calls[] 里常见：{id,name,arguments,...}）
        if (obj && obj.name && (obj.arguments !== undefined || obj.args !== undefined)) {
            scheduleClientCall(stepId, obj);
            return true;
        }
        return false;
    }



    function handleKey(e: React.KeyboardEvent<HTMLTextAreaElement>) {
        // @ts-expect-error IME composing flag
        if (e.isComposing || (e.nativeEvent as any)?.isComposing) return;
        if (e.key === "Enter" && !e.shiftKey) {
            if (e.repeat) {
                e.preventDefault();
                return;
            }
            e.preventDefault();
            handleSend();
        }
    }

    function handleStop() {
        abortRef.current?.abort();
        abortRef.current = null;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        currentSseStepIdRef.current = null;
        accumulatedRef.current = "";
        processedCallIdsRef.current.clear();
        setBusy(false);
    }

    // —— 与 Demo 一致的 SSE 累积策略（唯一数据源）
    function startSSE(stepId: string) {
        if (USE_DEMO) return;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        try {
            const url = joinUrl(BASE_URL, `/ai/v2/chat/sse?stepId=${encodeURIComponent(stepId)}`);
            const es = new EventSource(url);
            esRef.current = es;

            // 关键：SSE 开始时，重置累计串，并清空草稿（覆盖式）
            accumulatedRef.current = "";
            processedCallIdsRef.current.clear();
            replaceDraftContent("");

            setEvents((prev) => [...prev, { event: "sse-open", ts: Date.now(), data: { url } }]);

            es.onmessage = (e: MessageEvent) => {
                if (e.data === "[DONE]") {
                    setEvents((prev) => [...prev, { event: "sse-done", ts: Date.now(), data: {} }]);
                    finalizeDraft();
                    setBusy(false);
                    return;
                }
                try {
                    const obj = JSON.parse(e.data) as unknown;

                    // 先处理 clientCalls 包：{ type:"clientCalls", calls:[...] }
                    if (isRecord(obj) && obj["type"] === "clientCalls" && Array.isArray((obj as any).calls)) {
                        for (const c of (obj as any).calls) scheduleClientCall(stepId, c);
                        setEvents(prev => [...prev, { event: "sse-clientCalls", ts: Date.now(), data: obj as any }]);
                        return;
                    }

                    // ✅ 先看看是不是“前端工具调用”信号；如果是，直接执行并回填
                    if (handleToolMessage(stepId, obj)) {
                        setEvents((prev) => [...prev, { event: "sse-client-tool", ts: Date.now(), data: obj as any }]);
                        return;
                    }

                    // 空 delta：直接忽略（常见 keep-alive / 空结构化片段）
                    if (sseIsEmptyDelta(obj)) {
                        setEvents((prev) => [
                            ...prev,
                            { event: "sse-message", ts: Date.now(), data: isRecord(obj) ? obj : { raw: e.data } },
                        ]);
                        return;
                    }

                    const chunk = sseExtractDeltaContent(obj);
                    if (typeof chunk === "string" && chunk) {
                        accumulatedRef.current += chunk;
                        replaceDraftContent(accumulatedRef.current);
                    }
                    setEvents((prev) => [
                        ...prev,
                        { event: "sse-message", ts: Date.now(), data: isRecord(obj) ? obj : { raw: e.data } },
                    ]);
                } catch {
                    setEvents((prev) => [
                        ...prev,
                        { event: "sse-message-raw", ts: Date.now(), data: { raw: e.data } },
                    ]);
                }
            };

            es.onerror = () => {
                setEvents((prev) => [...prev, { event: "sse-error", ts: Date.now(), data: {} }]);
                es.close();
                esRef.current = null;
                currentSseStepIdRef.current = null;
                setBusy(false);
            };

            (["decision", "clientCalls", "tools", "status", "finished", "error"] as const).forEach((name) => {
                es.addEventListener(name, (ev: MessageEvent) => {
                    if (name === "clientCalls") {
                        try {
                            const payload = JSON.parse(ev.data);
                            const list = Array.isArray(payload?.calls) ? payload.calls : (Array.isArray(payload) ? payload : [payload]);
                            for (const c of list) scheduleClientCall(stepId, c);
                        } catch {}
                    }
                    setEvents((prev) => [
                        ...prev,
                        { event: `sse-${name}`, ts: Date.now(), data: { raw: ev.data } },
                    ]);
                    if (name === "finished") {
                        finalizeDraft();
                        setBusy(false);
                        es.close();
                        esRef.current = null;
                        currentSseStepIdRef.current = null;
                    }
                });
            });
        } catch (e) {
            setEvents((prev) => [
                ...prev,
                { event: "sse-open-failed", ts: Date.now(), data: { error: String(e) } },
            ]);
        }
    }

    // Filtered list
    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return conversations;
        return conversations.filter((c) => (c.title || UNTITLED).toLowerCase().includes(q));
    }, [conversations, query]);

    // Debug event groups: split NDJSON vs SSE
    const ndjsonEvents = useMemo(
        () => events.filter((e) => !(typeof e?.event === "string" && String(e.event).startsWith("sse-"))),
        [events],
    );
    const sseEvents = useMemo(
        () => events.filter((e) => typeof e?.event === "string" && String(e.event).startsWith("sse-")),
        [events],
    );

    return (
        <div className="min-h-screen w-full bg-gradient-to-b from-slate-50 to-white dark:from-slate-950 dark:to-slate-900 text-slate-900 dark:text-slate-100">
            <div className="mx-auto max-w-6xl px-3 sm:px-4 py-3">
                <div className="grid grid-cols-1 md:grid-cols-[auto_minmax(0,1fr)] gap-3 md:gap-4">
                    {/* Sidebar */}
                    <motion.aside
                        layout
                        initial={false}
                        animate={{ width: sidebarOpen ? 280 : 56 }}
                        transition={{ type: "spring", stiffness: 260, damping: 24 }}
                        className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 overflow-hidden"
                    >
                        <div className="flex">
                            {/* Mini rail */}
                            <div className="w-14 shrink-0 py-2 flex flex-col items-center gap-2">
                                <button
                                    onClick={() => setSidebarOpen((v) => !v)}
                                    className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    title={sidebarOpen ? "收起侧边栏" : "展开侧边栏"}
                                    aria-label="切换侧边栏"
                                >
                                    <Menu className="h-4 w-4" />
                                </button>
                                <button
                                    onClick={createConversation}
                                    className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    title="新建对话"
                                    aria-label="新建对话"
                                >
                                    <Plus className="h-4 w-4" />
                                </button>
                            </div>

                            {/* Expanded panel */}
                            <motion.div
                                className="flex-1 min-w-0 p-3"
                                initial={false}
                                animate={{ opacity: sidebarOpen ? 1 : 0, x: sidebarOpen ? 0 : -8 }}
                                transition={{ duration: 0.18 }}
                                style={{ pointerEvents: sidebarOpen ? "auto" : "none" }}
                            >
                                <div className="flex items-center gap-2 mb-2">
                                    <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900">
                                        <Sparkles className="h-4 w-4" />
                                    </div>
                                    <div className="font-semibold">会话</div>
                                    <div className="ml-auto flex items-center gap-2">
                                        <button
                                            onClick={createConversation}
                                            className="inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-xs border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                        >
                                            <Plus className="h-3.5 w-3.5" />
                                            新建
                                        </button>
                                        {/*aria-label="User ID"*/}
                                    </div>
                                </div>

                                <div className="relative mb-2">
                                    <Search className="absolute left-2 top-2.5 h-4 w-4 text-slate-400" />
                                    <input
                                        value={query}
                                        onChange={(e) => setQuery(e.target.value)}
                                        placeholder="搜索会话…"
                                        className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 bg-transparent pl-8 pr-3 py-2 text-sm outline-none"
                                    />
                                </div>

                                <div className="space-y-1 max-h-[68vh] overflow-y-auto pr-1">
                                    {filtered.length === 0 && (
                                        <div className="text-xs text-slate-500 dark:text-slate-400 py-6 text-center">
                                            没有匹配的会话
                                        </div>
                                    )}
                                    {filtered.map((c) => (
                                        <div
                                            key={c.id}
                                            role="button"
                                            tabIndex={0}
                                            onClick={() => {
                                                setActiveId(c.id);
                                                setEvents([]);
                                                setInput("");
                                            }}
                                            onKeyDown={(e) => {
                                                if (e.key === "Enter" || e.key === " ") {
                                                    e.preventDefault();
                                                    setActiveId(c.id);
                                                    setEvents([]);
                                                    setInput("");
                                                }
                                            }}
                                            className={`w-full text-left rounded-2xl px-3 py-2 border transition cursor-pointer focus:outline-none focus:ring-2 focus:ring-slate-400/40 ${
                                                c.id === activeId
                                                    ? "bg-slate-900 text-white dark:bg-white dark:text-slate-900 border-slate-900/20 dark:border-white/10"
                                                    : "bg-white/70 dark:bg-slate-900/40 border-slate-200 dark:border-slate-800 hover:bg-slate-100/70 dark:hover:bg-slate-800/60"
                                            }`}
                                        >
                                            <div className="flex items-center gap-2">
                                                <div className="flex-1 min-w-0 truncate font-medium text-sm">
                                                    {c.title || UNTITLED}
                                                </div>
                                                <div className="ml-2 shrink-0 flex items-center gap-1 opacity-80">
                                                    <button
                                                        type="button"
                                                        onPointerDown={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                        }}
                                                        onMouseDown={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                        }}
                                                        onClick={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                            requestRename(c.id);
                                                        }}
                                                        className="p-1 rounded hover:bg-black/5 dark:hover:bg-white/10"
                                                        aria-label="重命名会话"
                                                        title="重命名"
                                                    >
                                                        <Pencil className="h-3.5 w-3.5" />
                                                    </button>
                                                    <button
                                                        type="button"
                                                        onPointerDown={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                        }}
                                                        onMouseDown={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                        }}
                                                        onClick={(e) => {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                            requestDelete(c.id);
                                                        }}
                                                        className="p-1 rounded hover:bg-black/5 dark:hover:bg-white/10"
                                                        aria-label="删除会话"
                                                        title="删除"
                                                    >
                                                        <Trash2 className="h-3.5 w-3.5" />
                                                    </button>
                                                </div>
                                            </div>
                                            <div className="text-[11px] mt-0.5 opacity-70 truncate">
                                                {convMessages[c.id]?.[convMessages[c.id].length - 1]?.content ?? "(空)"}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </motion.div>
                        </div>
                    </motion.aside>

                    {/* Main column */}
                    <motion.main
                        layout
                        className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40"
                    >
                        {/* Header */}
                        <div className="sticky top-0 z-10 px-3 sm:px-4 py-3 border-b border-slate-200/70 dark:border-slate-800 backdrop-blur">
                            <div className="flex items-center gap-2">
                                <button
                                    onClick={() => setSidebarOpen((v) => !v)}
                                    className="mr-1 inline-flex items-center justify-center h-9 w-auto px-2.5 rounded-2xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    title="切换侧边栏"
                                >
                                    <Menu className="h-4 w-4" />
                                    <span className="ml-1 hidden sm:inline text-xs">
                    {sidebarOpen ? "收起" : "展开"}
                  </span>
                                </button>
                                <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900">
                                    <Sparkles className="h-4 w-4" />
                                </div>
                                <div className="font-semibold leading-tight truncate">
                                    {conversations.find((c) => c.id === activeId)?.title || UNTITLED}
                                </div>
                                <div className="ml-auto flex items-center gap-2">
                                    {busy ? (
                                        <button
                                            onClick={handleStop}
                                            className="inline-flex items-center gap-2 rounded-2xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                        >
                                            <Loader2 className="h-4 w-4 animate-spin" /> 停止
                                        </button>
                                    ) : (
                                        <button
                                            onClick={createConversation}
                                            className="inline-flex items-center gap-2 rounded-2xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                        >
                                            <Plus className="h-4 w-4" /> 新建
                                        </button>
                                    )}
                                    <button
                                        onClick={() => setShowDebug((v) => !v)}
                                        className="inline-flex items-center gap-2 rounded-2xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    >
                                        <Bug className="h-4 w-4" /> {showDebug ? "隐藏事件" : "查看事件"}
                                    </button>
                                </div>
                            </div>
                        </div>

                        {/* Messages */}
                        <div className="px-3 sm:px-4 py-3">
                            <div
                                ref={listRef}
                                className="h-[60vh] md:h-[66vh] overflow-y-auto rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4"
                            >
                                {messages.length === 0 && <EmptyState />}
                                <div className="space-y-3">
                                    {messages.map((m) => (
                                        <motion.div
                                            key={m.id}
                                            initial={{ opacity: 0, y: 8 }}
                                            animate={{ opacity: 1, y: 0 }}
                                            transition={{ duration: 0.18 }}
                                        >
                                            <ChatBubble role={m.role} text={m.content} />
                                        </motion.div>
                                    ))}
                                    {busy && !messages.some((x) => x.id === "draft") && (
                                        <motion.div
                                            initial={{ opacity: 0, y: 8 }}
                                            animate={{ opacity: 1, y: 0 }}
                                            transition={{ duration: 0.18 }}
                                        >
                                            <ChatBubble role="assistant" text="" typing />
                                        </motion.div>
                                    )}
                                </div>
                            </div>

                            {/* Debug events */}
                            {showDebug && (
                                <div className="mt-3 grid gap-3 md:grid-cols-2">
                                    <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4 text-sm">
                                        <div className="text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400 mb-2">
                                            NDJSON Events
                                        </div>
                                        <div className="max-h-56 overflow-auto font-mono text-[12px] leading-relaxed whitespace-pre-wrap">
                                            {ndjsonEvents.map((e, i) => (
                                                <div
                                                    key={`n-${i}`}
                                                    className="py-1 border-b border-slate-100/70 dark:border-slate-800/60 last:border-0"
                                                >
                                                    {JSON.stringify(e)}
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                    <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4 text-sm">
                                        <div className="text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400 mb-2">
                                            SSE Events
                                        </div>
                                        <div className="max-h-56 overflow-auto font-mono text-[12px] leading-relaxed whitespace-pre-wrap">
                                            {sseEvents.map((e, i) => (
                                                <div
                                                    key={`s-${i}`}
                                                    className="py-1 border-b border-slate-100/70 dark:border-slate-800/60 last:border-0"
                                                >
                                                    {JSON.stringify(e)}
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Composer */}
                            <div className="sticky bottom-4 mt-4">
                                <div className="relative rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-900/60 backdrop-blur p-2 shadow-sm">
                  <textarea
                      value={input}
                      onChange={(e) => setInput(e.target.value)}
                      onKeyDown={handleKey}
                      placeholder="和我聊聊… (Enter 发送，Shift+Enter 换行)"
                      className="w-full resize-none bg-transparent outline-none p-3 pr-12 leading-6 max-h-40 h-14 text-sm"
                  />
                                    <button
                                        onClick={() => handleSend()}
                                        disabled={busy || !input.trim() || !activeId}
                                        className="absolute right-3 bottom-3 inline-flex items-center justify-center h-9 w-9 rounded-2xl border border-slate-300/70 dark:border-slate-700 disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-800"
                                        title="Send"
                                    >
                                        {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                                    </button>
                                </div>

                                {/* NEW: userId 输入（会话框下方，使用 label+htmlFor，去掉 aria-label） */}
                                <div className="mt-2 flex items-center gap-2">
                                    <label htmlFor="userIdInput" className="text-[11px] text-slate-500 dark:text-slate-400">
                                        userId
                                    </label>
                                    <input
                                        id="userIdInput"
                                        value={userId}
                                        onChange={(e) => setUserId(e.target.value)}
                                        placeholder="u1"
                                        className="h-8 w-[12rem] rounded-2xl border border-slate-300/70 dark:border-slate-700 bg-transparent px-3 text-sm outline-none"
                                    />
                                </div>


                                <div className="mt-2 text-[11px] text-slate-500 dark:text-slate-400">
                                    {USE_DEMO ? (
                                        <>
                                            Demo mode · streaming fake NDJSON. Set <code>USE_DEMO=false</code> to connect your
                                            backend.
                                        </>
                                    ) : (
                                        <>
                                            POST {joinUrl(BASE_URL, NDJSON_PATH)} · Accept: application/x-ndjson · userId=
                                            <code>{userId || "u1"}</code> · conversationId=
                                            <code>{activeId}</code>
                                        </>
                                    )}
                                </div>
                            </div>
                        </div>
                    </motion.main>
                </div>
            </div>

            {/* Dialogs */}
            <ConfirmDialog
                open={!!confirmDelId}
                title="删除会话"
                message="确定要删除这个会话吗？此操作不可撤销。"
                danger
                confirmText="删除"
                cancelText="取消"
                onConfirm={() => {
                    if (confirmDelId) {
                        const id = confirmDelId;
                        setConfirmDelId(null);
                        doDelete(id);
                    }
                }}
                onCancel={() => setConfirmDelId(null)}
            />

            <RenameDialog
                open={!!renameId}
                initial={renameId ? conversations.find((c) => c.id === renameId)?.title || UNTITLED : UNTITLED}
                onSave={(t) => {
                    if (renameId) {
                        doRename(renameId, t);
                        setRenameId(null);
                    }
                }}
                onCancel={() => setRenameId(null)}
            />
        </div>
    );
}

function EmptyState() {
    return (
        <div className="h-full w-full flex items-center justify-center">
            <div className="text-center max-w-sm">
                <div className="mx-auto h-12 w-12 rounded-2xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center mb-3">
                    <Bot className="h-6 w-6" />
                </div>
                <div className="font-semibold">Start a conversation</div>
                <div className="text-sm text-slate-500 dark:text-slate-400 mt-1">Use the left sidebar to manage chats.</div>
            </div>
        </div>
    );
}

function ChatBubble({ role, text, typing }: { role: Role; text: string; typing?: boolean }) {
    const isUser = role === "user";
    return (
        <div className={`flex gap-3 ${isUser ? "justify-end" : "justify-start"}`}>
            {!isUser && (
                <div className="shrink-0 h-8 w-8 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center">
                    <Bot className="h-4 w-4" />
                </div>
            )}
            <div
                className={`max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-6 shadow-sm border ${
                    isUser ? "bg-sky-600 text-white border-sky-700" : "bg-white/85 dark:bg-slate-900/60 border-slate-200 dark:border-slate-800"
                }`}
            >
                {typing ? (
                    <TypingDots />
                ) : isUser ? (
                    text || <span className="opacity-60">…</span>
                ) : (
                    <SafeMarkdown source={text} allowHtml={false} highlight={true} proseClassName="m-0 p-0" />
                )}
            </div>
            {isUser && <div className="shrink-0" />}
        </div>
    );
}

function TypingDots() {
    return (
        <div className="inline-flex items-center gap-1">
      <span
          className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce"
          style={{ animationDelay: "-0.25s" }}
      />
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce" />
            <span
                className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce"
                style={{ animationDelay: "0.25s" }}
            />
        </div>
    );
}
