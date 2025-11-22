// src/pages/JavelinMinimalChat.hooks.ts
import { useEffect, useMemo, useRef, useState } from "react";
import type React from "react";
import type { Dispatch, RefObject, SetStateAction } from "react";
import { listSavedTools } from "../features/clientTools/storage";
import { compileGraphToClientTool } from "../features/clientTools/compile";
import type { ClientTool } from "../features/clientTools/types";
import type { UploadFileResponse } from "../components/ChatFileUploader";
import { localPcControlTool } from "../features/clientTools/builtin/localPcControl";

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

export interface RemoteConversationMeta {
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

export type PendingLink = { url: string; target: "_self" | "_blank"; ts: number };
export type PendingLinkItem = PendingLink & { id: string };

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
    // eslint-disable-next-line no-constant-condition
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

// 角色兜底
function coerceRole(raw?: string): Role {
    if (!raw) return "user";
    if (raw === "user" || raw === "assistant" || raw === "tool" || raw === "system") {
        return raw;
    }
    const s = raw.toLowerCase();
    if (s.includes("user")) return "user";
    if (s.includes("tool")) return "tool";
    if (s.includes("system")) return "system";
    return "assistant";
}

// ===== Hook 对外接口类型 ====================================================

export interface UseJavelinMinimalChatResult {
    conversations: ConversationMeta[];
    activeId: string;
    convMessages: Record<string, ChatMessage[]>;
    sidebarOpen: boolean;
    setSidebarOpen: Dispatch<SetStateAction<boolean>>;
    query: string;
    setQuery: Dispatch<SetStateAction<string>>;
    busy: boolean;
    events: StepEventLine[];
    setEvents: Dispatch<SetStateAction<StepEventLine[]>>;
    showDebug: boolean;
    setShowDebug: Dispatch<SetStateAction<boolean>>;
    input: string;
    setInput: Dispatch<SetStateAction<string>>;
    confirmDelId: string | null;
    setConfirmDelId: Dispatch<SetStateAction<string | null>>;
    renameId: string | null;
    setRenameId: Dispatch<SetStateAction<string | null>>;
    userId: string;
    setUserId: Dispatch<SetStateAction<string>>;
    toolPanelOpen: boolean;
    setToolPanelOpen: Dispatch<SetStateAction<boolean>>;
    exposed: Record<string, boolean>;
    setExposed: Dispatch<SetStateAction<Record<string, boolean>>>;
    pendingLinks: PendingLinkItem[];
    setPendingLinks: Dispatch<SetStateAction<PendingLinkItem[]>>;
    clientTools: ClientTool[];
    remoteConversations: RemoteConversationMeta[];
    remoteLoading: boolean;
    remoteError: string | null;
    remoteImporting: string | null;
    listRef: RefObject<HTMLDivElement>;
    messages: ChatMessage[];
    displayMessages: ChatMessage[];
    ndjsonEvents: StepEventLine[];
    sseEvents: StepEventLine[];
    filteredConversations: ConversationMeta[];
    setActiveId: Dispatch<SetStateAction<string>>;
    handleSend: (text?: string) => Promise<void>;
    handleKey: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void;
    handleStop: () => void;
    refreshRemoteHistory: () => Promise<void>;
    importHistoryConversation: (targetConversationId: string) => Promise<void>;
    createConversation: () => void;
    requestRename: (id: string) => void;
    doRename: (id: string, title: string) => void;
    requestDelete: (id: string) => void;
    doDelete: (id: string) => void;
    handleFileUploaded: (resp: UploadFileResponse, file: File) => void;
    handleUploadError: (err: Error) => void;
    openOne: (id: string) => void;
    baseUrl: string;
    ndjsonPath: string;
    useDemo: boolean;
}

// ===== 主 Hook =============================================================

export function useJavelinMinimalChat(): UseJavelinMinimalChatResult {
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
                    // 这里不再使用 ...buffer，避免 TS2698
                    buffer = {
                        id: `${buffer.id}-${msg.id}`,
                        role: buffer.role,
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
            // eslint-disable-next-line no-console
            console.log("[UI] pending-open-url:", d);
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
                const tool = await compileGraphToClientTool(b.graph, {
                    name: b.meta.name,
                    description: b.meta.description,
                });
                if (tool) {
                    compiled.push(tool);
                }
            }

            // 把本机控制工具放在最前面，其它是 Rete 编译出来的工具
            setClientTools([localPcControlTool, ...compiled]);
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
    }, [clientTools, setExposed]);

    // ===== Effect: 自动滚动 =====
    useEffect(() => {
        if (listRef.current) {
            listRef.current.scrollTo({
                top: listRef.current.scrollHeight,
                behavior: "smooth",
            });
        }
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
        void refreshRemoteHistory();
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
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // ===== 工具函数 / 事件处理（在 hook 内） =============================

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

    function handleUploadError(err: Error) {
        addMessage({
            id: newId(),
            role: "assistant",
            ts: Date.now(),
            content: `上传文件失败：\`${err.message}\``,
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
        // eslint-disable-next-line no-console
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
            // eslint-disable-next-line no-console
            console.log("[executeClientTool] executing", { name, args: rawArgs });
            const out = await tool.execute(rawArgs ?? {}, {
                userId,
                conversationId: activeId,
            });
            // eslint-disable-next-line no-console
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

        // eslint-disable-next-line no-console
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

    // ===== 返回给页面使用 ====================================================

    return {
        conversations,
        activeId,
        convMessages,
        sidebarOpen,
        setSidebarOpen,
        query,
        setQuery,
        busy,
        events,
        setEvents,
        showDebug,
        setShowDebug,
        input,
        setInput,
        confirmDelId,
        setConfirmDelId,
        renameId,
        setRenameId,
        userId,
        setUserId,
        toolPanelOpen,
        setToolPanelOpen,
        exposed,
        setExposed,
        pendingLinks,
        setPendingLinks,
        clientTools,
        remoteConversations,
        remoteLoading,
        remoteError,
        remoteImporting,
        listRef,
        messages,
        displayMessages,
        ndjsonEvents,
        sseEvents,
        filteredConversations,
        setActiveId,
        handleSend,
        handleKey,
        handleStop,
        refreshRemoteHistory,
        importHistoryConversation,
        createConversation,
        requestRename,
        doRename,
        requestDelete,
        doDelete,
        handleFileUploaded,
        handleUploadError,
        openOne,
        baseUrl: BASE_URL,
        ndjsonPath: NDJSON_PATH,
        useDemo: USE_DEMO,
    };
}
