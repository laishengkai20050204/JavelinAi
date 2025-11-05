import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import MarkdownViewer from "../components/MarkdownViewer";
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
    AlertTriangle
} from "lucide-react";

/**
 * Javelin — Minimal NDJSON Chat UI (fully rewritten)
 * --------------------------------------------------
 * • Clean UI, system dark-mode
 * • Solid conversations sidebar (create / switch / rename / delete)
 * • Custom dialogs (no window.prompt/confirm)
 * • NDJSON demo stream; flip USE_DEMO=false to hit real backend
 * • LocalStorage persistence
 */

// === Quick toggles ==========================================================
const USE_DEMO = false; // ← set to false to hit your server
const BASE_URL = "/"; // e.g. "http://localhost:8080" (no trailing slash)
const NDJSON_PATH = "/ai/v3/chat/step/ndjson"; // your NDJSON endpoint
const STORAGE_KEY = "javelin.chat.v3";
const UNTITLED = "新会话";

// Utilities
const newId = () => Math.random().toString(36).slice(2, 10);
const joinUrl = (a: string, b: string) => (a.endsWith("/") ? a.slice(0, -1) : a) + b;

// === Types =================================================================
export type Role = "user" | "assistant" | "tool" | "system";
export interface ChatMessage { id: string; role: Role; content: string; ts: number; }
export interface ConversationMeta { id: string; title: string; createdAt: number; updatedAt: number; }
export interface StepEventLine { event?: string; ts?: string | number; data?: any; [k: string]: any; }

// === NDJSON reader ==========================================================
async function* ndjsonIterator(res: Response) {
    if (!res.body) return;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    const NL = String.fromCharCode(10);
    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx = buf.indexOf(NL);
        while (idx >= 0) {
            const line = buf.slice(0, idx).trim();
            buf = buf.slice(idx + 1);
            if (line) {
                try { yield JSON.parse(line) as StepEventLine; } catch { console.warn("Bad NDJSON line:", line); }
            }
            idx = buf.indexOf(NL);
        }
    }
    const tail = buf.trim();
    if (tail) { try { yield JSON.parse(tail) as StepEventLine; } catch {} }
}

function parseAssistantDelta(line: StepEventLine): string | null {
    const d = line?.data ?? {};
    // Check for each possible content type and return only the new text
    if (typeof d.delta_text === "string") return d.delta_text;
    if (typeof d.delta === "string") return d.delta;
    if (typeof d.content === "string") return d.content;
    if (typeof d.assistant_content === "string") return d.assistant_content;
    if (typeof d.partial === "string") return d.partial;
    if (typeof d.text === "string") return d.text;
    if (typeof d.final_answer === "string") return d.final_answer;
    return null;
}
function isFinalEvent(line: StepEventLine): boolean {
    if (!line) return false;
    if (line.event === "final" || line.event === "completed") return true;
    const t = line?.data?.type;
    return t === "final" || t === "assistant_final" || t === "done";
}

// Extract OpenAI-style delta content from SSE chunk JSON
function sseExtractDeltaContent(u: unknown): string | null {
    if (!u || typeof u !== 'object' || Array.isArray(u)) return null;
    const o: any = u as any;
    const choices = o.choices;
    if (!Array.isArray(choices) || choices.length === 0) return null;
    const first = choices[0];
    if (!first || typeof first !== 'object') return null;
    const delta = (first as any).delta;
    if (!delta || typeof delta !== 'object') return null;
    const content = (delta as any).content;
    return typeof content === 'string' ? content : null;
}

// === Demo stream ============================================================
async function* demoNdjson(userText: string) {
    const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
    yield { event: "started", ts: Date.now(), data: { stepId: newId() } };
    await wait(220);
    yield { event: "step", ts: Date.now(), data: { type: "decision" } };
    await wait(160);
    const reply = `You said: "${userText}" — demo streaming via NDJSON.`;
    for (const ch of reply.split("")) { yield { event: "step", ts: Date.now(), data: { type: "assistant", delta_text: ch } }; await wait(8 + Math.random()*10); }
    yield { event: "final", ts: Date.now(), data: { type: "final" } };
}

