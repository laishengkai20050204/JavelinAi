// src/pages/JavelinMinimalChat.tsx
import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { listSavedTools } from "../features/clientTools/storage";
import { compileGraphToClientTool } from "../features/clientTools/compile";
import type { ClientTool } from "../features/clientTools/types";
import SafeMarkdown from "../components/SafeMarkdown";
import { ChatFileUploader } from "../components/ChatFileUploader";
import type { UploadFileResponse } from "../components/ChatFileUploader";
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

type PendingLink = { url: string; target: "_self" | "_blank"; ts: number };

// ===== 常量 / 工具函数 ======================================================

const USE_DEMO = false;
const CURRENT_ORIGIN =
    typeof window !== "undefined" && window.location ? window.location.origin : "";
const BASE_URL = CURRENT_ORIGIN || "/";
const NDJSON_PATH = "/ai/v3/chat/step/ndjson";
const STORAGE_KEY = "javelin.chat.v3";
const UNTITLED = "新会话";
const TOOLS_SELECTION_KEY = "javelin.chat.v3.tools.selection";

const newId = () => Math.random().toString(36).slice(2, 10);
const joinUrl = (a: string, b: string) => (a.endsWith("/") ? a.slice(0, -1) : a) + b;
const isRecord = (v: unknown): v is Record<string, unknown> =>
    !!v && typeof v === "object" && !Array.isArray(v);

// ===== 类型 ================================================================

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
    fromRemote?: boolean;
}

interface RemoteConversationMeta {
    conversationId: string;
    messageCount?: number;
    lastMessageAt?: string | null;
}

export interface StepEventLine {
    event?: string;
    ts?: string | number;
    data?: Record<string, unknown>;
    [k: string]: unknown;
}

// 角色兜底
function coerceRole(raw?: string): Role {
    if (!raw) return "user";
    if (raw === "user" || raw === "assistant" || raw === "tool" || raw === "system") return raw;
    const s = raw.toLowerCase();
    if (s.includes("user")) return "user";
    if (s.includes("tool")) return "tool";
    if (s.includes("system")) return "system";
    return "assistant";
}

const safeParseJson = (s?: string) => {
    try {
        return s ? JSON.parse(s) : {};
    } catch {
        return {};
    }
};

// ===== NDJSON 解析 ==========================================================

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

// Demo NDJSON（仅 USE_DEMO=true 时使用）
async function* demoNdjson(userText: string) {
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
    yield { event: "started", ts: Date.now(), data: { stepId: newId() } };
    await wait(200);
    yield { event: "step", ts: Date.now(), data: { type: "decision" } };
    await wait(150);
    yield { event: "final", ts: Date.now(), data: { type: "final" } };
}

// ===== SSE(OpenAI 格式) 解析 ==============================================

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

// ===== Dialog 组件 ==========================================================

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

// ===== 主组件 ===============================================================