// === Dialogs ================================================================
function DialogBase({ open, children, onClose }: { open: boolean; children: React.ReactNode; onClose?: ()=>void }) {
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
                        animate={{ y: 0, scale: 1, opacity: 1, transition: { type: 'spring', stiffness: 320, damping: 28 } }}
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
    open: boolean; title: string; message: string; confirmText?: string; cancelText?: string; danger?: boolean; onConfirm: ()=>void; onCancel: ()=>void;
}) {
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div className={`p-2 rounded-xl ${danger ? 'bg-red-600 text-white' : 'bg-slate-900 text-white'} dark:bg-white dark:text-slate-900`}><AlertTriangle className="h-4 w-4"/></div>
                <div className="font-semibold">{title}</div>
            </div>
            <div className="text-sm text-slate-600 dark:text-slate-300 mb-4">{message}</div>
            <div className="flex justify-end gap-2">
                <button onClick={onCancel} className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800">{cancelText}</button>
                <button onClick={onConfirm} className={`px-3 py-1.5 text-sm rounded-xl border ${danger ? 'border-red-600 text-red-600 hover:bg-red-50' : 'border-slate-900 text-slate-900 hover:bg-slate-100'} dark:border-white dark:text-white dark:hover:bg-white/10`}>{confirmText}</button>
            </div>
        </DialogBase>
    );
}

function RenameDialog({ open, initial, onSave, onCancel }: { open: boolean; initial: string; onSave: (t:string)=>void; onCancel: ()=>void }) {
    const [val, setVal] = useState(initial);
    useEffect(()=>{ setVal(initial); }, [initial, open]);
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900"><Pencil className="h-4 w-4"/></div>
                <div className="font-semibold">重命名会话</div>
            </div>
            <input value={val} onChange={e=>setVal(e.target.value)} className="w-full rounded-xl border border-slate-200 dark:border-slate-800 bg-transparent px-3 py-2 text-sm outline-none" placeholder="会话名称" />
            <div className="flex justify-end gap-2 mt-3">
                <button onClick={onCancel} className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800">取消</button>
                <button onClick={()=>{ const t=val.trim(); if(t) onSave(t); }} className="px-3 py-1.5 text-sm rounded-xl border border-slate-900 text-slate-900 hover:bg-slate-100 dark:border-white dark:text-white dark:hover:bg-white/10">保存</button>
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

    const abortRef = useRef<AbortController | null>(null);
    const listRef = useRef<HTMLDivElement | null>(null);
    const esRef = useRef<EventSource | null>(null);
    const sendingRef = useRef(false);
    const streamRef = useRef<string | null>(null);

    // Derived
    const messages = useMemo(() => convMessages[activeId] ?? [], [convMessages, activeId]);

    // Auto-scroll
    useEffect(() => { listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: "smooth" }); }, [messages, busy, activeId]);

    // Load from localStorage
    useEffect(() => {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                const st = JSON.parse(raw);
                setConversations(st.conversations ?? []);
                setConvMessages(st.convMessages ?? {});
                setActiveId(st.activeId ?? "");
            } else {
                const id = `c_${newId()}`;
                const meta: ConversationMeta = { id, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
                setConversations([meta]);
                setConvMessages({ [id]: [] });
                setActiveId(id);
            }
        } catch {}
    }, []);
    // Save to localStorage
    useEffect(() => { try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ conversations, convMessages, activeId })); } catch {} }, [conversations, convMessages, activeId]);

    // Cleanup on unmount: abort NDJSON and close SSE
    useEffect(() => {
        return () => {
            abortRef.current?.abort();
            if (esRef.current) { esRef.current.close(); esRef.current = null; }
        };
    }, []);

    // Helpers
    const touchActive = () => setConversations(prev => prev.map(c => c.id === activeId ? { ...c, updatedAt: Date.now() } : c));
    const setMsgs = (fn: (m: ChatMessage[]) => ChatMessage[]) => setConvMessages(prev => ({ ...prev, [activeId]: fn(prev[activeId] ?? []) }));
    const addMessage = (m: ChatMessage) => { setMsgs(prev => [...prev, m]); touchActive(); };
    const appendToDraft = (delta: string) => {
        setMsgs(prev => {
            const next = [...prev];
            const last = next[next.length - 1];

            // If the last message is 'draft' and the delta is the same as the last part, don't append it again
            if (last && last.role === "assistant" && last.id === "draft") {
                // Avoid appending the same delta text again
                if (last.content.endsWith(delta)) {
                    return next;
                }
                last.content += delta; // Add new delta to the last message content
                last.ts = Date.now(); // Update timestamp
            } else {
                next.push({ id: "draft", role: "assistant", content: delta, ts: Date.now() });
            }

            return next;
        });
    };
    const finalizeDraft = () => { setMsgs(prev => prev.map(m => m.id === 'draft' ? { ...m, id: newId() } : m)); };

    function createConversation() {
        abortRef.current?.abort();
        const id = `c_${newId()}`;
        const meta: ConversationMeta = { id, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
        setConversations(prev => [meta, ...prev]);
        setConvMessages(prev => ({ ...prev, [id]: [] }));
        setActiveId(id);
        setEvents([]); setInput("");
    }

    function requestRename(id: string) { setRenameId(id); }
    function doRename(id: string, title: string) {
        setConversations(prev => prev.map(c => c.id === id ? { ...c, title, updatedAt: Date.now() } : c));
    }

    function requestDelete(id: string) { setConfirmDelId(id); }
    function doDelete(id: string) {
        // compute based on current state (no stale closures)
        const idx = conversations.findIndex(c => c.id === id);
        const after = conversations.filter(c => c.id !== id);

        // remove messages for deleted conv
        setConvMessages(prev => { const { [id]: _removed, ...rest } = prev; return rest; });

        let next = activeId;
        let needBootstrap = false;
        if (activeId === id) {
            if (after.length > 0) next = after[Math.min(idx, after.length - 1)].id; else { next = `c_${newId()}`; needBootstrap = true; }
        }

        if (needBootstrap) {
            const meta: ConversationMeta = { id: next, title: UNTITLED, createdAt: Date.now(), updatedAt: Date.now() };
            setConversations([meta]);
            setConvMessages(prev => ({ ...prev, [next]: [] }));
        } else {
            setConversations(after);
        }
        setActiveId(next);
        setEvents([]); setInput("");
    }

    // Send
    async function handleSend(text?: string) {
        if (sendingRef.current) return;
        const content = (text ?? input).trim();
        if (!content || busy || !activeId) return;
        sendingRef.current = true;
        setInput(""); setBusy(true); setEvents([]);

        // Add user message to the conversation
        addMessage({ id: newId(), role: "user", content, ts: Date.now() });

        const meta = conversations.find(c => c.id === activeId);
        if (meta && (!meta.title || meta.title === UNTITLED)) {
            const short = content.split(" ").filter(Boolean).join(" ").slice(0, 18);
            doRename(activeId, short || UNTITLED);
        }

        const controller = new AbortController();
        abortRef.current = controller;
        const streamId = newId();
        streamRef.current = streamId;

        try {
            const iterable = USE_DEMO ? demoNdjson(content) : fetchNdjson(content, activeId, controller.signal);
            for await (const line of iterable as AsyncIterable<StepEventLine>) {
                if (streamRef.current !== streamId) break;
                setEvents(prev => [...prev, line]);

                // On real backend, start SSE when we get stepId
                if (!USE_DEMO) {
                    const sid = (line && (line as any).data && typeof (line as any).data.stepId === 'string') ? (line as any).data.stepId as string : null;
                    if (sid && !esRef.current) {
                        startSSE(sid);
                    }
                }

                const delta = parseAssistantDelta(line);
                // Only append NDJSON deltas in demo mode
                if (delta && USE_DEMO) appendToDraft(delta);
                if (isFinalEvent(line)) finalizeDraft();
            }
        } catch (err: any) {
            console.error(err);
            appendToDraft("(stream error) " + (err?.message ?? String(err)));
            finalizeDraft();
        } finally {
            if (USE_DEMO || !esRef.current) setBusy(false);
            abortRef.current = null;
            sendingRef.current = false;
        }
    }


    async function* fetchNdjson(userText: string, conversationId: string, signal?: AbortSignal) {
        const payload = buildRequest(userText, conversationId);
        const res = await fetch(joinUrl(BASE_URL, NDJSON_PATH), { method: "POST", headers: { "Content-Type": "application/json", "Accept": "application/x-ndjson" }, body: JSON.stringify(payload), signal });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        for await (const line of ndjsonIterator(res)) yield line;
    }

    function buildRequest(userText: string, conversationId: string) { return { userId: "u1", conversationId, q: userText }; }
    function handleKey(e: React.KeyboardEvent<HTMLTextAreaElement>) {
        // ignore IME composing and key auto-repeat to avoid double send
        // @ts-ignore
        if ((e as any).isComposing || (e.nativeEvent && (e.nativeEvent as any).isComposing)) return;
        if (e.key === "Enter" && !e.shiftKey) { if (e.repeat) { e.preventDefault(); return; } e.preventDefault(); handleSend(); }
    }
    function handleStop() {
        abortRef.current?.abort();
        abortRef.current = null;
        if (esRef.current) { esRef.current.close(); esRef.current = null; }
        setBusy(false);
    }

    function startSSE(stepId: string) {
        if (USE_DEMO) return; // demo mode only uses NDJSON mock
        if (esRef.current) { esRef.current.close(); esRef.current = null; }
        try {
            const url = joinUrl(BASE_URL, `/ai/v2/chat/sse?stepId=${encodeURIComponent(stepId)}`);
            const es = new EventSource(url);
            esRef.current = es;
            setEvents(prev => [...prev, { event: 'sse-open', ts: Date.now(), data: { url } }]);

            es.onmessage = (e: MessageEvent) => {
                if (e.data === '[DONE]') {
                    // 上游一条流完成（可能还有后续事件/其他分支），仅清忙，不关闭 SSE
                    setEvents(prev => [...prev, { event: 'sse-done', ts: Date.now() }]);
                    finalizeDraft();
                    setBusy(false);
                    return;
                }
                try {
                    const obj = JSON.parse(e.data);
                    const chunk = sseExtractDeltaContent(obj);
                    if (typeof chunk === 'string' && chunk) {
                        appendToDraft(chunk);
                    }
                    setEvents(prev => [...prev, { event: 'sse-message', ts: Date.now(), data: obj }]);
                } catch {
                    setEvents(prev => [...prev, { event: 'sse-message-raw', ts: Date.now(), data: e.data }]);
                }
            };

            es.onerror = () => {
                setEvents(prev => [...prev, { event: 'sse-error', ts: Date.now() }]);
                es.close(); esRef.current = null; setBusy(false);
            };

            (['decision','clientCalls','tools','status','finished','error'] as const).forEach(name => {
                es.addEventListener(name, (ev: MessageEvent) => {
                    setEvents(prev => [...prev, { event: `sse-${name}`, ts: Date.now(), data: ev.data }]);
                    if (name === 'finished') {
                        finalizeDraft();
                        setBusy(false);
                        es.close(); esRef.current = null;
                    }
                });
            });
        } catch (e) {
            setEvents(prev => [...prev, { event: 'sse-open-failed', ts: Date.now(), data: String(e) }]);
        }
    }

    // Filtered list
    const filtered = useMemo(() => { const q = query.trim().toLowerCase(); if (!q) return conversations; return conversations.filter(c => (c.title || UNTITLED).toLowerCase().includes(q)); }, [conversations, query]);

    // Debug event groups: split NDJSON vs SSE
    const ndjsonEvents = useMemo(() => events.filter(e => !(typeof (e as any)?.event === 'string' && String((e as any).event).startsWith('sse-'))), [events]);
    const sseEvents = useMemo(() => events.filter(e => (typeof (e as any)?.event === 'string' && String((e as any).event).startsWith('sse-'))), [events]);

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
                            {/* Mini rail (always visible) */}
                            <div className="w-14 shrink-0 py-2 flex flex-col items-center gap-2">
                                <button
                                    onClick={()=>setSidebarOpen(v=>!v)}
                                    className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    title={sidebarOpen ? "收起侧边栏" : "展开侧边栏"}
                                    aria-label="切换侧边栏"
                                >
                                    <Menu className="h-4 w-4"/>
                                </button>
                                <button
                                    onClick={createConversation}
                                    className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                                    title="新建对话"
                                    aria-label="新建对话"
                                >
                                    <Plus className="h-4 w-4"/>
                                </button>
                                {/* TODO: future quick actions here */}
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
                                    <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900"><Sparkles className="h-4 w-4" /></div>
                                    <div className="font-semibold">会话</div>
                                    <div className="ml-auto flex items-center gap-2">
                                        <button onClick={createConversation} className="inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-xs border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"><Plus className="h-3.5 w-3.5"/>新建</button>
                                    </div>
                                </div>

                                <div className="relative mb-2">
                                    <Search className="absolute left-2 top-2.5 h-4 w-4 text-slate-400"/>
                                    <input value={query} onChange={e=>setQuery(e.target.value)} placeholder="搜索会话…" className="w-full rounded-xl border border-slate-200 dark:border-slate-800 bg-transparent pl-8 pr-3 py-2 text-sm outline-none" />
                                </div>

                                <div className="space-y-1 max-h-[68vh] overflow-y-auto pr-1">
                                    {filtered.length === 0 && (<div className="text-xs text-slate-500 dark:text-slate-400 py-6 text-center">没有匹配的会话</div>)}
                                    {filtered.map(c => (
                                        <div
                                            key={c.id}
                                            role="button"
                                            tabIndex={0}
                                            onClick={() => { setActiveId(c.id); setEvents([]); setInput(""); }}
                                            onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setActiveId(c.id); setEvents([]); setInput(""); } }}
                                            className={`w-full text-left rounded-xl px-3 py-2 border transition cursor-pointer focus:outline-none focus:ring-2 focus:ring-slate-400/40 ${c.id===activeId ? "bg-slate-900 text-white dark:bg-white dark:text-slate-900 border-slate-900/20 dark:border-white/10" : "bg-white/70 dark:bg-slate-900/40 border-slate-200 dark:border-slate-800 hover:bg-slate-100/70 dark:hover:bg-slate-800/60"}`}
                                        >
                                            <div className="flex items-center gap-2">
                                                <div className="flex-1 min-w-0 truncate font-medium text-sm">{c.title || UNTITLED}</div>
                                                <div className="ml-2 shrink-0 flex items-center gap-1 opacity-80">
                                                    <button type="button" onPointerDown={(e)=>{ e.preventDefault(); e.stopPropagation(); }} onMouseDown={(e)=>{ e.preventDefault(); e.stopPropagation(); }} onClick={(e)=>{ e.preventDefault(); e.stopPropagation(); requestRename(c.id); }} className="p-1 rounded hover:bg-black/5 dark:hover:bg-white/10" aria-label="重命名会话" title="重命名"><Pencil className="h-3.5 w-3.5"/></button>
                                                    <button type="button" onPointerDown={(e)=>{ e.preventDefault(); e.stopPropagation(); }} onMouseDown={(e)=>{ e.preventDefault(); e.stopPropagation(); }} onClick={(e)=>{ e.preventDefault(); e.stopPropagation(); requestDelete(c.id); }} className="p-1 rounded hover:bg-black/5 dark:hover:bg-white/10" aria-label="删除会话" title="删除"><Trash2 className="h-3.5 w-3.5"/></button>
                                                </div>
                                            </div>
                                            <div className="text-[11px] mt-0.5 opacity-70 truncate">{(convMessages[c.id]?.[convMessages[c.id].length-1]?.content ?? "(空)")}</div>
                                        </div>
                                    ))}
                                </div>
                            </motion.div>
                        </div>
                    </motion.aside>

                    {/* Main column */}
                    <motion.main layout className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40">
                        {/* Header */}
                        <div className="sticky top-0 z-10 px-3 sm:px-4 py-3 border-b border-slate-200/70 dark:border-slate-800 backdrop-blur">
                            <div className="flex items-center gap-2">
                                <button onClick={()=>setSidebarOpen(v=>!v)} className="mr-1 inline-flex items-center justify-center h-9 w-auto px-2.5 rounded-xl border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800" title="切换侧边栏"><Menu className="h-4 w-4"/><span className="ml-1 hidden sm:inline text-xs">{sidebarOpen ? "收起" : "展开"}</span></button>
                                <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900"><Sparkles className="h-4 w-4" /></div>
                                <div className="font-semibold leading-tight truncate">{conversations.find(c=>c.id===activeId)?.title || UNTITLED}</div>
                                <div className="ml-auto flex items-center gap-2">
                                    {busy ? (
                                        <button onClick={handleStop} className="inline-flex items-center gap-2 rounded-xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"><Loader2 className="h-4 w-4 animate-spin" /> 停止</button>
                                    ) : (
                                        <button onClick={createConversation} className="inline-flex items-center gap-2 rounded-xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"><Plus className="h-4 w-4" /> 新建</button>
                                    )}
                                    <button onClick={() => setShowDebug(v => !v)} className="inline-flex items-center gap-2 rounded-xl px-3 py-1.5 text-sm border border-slate-300/60 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"><Bug className="h-4 w-4" /> {showDebug ? "隐藏事件" : "查看事件"}</button>
                                </div>
                            </div>
                        </div>

                        {/* Messages */}
                        <div className="px-3 sm:px-4 py-3">
                            <div ref={listRef} className="h-[60vh] md:h-[66vh] overflow-y-auto rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4">
                                {messages.length === 0 && (<EmptyState />)}
                                <div className="space-y-3">
                                    {messages.map((m) => (
                                        <motion.div key={m.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18 }}>
                                            <ChatBubble role={m.role} text={m.content} />
                                        </motion.div>
                                    ))}
                                    {busy && !messages.some(x => x.id === "draft") && (
                                        <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18 }}>
                                            <ChatBubble role="assistant" text="" typing />
                                        </motion.div>
                                    )}
                                </div>
                            </div>

                            {/* Debug events */}
              {showDebug && (
                <div className="mt-3 grid gap-3 md:grid-cols-2">
                  <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4 text-sm">
                    <div className="text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400 mb-2">NDJSON Events</div>
                    <div className="max-h-56 overflow-auto font-mono text-[12px] leading-relaxed whitespace-pre-wrap">
                      {ndjsonEvents.map((e, i) => (<div key={`n-${i}`} className="py-1 border-b border-slate-100/70 dark:border-slate-800/60 last:border-0">{JSON.stringify(e)}</div>))}
                    </div>
                  </div>
                  <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/70 dark:bg-slate-900/40 p-3 sm:p-4 text-sm">
                    <div className="text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400 mb-2">SSE Events</div>
                    <div className="max-h-56 overflow-auto font-mono text-[12px] leading-relaxed whitespace-pre-wrap">
                      {sseEvents.map((e, i) => (<div key={`s-${i}`} className="py-1 border-b border-slate-100/70 dark:border-slate-800/60 last:border-0">{typeof e === 'string' ? e : JSON.stringify(e)}</div>))}
                    </div>
                  </div>
                </div>
              )}

                            {/* Composer */}
                            <div className="sticky bottom-4 mt-4">
                                <div className="relative rounded-2xl border border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-900/60 backdrop-blur p-2 shadow-sm">
                                    <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKey} placeholder="和我聊聊… (Enter 发送，Shift+Enter 换行)" className="w-full resize-none bg-transparent outline-none p-3 pr-12 leading-6 max-h-40 h-14 text-sm" />
                                    <button onClick={() => handleSend()} disabled={busy || !input.trim() || !activeId} className="absolute right-3 bottom-3 inline-flex items-center justify-center h-9 w-9 rounded-xl border border-slate-300/70 dark:border-slate-700 disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-800" title="Send">{busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}</button>
                                </div>
                                <div className="mt-2 text-[11px] text-slate-500 dark:text-slate-400">
                                    {USE_DEMO ? (<>Demo mode · streaming fake NDJSON. Set <code>USE_DEMO=false</code> to connect your backend.</>) : (<>POST {joinUrl(BASE_URL, NDJSON_PATH)} · Accept: application/x-ndjson · conversationId=<code>{activeId}</code></>)}
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
                onConfirm={()=>{ if(confirmDelId){ const id=confirmDelId; setConfirmDelId(null); doDelete(id); } }}
                onCancel={()=>setConfirmDelId(null)}
            />

            <RenameDialog
                open={!!renameId}
                initial={renameId ? (conversations.find(c=>c.id===renameId)?.title || UNTITLED) : UNTITLED}
                onSave={(t)=>{ if(renameId){ doRename(renameId, t); setRenameId(null);} }}
                onCancel={()=>setRenameId(null)}
            />
        </div>
    );
}