export default function JavelinMinimalChat() {
    // 会话
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
    const [userId, setUserId] = useState<string>("u1");
    const [toolPanelOpen, setToolPanelOpen] = useState(true);
    const [exposed, setExposed] = useState<Record<string, boolean>>({});

    // Pending open-url 队列
    type PendingLinkItem = PendingLink & { id: string };
    const [pendingLinks, setPendingLinks] = useState<PendingLinkItem[]>([]);

    // Client tools
    const [clientTools, setClientTools] = useState<ClientTool[]>([]);

    // 远程历史
    const [remoteConversations, setRemoteConversations] = useState<RemoteConversationMeta[]>([]);
    const [remoteLoading, setRemoteLoading] = useState(false);
    const [remoteError, setRemoteError] = useState<string | null>(null);
    const [remoteImporting, setRemoteImporting] = useState<string | null>(null);

    // refs
    const abortRef = useRef<AbortController | null>(null);
    const listRef = useRef<HTMLDivElement | null>(null);
    const esRef = useRef<EventSource | null>(null);
    const sendingRef = useRef(false);
    const streamRef = useRef<string | null>(null);

    const accumulatedRef = useRef<string>("");
    const currentSseStepIdRef = useRef<string | null>(null);
    const openedSseStepIdsRef = useRef<Set<string>>(new Set());
    const processedCallIdsRef = useRef<Set<string>>(new Set());
    const remoteListReqRef = useRef(0);

    // ===== 派生状态 =====

    const messages = useMemo<ChatMessage[]>(
        () => convMessages[activeId] ?? [],
        [convMessages, activeId],
    );

    // 合并本地 + 远程元信息
    const remoteMetaMap = useMemo(() => {
        const map = new Map<string, RemoteConversationMeta>();
        for (const meta of remoteConversations) {
            if (meta?.conversationId) map.set(meta.conversationId, meta);
        }
        return map;
    }, [remoteConversations]);

    const combinedConversations = useMemo<ConversationMeta[]>(() => {
        const locals = conversations.map((c) => ({ ...c, fromRemote: false }));
        const localIds = new Set(locals.map((c) => c.id));
        const remote = remoteConversations
            .filter((meta) => !!meta.conversationId && !localIds.has(meta.conversationId))
            .map((meta) => {
                const ts = meta.lastMessageAt ? Date.parse(meta.lastMessageAt) : 0;
                const safeTs = Number.isNaN(ts) ? 0 : ts;
                return {
                    id: meta.conversationId,
                    title: meta.conversationId,
                    createdAt: safeTs,
                    updatedAt: safeTs,
                    fromRemote: true,
                } as ConversationMeta;
            });
        return [...locals, ...remote].sort((a, b) => b.updatedAt - a.updatedAt);
    }, [conversations, remoteConversations]);

    // 合并 assistant / tool 连续消息成一块
    const displayMessages = useMemo<ChatMessage[]>(() => {
        const grouped: ChatMessage[] = [];
        let buffer: ChatMessage | null = null;
        const flush = () => {
            if (buffer) {
                grouped.push(buffer);
                buffer = null;
            }
        };
        for (const msg of messages) {
            if (msg.role === "assistant" || msg.role === "tool") {
                if (!buffer) {
                    buffer = { ...msg };
                } else {
                    buffer = {
                        ...buffer,
                        id: `${buffer.id}-${msg.id}`,
                        content: buffer.content
                            ? `${buffer.content}\n\n${msg.content}`
                            : msg.content,
                        ts: msg.ts,
                    };
                }
                continue;
            }
            flush();
            grouped.push(msg);
        }
        flush();
        return grouped;
    }, [messages]);

    // 会话过滤
    const filteredConversations = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return combinedConversations;
        return combinedConversations.filter((c) =>
            (c.title || UNTITLED).toLowerCase().includes(q),
        );
    }, [combinedConversations, query]);

    // Debug 事件切分
    const ndjsonEvents = useMemo(
        () => events.filter((e) => !(typeof e?.event === "string" && e.event.startsWith("sse-"))),
        [events],
    );
    const sseEvents = useMemo(
        () => events.filter((e) => typeof e?.event === "string" && e.event.startsWith("sse-")),
        [events],
    );

    // ===== Effect: pending-open-url 监听 =====
    useEffect(() => {
        const onEvt = (e: Event) => {
            const d = (e as CustomEvent).detail as PendingLink;
            const id =
                (crypto as any)?.randomUUID?.() ?? String(Date.now()) + Math.random();
            setPendingLinks((prev) => {
                const dup = prev.some(
                    (x) =>
                        x.url === d.url &&
                        x.target === d.target &&
                        d.ts - x.ts < 5000,
                );
                return dup ? prev : [...prev, { ...d, id }];
            });
            console.log("[UI] pending open-url:", d);
        };
        window.addEventListener("javelin:pending-open-url", onEvt as EventListener);
        return () =>
            window.removeEventListener(
                "javelin:pending-open-url",
                onEvt as EventListener,
            );
    }, []);

    // ===== Effect: 加载 clientTools =====
    useEffect(() => {
        (async () => {
            const saved = listSavedTools();
            const compiled: ClientTool[] = [];
            for (const b of saved) {
                compiled.push(
                    await compileGraphToClientTool(b.graph, {
                        name: b.meta.name,
                        description: b.meta.description,
                    }),
                );
            }
            setClientTools(compiled);
        })();
    }, []);

    // ===== Effect: 加载工具开关状态 =====
    useEffect(() => {
        try {
            const raw = localStorage.getItem(TOOLS_SELECTION_KEY);
            if (raw) setExposed(JSON.parse(raw) as Record<string, boolean>);
        } catch {
            // ignore
        }
    }, []);

    // 当 clientTools 变化时，对齐开关并持久化
    useEffect(() => {
        setExposed((prev: Record<string, boolean>) => {
            const next: Record<string, boolean> = { ...prev };
            for (const t of clientTools) {
                const name = t.manifest?.name;
                if (name && next[name] === undefined) next[name] = true; // 默认启用
            }
            for (const k of Object.keys(next)) {
                if (!clientTools.some((t) => t.manifest?.name === k)) {
                    delete next[k];
                }
            }
            try {
                localStorage.setItem(TOOLS_SELECTION_KEY, JSON.stringify(next));
            } catch {
                // ignore
            }
            return next;
        });
    }, [clientTools]);

    // ===== Effect: 自动滚动 =====
    useEffect(() => {
        listRef.current?.scrollTo({
            top: listRef.current.scrollHeight,
            behavior: "smooth",
        });
    }, [messages, busy, activeId]);

    // ===== Effect: 从 localStorage 恢复会话 =====
    useEffect(() => {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const st = JSON.parse(raw);
                setConversations(st.conversations ?? []);
                setConvMessages(st.convMessages ?? {});
                setActiveId(st.activeId ?? "");
                setUserId(st.userId ?? "u1");
            } else {
                const id = `c_${newId()}`;
                const meta: ConversationMeta = {
                    id,
                    title: UNTITLED,
                    createdAt: Date.now(),
                    updatedAt: Date.now(),
                };
                setConversations([meta]);
                setConvMessages({ [id]: [] });
                setActiveId(id);
            }
        } catch {
            const id = `c_${newId()}`;
            const meta: ConversationMeta = {
                id,
                title: UNTITLED,
                createdAt: Date.now(),
                updatedAt: Date.now(),
            };
            setConversations([meta]);
            setConvMessages({ [id]: [] });
            setActiveId(id);
        }
    }, []);

    // ===== Effect: 保存到 localStorage =====
    useEffect(() => {
        try {
            localStorage.setItem(
                STORAGE_KEY,
                JSON.stringify({ conversations, convMessages, activeId, userId }),
            );
        } catch {
            // ignore
        }
    }, [conversations, convMessages, activeId, userId]);

    // ===== Effect: userId 变化时刷新远程历史 =====
    useEffect(() => {
        refreshRemoteHistory();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [userId]);

    // ===== Effect: 卸载清理 =====
    useEffect(() => {
        return () => {
            abortRef.current?.abort();
            if (esRef.current) {
                esRef.current.close();
                esRef.current = null;
            }
        };
    }, []);

    // ===== 工具函数 / 事件处理（在组件内部） =============================

    function openOne(id: string) {
        setPendingLinks((prev) => {
            const it = prev.find((x) => x.id === id);
            if (!it) return prev;
            const target = it.target || "_blank";
            const w = window.open(it.url, target, "noopener");
            if (w) {
                return prev.filter((x) => x.id !== id);
            }
            return prev;
        });
    }

    function touchActive() {
        setConversations((prev) =>
            prev.map((c) =>
                c.id === activeId ? { ...c, updatedAt: Date.now() } : c,
            ),
        );
    }

    const setMsgs = (fn: (m: ChatMessage[]) => ChatMessage[]) =>
        setConvMessages((prev: Record<string, ChatMessage[]>) => {
            const current = prev[activeId] ?? [];
            const nextForConv = fn(current);
            return { ...prev, [activeId]: nextForConv };
        });

    function addMessage(m: ChatMessage) {
        setMsgs((prev) => [...prev, m]);
        touchActive();
    }

    function normalizeFileUrl(url: string): string {
        if (typeof window === "undefined") return url;
        try {
            const u = new URL(url, window.location.origin);
            const origin = window.location.origin;
            let path = u.pathname || "/";
            if (!path.startsWith("/minio/")) {
                if (!path.startsWith("/")) path = "/" + path;
                path = "/minio" + path;
            }
            return origin + path + u.search + u.hash;
        } catch {
            return url;
        }
    }

    function handleFileUploaded(resp: UploadFileResponse, file: File) {
        const sizeKB = Math.max(1, Math.round(resp.size / 1024));
        const normUrl = normalizeFileUrl(resp.url);

        addMessage({
            id: newId(),
            role: "assistant",
            ts: Date.now(),
            content:
                `已上传文件：**${file.name}**（约 ${sizeKB} KB）\n\n` +
                `- 存储桶：\`${resp.bucket}\`\n` +
                `- 对象 Key：\`${resp.objectKey}\`\n` +
                (resp.contentType ? `- 类型：\`${resp.contentType}\`\n` : "") +
                `- 下载链接：${normUrl}\n\n` +
                `后续提问时可以引用这个链接，或让工具去下载分析。`,
        });
    }

    const replaceDraftContent = (text: string) => {
        setConvMessages((prev: Record<string, ChatMessage[]>) => {
            const msgs = prev[activeId] ?? [];
            const next = [...msgs];
            const last = next[next.length - 1];
            if (last && last.role === "assistant" && last.id === "draft") {
                if (last.content === text) {
                    return prev;
                }
                next[next.length - 1] = {
                    ...last,
                    content: text,
                    ts: Date.now(),
                };
            } else {
                next.push({
                    id: "draft",
                    role: "assistant",
                    content: text,
                    ts: Date.now(),
                });
            }
            return { ...prev, [activeId]: next };
        });
    };

    const ensureDraftExists = () => replaceDraftContent("");
    const finalizeDraft = () => {
        setConvMessages((prev: Record<string, ChatMessage[]>) => {
            const msgs = prev[activeId] ?? [];
            const next = msgs.map((m) =>
                m.id === "draft" ? { ...m, id: newId(), ts: Date.now() } : m,
            );
            return { ...prev, [activeId]: next };
        });
    };

    function manifestList() {
        const enabledNames = new Set(
            Object.entries(exposed)
                .filter(([, on]) => !!on)
                .map(([name]) => name),
        );
        return clientTools
            .filter((t) => enabledNames.has(t.manifest?.name))
            .map((t) => ({ type: "function", function: t.manifest }));
    }

    function toClientDataPayload(out: any) {
        if (out && typeof out === "object" && "payload" in out) {
            return { payload: (out as any).payload };
        }
        if (typeof out === "string") {
            return { payload: { type: "text", value: out } };
        }
        if (out && typeof out === "object" && "result" in out) {
            return { payload: { type: "json", value: (out as any).result } };
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
            clientResults: [clientResult],
        };

        const res = await fetch(joinUrl(BASE_URL, NDJSON_PATH), {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/x-ndjson",
            },
            body: JSON.stringify(body),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        for await (const line of ndjsonIterator(res)) {
            handleStepNdjsonLine(line);
        }
    }

    async function executeClientTool(
        stepId: string,
        name: string,
        callId: string,
        rawArgs: any,
    ) {
        console.log("[executeClientTool] start", { stepId, name, callId, rawArgs });

        const tool = clientTools.find((t) => t.manifest.name === name);
        if (!tool) {
            const clientResult = {
                callId,
                name,
                status: "ERROR",
                reused: false,
                arguments: rawArgs || {},
                data: {
                    payload: {
                        type: "text",
                        value: `client tool not found: ${name}`,
                    },
                },
            };
            await continueViaNdjson(stepId, clientResult);
            return;
        }
        try {
            console.log("[executeClientTool] executing", { name, args: rawArgs });
            const out = await tool.execute(rawArgs ?? {}, {
                userId,
                conversationId: activeId,
            });
            console.log("[executeClientTool] executed", { name, out });
            const clientResult = {
                callId,
                name,
                status: "SUCCESS",
                reused: false,
                arguments: rawArgs || {},
                data: toClientDataPayload(out),
            };
            await continueViaNdjson(stepId, clientResult);
        } catch (e: any) {
            const clientResult = {
                callId,
                name,
                status: "ERROR",
                reused: false,
                arguments: rawArgs || {},
                data: {
                    payload: {
                        type: "text",
                        value: String(e?.message || e),
                    },
                },
            };
            await continueViaNdjson(stepId, clientResult);
        }
    }

    function scheduleClientCall(stepId: string, call: any) {
        const name =
            call?.name ||
            call?.function?.name ||
            call?.tool?.name ||
            call?.tool_name;

        const callId =
            call?.id ||
            call?.callId ||
            call?.tool_call_id ||
            (crypto as any)?.randomUUID?.() ||
            String(Date.now());

        if (!name) return;
        if (processedCallIdsRef.current.has(callId)) return;
        processedCallIdsRef.current.add(callId);

        let args = call?.arguments ?? call?.args ?? call?.function?.arguments ?? {};
        if (typeof args === "string") {
            try {
                args = JSON.parse(args);
            } catch {
                // ignore
            }
        }

        console.log("[scheduleClientCall]", {
            stepId,
            name,
            callId,
            raw: call,
            parsedArgs: args,
        });

        void executeClientTool(stepId, name, callId, args);
    }

    function handleToolMessage(stepId: string, obj: any): boolean {
        // A) OpenAI tool_call
        if (
            obj?.type === "tool_call" &&
            (obj?.xExecTarget === "client" ||
                obj?.["x-execTarget"] === "client")
        ) {
            const callId =
                obj.tool_call_id ||
                (crypto as any)?.randomUUID?.() ||
                String(Date.now());
            const args =
                typeof obj.arguments === "string"
                    ? safeParseJson(obj.arguments)
                    : obj.arguments || {};
            void executeClientTool(stepId, obj.name, callId, args);
            return true;
        }
        // B) 自定义 client_tool_call
        if (obj?.type === "client_tool_call") {
            const callId =
                obj.callId ||
                (crypto as any)?.randomUUID?.() ||
                String(Date.now());
            void executeClientTool(stepId, obj.name, callId, obj.args || {});
            return true;
        }
        // C) 一般 {name, arguments} 形式
        if (obj && obj.name && (obj.arguments !== undefined || obj.args !== undefined)) {
            scheduleClientCall(stepId, obj);
            return true;
        }
        return false;
    }

    function handleStepNdjsonLine(line: StepEventLine) {
        setEvents((prev) => [...prev, line]);

        const sidTop =
            isRecord(line) && typeof line["stepId"] === "string"
                ? String(line["stepId"])
                : null;
        const sidData =
            isRecord(line?.data) &&
            typeof (line.data as any)["stepId"] === "string"
                ? String((line.data as any)["stepId"])
                : null;
        const sid = sidTop || sidData || null;

        if (!USE_DEMO && sid && !openedSseStepIdsRef.current.has(sid)) {
            openedSseStepIdsRef.current.add(sid);
            currentSseStepIdRef.current = sid;
            startSSE(sid);
        }

        if (
            isRecord(line) &&
            line["type"] === "clientCalls" &&
            Array.isArray((line as any).calls)
        ) {
            const calls = (line as any).calls as Array<any>;
            for (const c of calls) scheduleClientCall(sid || "", c);
        }
        if (
            isRecord(line?.data) &&
            (line.data as any)["type"] === "clientCalls" &&
            Array.isArray((line.data as any)["calls"])
        ) {
            const calls = (line.data as any)["calls"] as Array<any>;
            for (const c of calls) scheduleClientCall(sid || "", c);
        }

        if (
            isRecord(line?.data) &&
            (line.event === "final" ||
                line.event === "completed" ||
                ["final", "assistant_final", "done"].includes(
                    String(line.data["type"] ?? ""),
                ))
        ) {
            finalizeDraft();
        }
    }

    function buildRequest(userText: string, conversationId: string) {
        return {
            userId: userId.trim() || "u1",
            conversationId,
            q: userText,
            toolChoice: "auto",
            responseMode: "step-json-ndjson",
            clientTools: manifestList(),
        };
    }

    async function* fetchNdjson(
        userText: string,
        conversationId: string,
        signal?: AbortSignal,
    ) {
        const payload = buildRequest(userText, conversationId);
        const res = await fetch(joinUrl(BASE_URL, NDJSON_PATH), {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/x-ndjson",
            },
            body: JSON.stringify(payload),
            signal,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        for await (const line of ndjsonIterator(res)) {
            yield line;
        }
    }

    async function handleSend(text?: string) {
        if (sendingRef.current) return;
        const content = (text ?? input).trim();
        if (!content || busy || !activeId) return;

        sendingRef.current = true;
        setInput("");
        setBusy(true);
        setEvents([]);

        addMessage({ id: newId(), role: "user", content, ts: Date.now() });

        const meta = conversations.find((c) => c.id === activeId);
        if (meta && (!meta.title || meta.title === UNTITLED)) {
            const short = content.split(/\s+/).filter(Boolean).join(" ").slice(0, 18);
            doRename(activeId, short || UNTITLED);
        }

        ensureDraftExists();

        const controller = new AbortController();
        abortRef.current = controller;
        const streamId = newId();
        streamRef.current = streamId;

        try {
            const iterable = USE_DEMO
                ? demoNdjson(content)
                : fetchNdjson(content, activeId, controller.signal);
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

    function handleKey(e: React.KeyboardEvent<HTMLTextAreaElement>) {
        // @ts-expect-error IME composing flag
        if (e.isComposing || (e.nativeEvent as any)?.isComposing) return;
        if (e.key === "Enter" && !e.shiftKey) {
            if (e.repeat) {
                e.preventDefault();
                return;
            }
            e.preventDefault();
            void handleSend();
        }
    }

    function handleStop() {
        abortRef.current?.abort();
        abortRef.current = null;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        accumulatedRef.current = "";
        processedCallIdsRef.current.clear();
        setBusy(false);
    }

    function startSSE(stepId: string) {
        if (USE_DEMO) return;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        try {
            const url = joinUrl(
                BASE_URL,
                `/ai/v2/chat/sse?stepId=${encodeURIComponent(stepId)}`,
            );
            const es = new EventSource(url);
            esRef.current = es;

            accumulatedRef.current = "";
            processedCallIdsRef.current.clear();
            replaceDraftContent("");

            setEvents((prev) => [
                ...prev,
                { event: "sse-open", ts: Date.now(), data: { url } },
            ]);

            es.onmessage = (e: MessageEvent) => {
                if (e.data === "[DONE]") {
                    setEvents((prev) => [
                        ...prev,
                        { event: "sse-done", ts: Date.now(), data: {} },
                    ]);
                    return;
                }

                try {
                    const obj = JSON.parse(e.data) as unknown;

                    if (
                        isRecord(obj) &&
                        obj["type"] === "clientCalls" &&
                        Array.isArray((obj as any).calls)
                    ) {
                        for (const c of (obj as any).calls) scheduleClientCall(stepId, c);
                        setEvents((prev) => [
                            ...prev,
                            {
                                event: "sse-clientCalls",
                                ts: Date.now(),
                                data: obj as any,
                            },
                        ]);
                        return;
                    }

                    if (handleToolMessage(stepId, obj)) {
                        setEvents((prev) => [
                            ...prev,
                            {
                                event: "sse-client-tool",
                                ts: Date.now(),
                                data: obj as any,
                            },
                        ]);
                        return;
                    }

                    if (sseIsEmptyDelta(obj)) {
                        setEvents((prev) => [
                            ...prev,
                            {
                                event: "sse-message",
                                ts: Date.now(),
                                data: isRecord(obj) ? obj : { raw: e.data },
                            },
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
                        {
                            event: "sse-message",
                            ts: Date.now(),
                            data: isRecord(obj) ? obj : { raw: e.data },
                        },
                    ]);
                } catch {
                    setEvents((prev) => [
                        ...prev,
                        {
                            event: "sse-message-raw",
                            ts: Date.now(),
                            data: { raw: e.data },
                        },
                    ]);
                }
            };

            es.onerror = () => {
                setEvents((prev) => [
                    ...prev,
                    { event: "sse-error", ts: Date.now(), data: {} },
                ]);
                es.close();
                esRef.current = null;
                setBusy(false);
            };

            (["decision", "clientCalls", "tools", "status", "finished", "error"] as const).forEach(
                (name) => {
                    es.addEventListener(name, (ev: MessageEvent) => {
                        if (name === "clientCalls") {
                            try {
                                const payload = JSON.parse(ev.data);
                                const list = Array.isArray(payload?.calls)
                                    ? payload.calls
                                    : Array.isArray(payload)
                                        ? payload
                                        : [payload];
                                for (const c of list) scheduleClientCall(stepId, c);
                            } catch {
                                // ignore
                            }
                        }
                        setEvents((prev) => [
                            ...prev,
                            {
                                event: `sse-${name}`,
                                ts: Date.now(),
                                data: { raw: ev.data },
                            },
                        ]);
                        if (name === "finished") {
                            finalizeDraft();
                            setBusy(false);
                            es.close();
                            esRef.current = null;
                        }
                    });
                },
            );
        } catch (e) {
            setEvents((prev) => [
                ...prev,
                {
                    event: "sse-open-failed",
                    ts: Date.now(),
                    data: { error: String(e) },
                },
            ]);
        }
    }

    async function refreshRemoteHistory() {
        const normalizedUser = (userId || "").trim();
        if (!normalizedUser) {
            setRemoteConversations([]);
            setRemoteError(null);
            return;
        }
        const requestId = remoteListReqRef.current + 1;
        remoteListReqRef.current = requestId;
        setRemoteLoading(true);
        setRemoteError(null);
        try {
            const resp = await fetch(
                `/history/conversations/${encodeURIComponent(normalizedUser)}`,
            );
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            const body = await resp.json();
            if (remoteListReqRef.current !== requestId) {
                return;
            }
            const list = Array.isArray(body?.conversations)
                ? (body.conversations as RemoteConversationMeta[])
                : [];
            setRemoteConversations(list);
        } catch (err) {
            if (remoteListReqRef.current !== requestId) {
                return;
            }
            setRemoteConversations([]);
            setRemoteError(
                err instanceof Error ? err.message : "历史记录获取失败",
            );
        } finally {
            if (remoteListReqRef.current === requestId) {
                setRemoteLoading(false);
            }
        }
    }

    async function importHistoryConversation(targetConversationId: string) {
        const normalizedUser = (userId || "").trim();
        const trimmedConv = (targetConversationId || "").trim();
        if (!normalizedUser || !trimmedConv) {
            return;
        }
        setRemoteImporting(trimmedConv);
        setRemoteError(null);
        try {
            const resp = await fetch(
                `/history/conversations/${encodeURIComponent(
                    normalizedUser,
                )}/${encodeURIComponent(trimmedConv)}?limit=0`,
            );
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            const payload = await resp.json();
            const msgs = Array.isArray(payload)
                ? payload
                    .map((item) => {
                        if (!item || typeof item !== "object") return null;
                        const rawContent = (item as { content?: unknown }).content;
                        if (typeof rawContent !== "string" || !rawContent.trim()) {
                            return null;
                        }
                        const rawRole = (item as { role?: unknown }).role;
                        return {
                            role: coerceRole(
                                typeof rawRole === "string" ? rawRole : undefined,
                            ),
                            content: rawContent.trim(),
                        };
                    })
                    .filter(
                        (v): v is { role: Role; content: string } =>
                            !!v && typeof v.content === "string",
                    )
                : [];
            if (msgs.length === 0) {
                throw new Error("历史会话为空");
            }
            const now = Date.now();
            const imported: ChatMessage[] = msgs.map(({ role, content }, idx) => ({
                id: `${trimmedConv}-hist-${idx}-${now}`,
                role,
                content,
                ts: now + idx,
            }));
            setConvMessages((prev: Record<string, ChatMessage[]>) => ({
                ...prev,
                [trimmedConv]: imported,
            }));
            setConversations((prev) => {
                const existing = prev.find((c) => c.id === trimmedConv);
                if (existing) {
                    return prev.map((c) =>
                        c.id === trimmedConv
                            ? { ...c, updatedAt: now, fromRemote: false }
                            : c,
                    );
                }
                const titleSuffix = trimmedConv.slice(-6);
                const title = `历史会话 ${titleSuffix}`;
                return [
                    {
                        id: trimmedConv,
                        title,
                        createdAt: now,
                        updatedAt: now,
                    },
                    ...prev,
                ];
            });
            setRemoteConversations((prev) =>
                prev.filter((meta) => meta.conversationId !== trimmedConv),
            );
            setActiveId(trimmedConv);
            setEvents([]);
            setInput("");
        } catch (err) {
            setRemoteError(err instanceof Error ? err.message : "导入失败");
        } finally {
            setRemoteImporting(null);
        }
    }

    function createConversation() {
        abortRef.current?.abort();
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        accumulatedRef.current = "";

        const id = `c_${newId()}`;
        const meta: ConversationMeta = {
            id,
            title: UNTITLED,
            createdAt: Date.now(),
            updatedAt: Date.now(),
        };
        setConversations((prev) => [meta, ...prev]);
        setConvMessages((prev: Record<string, ChatMessage[]>) => ({
            ...prev,
            [id]: [],
        }));
        setActiveId(id);
        setEvents([]);
        setInput("");
        processedCallIdsRef.current.clear();
    }

    function requestRename(id: string) {
        setRenameId(id);
    }

    function doRename(id: string, title: string) {
        setConversations((prev) =>
            prev.map((c) =>
                c.id === id ? { ...c, title, updatedAt: Date.now() } : c,
            ),
        );
    }

    function requestDelete(id: string) {
        setConfirmDelId(id);
    }

    function doDelete(id: string) {
        const idx = conversations.findIndex((c) => c.id === id);
        const after = conversations.filter((c) => c.id !== id);

        setConvMessages((prev: Record<string, ChatMessage[]>) => {
            const next: Record<string, ChatMessage[]> = {};
            for (const key of Object.keys(prev)) {
                if (key !== id) {
                    next[key] = prev[key];
                }
            }
            return next;
        });

        let nextActive = activeId;
        let needBootstrap = false;

        if (activeId === id) {
            if (after.length > 0) {
                nextActive = after[Math.min(idx, after.length - 1)].id;
            } else {
                nextActive = `c_${newId()}`;
                needBootstrap = true;
            }
        }

        if (needBootstrap) {
            const meta: ConversationMeta = {
                id: nextActive,
                title: UNTITLED,
                createdAt: Date.now(),
                updatedAt: Date.now(),
            };
            setConversations([meta]);
            setConvMessages((prev: Record<string, ChatMessage[]>) => ({
                ...prev,
                [nextActive]: [],
            }));
        } else {
            setConversations(after);
        }

        setActiveId(nextActive);
        setEvents([]);
        setInput("");

        abortRef.current?.abort();
        abortRef.current = null;
        if (esRef.current) {
            esRef.current.close();
            esRef.current = null;
        }
        accumulatedRef.current = "";
    }

    // ===== JSX ===============================================================

    return (
        <div className="h-full w-full bg-white text-slate-900">
            <div className="flex h-full">
                {/* 左侧：会话栏 */}
                <aside
                    className={`flex h-full flex-col border-r border-slate-200 bg-slate-50 transition-[width] duration-200 ${
                        sidebarOpen ? "w-64" : "w-0 sm:w-64"
                    }`}
                >
                    <div
                        className={`flex h-full flex-col overflow-hidden ${
                            sidebarOpen
                                ? "opacity-100"
                                : "opacity-0 pointer-events-none sm:opacity-100 sm:pointer-events-auto"
                        }`}
                    >
                        {/* 顶部：标题 + 新建按钮 */}
                        <div className="flex items-center justify-between px-3 pt-3 pb-2">
                            <div className="flex items-center gap-2">
                                <div className="flex h-7 w-7 items-center justify-center rounded-xl bg-slate-900 text-white">
                                    <Sparkles className="h-3.5 w-3.5" />
                                </div>
                                <div>
                                    <div className="text-xs font-semibold">会话</div>
                                    <div className="text-[11px] text-slate-500">
                                        共 {conversations.length || 0} 个
                                    </div>
                                    <button
                                        type="button"
                                        onClick={refreshRemoteHistory}
                                        disabled={remoteLoading}
                                        className="mt-1 text-[10px] text-slate-500 underline-offset-2 hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                                    >
                                        {remoteLoading ? "同步历史中…" : "同步历史"}
                                    </button>
                                </div>
                            </div>
                            <button
                                onClick={createConversation}
                                className="inline-flex items-center justify-center rounded-xl border border-slate-300 bg-white px-2 py-1 text-[11px] hover:bg-slate-100"
                            >
                                <Plus className="h-3 w-3" />
                                <span className="ml-1">新建</span>
                            </button>
                        </div>

                        {/* 搜索框 */}
                        <div className="relative px-3 pb-2">
                            <Search className="pointer-events-none absolute left-5 top-2.5 h-3.5 w-3.5 text-slate-400" />
                            <input
                                value={query}
                                onChange={(e) => setQuery(e.target.value)}
                                placeholder="搜索会话…"
                                className="w-full rounded-2xl border border-slate-200 bg-white pl-8 pr-3 py-1.5 text-[13px] outline-none placeholder:text-slate-400 focus:border-slate-400"
                            />
                            {remoteError && (
                                <div className="mt-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-1.5 text-[11px] text-rose-600">
                                    {remoteError}
                                </div>
                            )}
                        </div>

                        {/* 工具开关 */}
                        <div className="px-3 pb-2">
                            <div className="rounded-2xl border border-slate-200 bg-white">
                                <button
                                    onClick={() => setToolPanelOpen((v) => !v)}
                                    className="flex w-full items-center justify-between px-3 py-2 text-[11px] font-medium"
                                >
                                    <span>
                                        客户端工具：已启用{" "}
                                        {
                                            Object.values(exposed).filter(Boolean)
                                                .length
                                        }
                                        /{clientTools.length}
                                    </span>
                                    <span className="text-[10px] text-slate-500">
                                        {toolPanelOpen ? "收起" : "展开"}
                                    </span>
                                </button>
                                {toolPanelOpen && (
                                    <div className="max-h-40 space-y-1 overflow-auto px-2 pb-2">
                                        {clientTools.length === 0 && (
                                            <div className="px-2 py-3 text-[11px] text-slate-500">
                                                暂无客户端工具（去 Tool Builder 新建）
                                            </div>
                                        )}
                                        {clientTools.map((t) => {
                                            const name =
                                                t.manifest?.name || "(unnamed)";
                                            const desc =
                                                t.manifest?.description || "";
                                            const on = !!exposed[name];
                                            return (
                                                <label
                                                    key={name}
                                                    className="flex items-center gap-2 rounded-xl px-2 py-1 hover:bg-slate-50"
                                                >
                                                    <input
                                                        type="checkbox"
                                                        checked={on}
                                                        onChange={(e) => {
                                                            const v = e.target
                                                                .checked;
                                                            setExposed(
                                                                (
                                                                    prev: Record<
                                                                        string,
                                                                        boolean
                                                                    >,
                                                                ) => {
                                                                    const next = {
                                                                        ...prev,
                                                                        [name]: v,
                                                                    };
                                                                    try {
                                                                        localStorage.setItem(
                                                                            TOOLS_SELECTION_KEY,
                                                                            JSON.stringify(
                                                                                next,
                                                                            ),
                                                                        );
                                                                    } catch {
                                                                        // ignore
                                                                    }
                                                                    return next;
                                                                },
                                                            );
                                                        }}
                                                    />
                                                    <div className="min-w-0">
                                                        <div className="truncate text-xs font-medium">
                                                            {name}
                                                        </div>
                                                        {desc && (
                                                            <div className="truncate text-[11px] text-slate-500">
                                                                {desc}
                                                            </div>
                                                        )}
                                                    </div>
                                                </label>
                                            );
                                        })}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* 会话列表 */}
                        <div className="flex-1 overflow-y-auto px-3 pb-3">
                            {filteredConversations.length === 0 && (
                                <div className="py-6 text-center text-xs text-slate-500">
                                    没有匹配的会话
                                </div>
                            )}
                            <div className="space-y-1">
                                {filteredConversations.map((c) => {
                                    const isActive = c.id === activeId;
                                    const isRemote = !!c.fromRemote;
                                    const isImporting =
                                        isRemote && remoteImporting === c.id;
                                    const handleSelect = () => {
                                        if (isRemote) {
                                            if (!isImporting) {
                                                void importHistoryConversation(
                                                    c.id,
                                                );
                                            }
                                            return;
                                        }
                                        setActiveId(c.id);
                                        setEvents([]);
                                        setInput("");
                                    };
                                    const lastMsg =
                                        convMessages[c.id]?.[
                                        convMessages[c.id].length - 1
                                            ];
                                    return (
                                        <div
                                            key={c.id}
                                            role="button"
                                            tabIndex={0}
                                            onClick={handleSelect}
                                            onKeyDown={(e) => {
                                                if (
                                                    e.key === "Enter" ||
                                                    e.key === " "
                                                ) {
                                                    e.preventDefault();
                                                    handleSelect();
                                                }
                                            }}
                                            className={`w-full rounded-2xl border px-3 py-2 text-left text-sm transition ${
                                                isRemote
                                                    ? "border-slate-300 bg-white hover:border-slate-400 hover:bg-slate-50 text-slate-900"
                                                    : isActive
                                                        ? "border-slate-900 bg-slate-900 text-white"
                                                        : "border-transparent bg-transparent hover:bg-slate-100"
                                            } ${
                                                isImporting
                                                    ? "cursor-wait opacity-60"
                                                    : ""
                                            }`}
                                        >
                                            <div className="flex items-center gap-2">
                                                <div className="flex-1 min-w-0 truncate text-xs font-medium">
                                                    {c.title || UNTITLED}
                                                </div>
                                                {!isRemote && (
                                                    <div className="flex items-center gap-1 text-[10px] text-slate-400">
                                                        <button
                                                            type="button"
                                                            onPointerDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onMouseDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                requestRename(
                                                                    c.id,
                                                                );
                                                            }}
                                                            className="rounded p-0.5 hover:bg-slate-100"
                                                            title="重命名"
                                                        >
                                                            <Pencil className="h-3 w-3" />
                                                        </button>
                                                        <button
                                                            type="button"
                                                            onPointerDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onMouseDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                requestDelete(
                                                                    c.id,
                                                                );
                                                            }}
                                                            className="rounded p-0.5 hover:bg-slate-100"
                                                            title="删除"
                                                        >
                                                            <Trash2 className="h-3 w-3" />
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                            <div className="mt-0.5 text-[11px] text-slate-500 line-clamp-2">
                                                {lastMsg?.content ?? "(空)"}
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>

                        {/* 左下角 userId 输入 */}
                        <div className="border-t border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-600">
                            <div className="mb-1 text-[10px] uppercase tracking-wide text-slate-400">
                                用户标识（userId）
                            </div>
                            <div className="flex items-center gap-2">
                                <input
                                    id="userIdInput"
                                    value={userId}
                                    onChange={(e) => setUserId(e.target.value)}
                                    placeholder="u1"
                                    className="h-7 flex-1 rounded-2xl border border-slate-300 bg-white px-3 text-xs outline-none placeholder:text-slate-400 focus:border-slate-500"
                                />
                            </div>
                        </div>
                    </div>
                </aside>

                {/* 右侧主区域 */}
                <div className="flex h-full flex-1 flex-col">
                    {/* 顶部栏 */}
                    <header className="flex h-12 flex-none items-center justify-between border-b border-slate-200 bg-white px-3 sm:px-6">
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => setSidebarOpen((v) => !v)}
                                className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-slate-300 bg-white hover:bg-slate-100 sm:hidden"
                                title="切换会话栏"
                            >
                                <Menu className="h-4 w-4" />
                            </button>
                            <div className="text-sm font-medium">Javelin · Chat</div>
                            <span className="hidden text-xs text-slate-500 sm:inline">
                                {USE_DEMO
                                    ? "Demo 模式"
                                    : "已连接后端 · SSE + NDJSON"}
                            </span>
                        </div>
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => setShowDebug((v) => !v)}
                                className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-2.5 py-1 text-[11px] hover:bg-slate-100"
                            >
                                <Bug className="h-3.5 w-3.5" />
                                {showDebug ? "隐藏事件" : "事件"}
                            </button>
                            {busy ? (
                                <button
                                    onClick={handleStop}
                                    className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-3 py-1.5 text-[11px] hover:bg-slate-100"
                                >
                                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                    停止
                                </button>
                            ) : (
                                <button
                                    onClick={createConversation}
                                    className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-3 py-1.5 text-[11px] hover:bg-slate-100"
                                >
                                    <Plus className="h-3.5 w-3.5" />
                                    新对话
                                </button>
                            )}
                        </div>
                    </header>

                    {/* 中间：消息 + 调试 + 输入区 */}
                    <main className="flex flex-1 justify-center bg-white overflow-hidden">
                        <div className="flex h-full w-full max-w-3xl flex-col px-3 sm:px-6">
                            {/* 消息 & 调试 */}
                            <div className="flex-1 min-h-0 flex flex-col">
                                {/* 消息列表 */}
                                <div
                                    ref={listRef}
                                    className="flex-1 min-h-0 flex flex-col overflow-y-auto py-4"
                                >
                                    {messages.length === 0 && <EmptyState />}

                                    <div className="space-y-3">
                                        {displayMessages.map((m) => (
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

                                {/* 调试事件 */}
                                {showDebug && (
                                    <div className="mb-3 grid gap-3 border-t border-slate-200 pt-3 text-xs md:grid-cols-2">
                                        <div className="rounded-xl border border-slate-200 bg-slate-50 p-2">
                                            <div className="mb-1 text-[11px] font-medium text-slate-500">
                                                NDJSON Events
                                            </div>
                                            <div className="max-h-48 overflow-auto font-mono text-[11px] leading-relaxed whitespace-pre-wrap">
                                                {ndjsonEvents.map((e, i) => (
                                                    <div
                                                        key={`n-${i}`}
                                                        className="border-b border-slate-200/70 py-0.5 last:border-0"
                                                    >
                                                        {JSON.stringify(e)}
                                                    </div>
                                                ))}
                                                {ndjsonEvents.length === 0 && (
                                                    <div className="py-2 text-[11px] text-slate-500">
                                                        暂无 NDJSON 事件
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                        <div className="rounded-xl border border-slate-200 bg-slate-50 p-2">
                                            <div className="mb-1 text-[11px] font-medium text-slate-500">
                                                SSE Events
                                            </div>
                                            <div className="max-h-48 overflow-auto font-mono text-[11px] leading-relaxed whitespace-pre-wrap">
                                                {sseEvents.map((e, i) => (
                                                    <div
                                                        key={`s-${i}`}
                                                        className="border-b border-slate-200/70 py-0.5 last:border-0"
                                                    >
                                                        {JSON.stringify(e)}
                                                    </div>
                                                ))}
                                                {sseEvents.length === 0 && (
                                                    <div className="py-2 text-[11px] text-slate-500">
                                                        暂无 SSE 事件
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                )}
                            </div>

                            {/* 底部输入区 */}
                            <div className="flex-none border-t border-slate-200 bg-white py-3">
                                {/* 输入框 */}
                                <div className="relative rounded-3xl border border-slate-300 bg-slate-50 px-4 py-2">
                                    <textarea
                                        value={input}
                                        onChange={(e) => setInput(e.target.value)}
                                        onKeyDown={handleKey}
                                        placeholder="向我提问任何问题…（Enter 发送，Shift+Enter 换行）"
                                        className="h-14 max-h-40 w-full resize-none bg-transparent py-2 pr-10 text-sm leading-6 outline-none placeholder:text-slate-500"
                                    />
                                    <button
                                        onClick={() => void handleSend()}
                                        disabled={busy || !input.trim() || !activeId}
                                        className="absolute bottom-2.5 right-2.5 inline-flex h-8 w-8 items-center justify-center rounded-full bg-slate-900 text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
                                        title="发送"
                                    >
                                        {busy ? (
                                            <Loader2 className="h-4 w-4 animate-spin" />
                                        ) : (
                                            <Send className="h-4 w-4" />
                                        )}
                                    </button>
                                </div>

                                {/* 文件上传 */}
                                <ChatFileUploader
                                    className="mt-2"
                                    baseUrl={BASE_URL}
                                    userId={userId}
                                    conversationId={activeId}
                                    onUploaded={handleFileUploaded}
                                    onError={(err) => {
                                        addMessage({
                                            id: newId(),
                                            role: "assistant",
                                            ts: Date.now(),
                                            content: `上传文件失败：\`${err.message}\``,
                                        });
                                    }}
                                />

                                {/* 接口信息 */}
                                <div className="mt-2 text-[11px] text-slate-500">
                                    {USE_DEMO ? (
                                        <>
                                            Demo 模式 · 正在使用本地模拟 NDJSON。
                                            修改 <code className="font-mono">USE_DEMO=false</code>{" "}
                                            即可连接后端。
                                        </>
                                    ) : (
                                        <>
                                            POST{" "}
                                            <code className="font-mono">
                                                {joinUrl(BASE_URL, NDJSON_PATH)}
                                            </code>{" "}
                                            · Accept:{" "}
                                            <code className="font-mono">
                                                application/x-ndjson
                                            </code>{" "}
                                            · userId=
                                            <code className="font-mono">
                                                {userId || "u1"}
                                            </code>{" "}
                                            · conversationId=
                                            <code className="font-mono">{activeId}</code>
                                        </>
                                    )}
                                </div>
                            </div>
                        </div>
                    </main>
                </div>
            </div>

            {/* 删除 / 重命名 Dialog */}
            <ConfirmDialog
                open={!!confirmDelId}
                title="删除会话"
                message="确定要删除这个会话吗？此操作不可恢复。"
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
                initial={
                    renameId
                        ? conversations.find((c) => c.id === renameId)?.title ||
                        UNTITLED
                        : UNTITLED
                }
                onSave={(t) => {
                    if (renameId) {
                        doRename(renameId, t);
                        setRenameId(null);
                    }
                }}
                onCancel={() => setRenameId(null)}
            />

            {/* 待打开链接队列 */}
            {pendingLinks.length > 0 && (
                <div className="fixed bottom-4 right-4 z-50 w-[22rem] max-w-[92vw] rounded-xl border border-slate-200 bg-white p-3 shadow-xl">
                    <div className="mb-2 flex items-center justify-between">
                        <div className="text-sm font-medium">
                            选择要打开的链接（{pendingLinks.length}）
                        </div>
                        <button
                            onClick={() => setPendingLinks([])}
                            className="rounded-lg border border-slate-300 px-2 py-1 text-[11px] hover:bg-slate-50"
                        >
                            清空
                        </button>
                    </div>

                    <div className="max-h-56 space-y-2 overflow-auto">
                        {pendingLinks.map((it) => (
                            <div
                                key={it.id}
                                className="flex items-center gap-2 rounded-lg bg-slate-50 px-2 py-1.5"
                            >
                                <div className="min-w-0 flex-1 text-xs">
                                    <div className="font-mono truncate text-slate-900">
                                        {(() => {
                                            try {
                                                return new URL(it.url).host;
                                            } catch {
                                                return it.url;
                                            }
                                        })()}
                                    </div>
                                    <div className="truncate text-[11px] text-slate-500">
                                        {it.url}
                                    </div>
                                </div>
                                <button
                                    onMouseDown={(e) => {
                                        e.preventDefault();
                                        openOne(it.id);
                                    }}
                                    className="rounded-lg bg-slate-900 px-2 py-1 text-[11px] font-medium text-white hover:bg-slate-800"
                                    title="打开此链接"
                                >
                                    打开
                                </button>
                                <button
                                    onClick={() =>
                                        setPendingLinks((prev) =>
                                            prev.filter((x) => x.id !== it.id),
                                        )
                                    }
                                    className="rounded-lg border border-slate-300 px-2 py-1 text-[11px] hover:bg-slate-50"
                                    title="移除"
                                >
                                    ×
                                </button>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}

// ===== 辅助 UI 组件 =========================================================

function EmptyState() {
    return (
        <div className="h-full w-full flex items-center justify-center">
            <div className="text-center max-w-sm">
                <div className="mx-auto h-12 w-12 rounded-2xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center mb-3">
                    <Bot className="h-6 w-6" />
                </div>
                <div className="font-semibold">开始一段新对话</div>
                <div className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                    使用左侧栏管理会话历史。
                </div>
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
                    isUser
                        ? "bg-sky-600 text-white border-sky-700"
                        : "bg-white/85 dark:bg-slate-900/60 border-slate-200 dark:border-slate-800"
                }`}
            >
                {typing ? (
                    <TypingDots />
                ) : isUser ? (
                    text || <span className="opacity-60">…</span>
                ) : (
                    <SafeMarkdown
                        source={text}
                        allowHtml={false}
                        highlight={true}
                        proseClassName="m-0 p-0"
                    />
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