function EmptyState() {
    return (
        <div className="h-full w-full flex items-center justify-center">
            <div className="text-center max-w-sm">
                <div className="mx-auto h-12 w-12 rounded-2xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center mb-3"><Bot className="h-6 w-6" /></div>
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
            {!isUser && (<div className="shrink-0 h-8 w-8 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center"><Bot className="h-4 w-4" /></div>)}
            <div className={`max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-6 shadow-sm border ${isUser ? "bg-sky-600 text-white border-sky-700" : "bg-white/85 dark:bg-slate-900/60 border-slate-200 dark:border-slate-800"}`}>
                {typing ? (
                    <TypingDots />
                ) : (
                    isUser ? (
                        text || <span className="opacity-60">…</span>
                    ) : (
                        <MarkdownViewer
                          source={text}
                          defaultView="markdown"
                          defaultHighlight={true}
                          allowHtml={false}
                          autoScroll={false}
                          maxHeight={99999}
                          className="border-0 bg-transparent dark:bg-transparent shadow-none p-0"
                        />
                    )
                )}
            </div>
            {isUser && <div className="shrink-0" />}
        </div>
    );
}

function TypingDots() {
    return (
        <div className="inline-flex items-center gap-1">
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '-0.25s' }} />
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '0s' }} />
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '0.25s' }} />
        </div>
    );
}
