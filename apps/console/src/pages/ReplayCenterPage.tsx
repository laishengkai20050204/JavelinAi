import React, { useEffect, useMemo, useRef, useState } from "react";
import { JsonViewer } from "../components/JsonViewer";
import { motion } from "framer-motion";
import {
    Play, Square, Download, Filter, RefreshCw, Languages,
    Clipboard, ClipboardCheck, Binary, MessageSquare, Workflow, Wrench
} from "lucide-react";
import { readNdjson } from "../lib/ndjson";
import { useSharedIds } from "../lib/sharedIds";
import SafeMarkdown from "../components/SafeMarkdown";

/* ========= Error Boundary：任何渲染异常 → 不再白屏 ========= */
class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { error: Error | null }> {
    constructor(props: { children: React.ReactNode }) {
        super(props);
        this.state = { error: null };
    }
    static getDerivedStateFromError(error: Error) {
        return { error };
    }
    render() {
        if (this.state.error) {
            return (
                <div className="m-4 rounded-xl border border-red-300 bg-red-50 p-4 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200">
                    <div className="font-semibold mb-1">Render Error</div>
                    <pre className="whitespace-pre-wrap text-sm">
            {String(this.state.error?.stack || this.state.error?.message)}
          </pre>
                </div>
            );
        }
        return this.props.children;
    }
}

/* ===== 事件类型 ===== */
type MessageData = { type: "message"; role?: string; text?: string };
type DecisionData = { type: "decision"; tool_calls?: unknown };
type ToolPayload = { exitCode?: number; [k: string]: unknown };
type ToolData = { type: "tool"; name?: string; reused?: boolean; data?: ToolPayload | unknown; text?: string };

type StartedEvent = { event: "started"; ts?: string; data?: unknown };
type FinishedEvent = { event: "finished"; ts?: string; data?: unknown };
type GenericEvent = { event?: string; ts?: string; data?: unknown; [k: string]: unknown };

type ReplayEvent =
    | ({ event?: string; ts?: string; data: MessageData })
    | ({ event?: string; ts?: string; data: DecisionData })
    | ({ event?: string; ts?: string; data: ToolData })
    | StartedEvent
    | FinishedEvent
    | GenericEvent;

/* ===== 小工具：解析 query ===== */
function getQueryFlag(name: string) {
    try { return new URLSearchParams(window.location.search).has(name); } catch { return false; }
}

/* ===================== utils & guards ===================== */
function asRecord(v: unknown): Record<string, unknown> {
    return (v !== null && typeof v === "object") ? (v as Record<string, unknown>) : {};
}
function getString(r: Record<string, unknown>, key: string): string | undefined {
    const v = r[key];
    return typeof v === "string" ? v : undefined;
}
function getNumber(r: Record<string, unknown>, key: string): number | undefined {
    const v = r[key];
    return typeof v === "number" ? v : undefined;
}
function getBoolean(r: Record<string, unknown>, key: string): boolean {
    const v = r[key];
    return typeof v === "boolean" ? v : false;
}
function getEventType(e: ReplayEvent): "message" | "decision" | "tool" | undefined {
    const data = asRecord(asRecord(e)["data"]);
    const t = data["type"];
    return t === "message" || t === "decision" || t === "tool" ? t : undefined;
}

/** 把可能“双重 JSON 字符串”的值解析成对象/数组；失败就原样返回 */
function deepTryParseJson(v: unknown): unknown {
    if (typeof v !== "string") return v;
    try {
        const once = JSON.parse(v);
        if (typeof once === "string") {
            try { return JSON.parse(once); } catch { return toDisplayMultiline(once); }
        }
        return once;
    } catch {
        return v;
    }
}

/** 把字面量 \n / \r\n / \t / \uXXXX 还原为真实字符 */
function toDisplayMultiline(v: unknown): string {
    if (typeof v !== "string") return (v ?? "") as string;
    try {
        const unescaped = JSON.parse(
            `"${v.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`
        );
        return unescaped as string;
    } catch {
        return v.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
    }
}
function safeJSONStringify(v: unknown, space = 0) {
    try { return JSON.stringify(v, null, space); } catch { return String(v); }
}
function triggerDownload(blob: Blob, filename: string) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}

/** 规范化：把 decision.tool_calls[*].function.arguments 从字符串解析成对象（支持双重 JSON） */
function normalizeEvent(e: ReplayEvent): ReplayEvent {
    const root = asRecord(e);
    const data = asRecord(root["data"]);
    const typ = getEventType(e);
    if (typ !== "decision") return e;

    const calls = Array.isArray(data["tool_calls"]) ? data["tool_calls"] : [];
    const fixed = calls.map((c) => {
        const r = asRecord(c);
        const fn = asRecord(r["function"]);
        const raw = fn["arguments"] ?? r["arguments"];
        const parsed = deepTryParseJson(raw);
        if (parsed && typeof parsed === "object") {
            return { ...r, function: { ...fn, arguments: parsed } };
        }
        return r;
    });

    return { ...root, data: { ...data, tool_calls: fixed } } as ReplayEvent;
}

/** 从决策里抽取 arguments 对象（已被 normalizeEvent 处理为对象时更稳定） */
function extractDecisionArgsTrees(data: Record<string, unknown>): unknown[] {
    const calls = Array.isArray(data["tool_calls"]) ? data["tool_calls"] : [];
    const out: unknown[] = [];
    for (const c of calls) {
        const r = asRecord(c);
        const fn = asRecord(r["function"]);
        const raw = fn["arguments"] ?? r["arguments"];
        if (raw !== null && typeof raw === "object") out.push(raw as Record<string, unknown>);
        else if (typeof raw === "string") {
            const parsed = deepTryParseJson(raw);
            if (parsed && typeof parsed === "object") out.push(parsed);
        }
    }
    return out;
}

/* ===== 主组件 ===== */
export default function ReplayCenterPage() {
    /* ===== i18n ===== */
    type Lang = "zh" | "en";
    const [lang, setLang] = useState<Lang>(() => {
        try { return navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en"; } catch { return "zh"; }
    });

    const i18n = {
        zh: {
            title: "Javelin 回放中心",
            subtitle: "按行解析 NDJSON · 工具/决策/消息可视化",
            form: {
                userId: "用户 ID", convId: "会话 ID", stepId: "Step ID（可选）",
                limit: "Limit（条数上限）", start: "开始回放", stop: "停止",
                exportJson: "导出 JSON", exportNdjson: "导出 NDJSON",
                filter: "筛选", refresh: "清空事件",
            },
            banners: { streaming: "正在流式回放...", stopped: "回放已停止", empty: "暂时没有事件", copied: "已复制" },
            filters: { msg: "消息", dec: "决策", tool: "工具", other: "其它" },
            view: { raw: "Raw", md: "Markdown", hl: "高亮" },
        },
        en: {
            title: "Javelin Replay Center",
            subtitle: "NDJSON line-by-line · visualize tools/decisions/messages",
            form: {
                userId: "User ID", convId: "Conversation ID", stepId: "Step ID (optional, up to & including)",
                limit: "Limit", start: "Start Replay", stop: "Stop",
                exportJson: "Export JSON", exportNdjson: "Export NDJSON",
                filter: "Filter", refresh: "Clear Events",
            },
            banners: { streaming: "Streaming replay...", stopped: "Replay stopped", empty: "No events yet", copied: "Copied" },
            filters: { msg: "Message", dec: "Decision", tool: "Tool", other: "Other" },
            view: { raw: "Raw", md: "Markdown", hl: "Highlight" },
        },
    } as const;
    const t = i18n[lang];

    /* ===== 视图切换 ===== */
    const [mdView, setMdView] = useState<boolean>(true);
    const [highlightOn, setHighlightOn] = useState<boolean>(true);

    /* ===== state ===== */
    const { userId, setUserId, conversationId, setConversationId } = useSharedIds("u1", "c1");
    const [stepId, setStepId] = useState<string>("");
    const [limit, setLimit] = useState<number>(1000);

    const [events, setEvents] = useState<ReplayEvent[]>([]);
    const [loading, setLoading] = useState(false);
    const [copiedIdx, setCopiedIdx] = useState<number | null>(null);
    const abortRef = useRef<AbortController | null>(null);
    const scrollerRef = useRef<HTMLDivElement | null>(null);

    // filters
    const [showMsg, setShowMsg] = useState(true);
    const [showDec, setShowDec] = useState(true);
    const [showTool, setShowTool] = useState(true);
    const [showOther, setShowOther] = useState(true);

    // DEMO：?demo=1 时注入示例事件，方便验证 UI 是否正常
    const DEMO = getQueryFlag("demo");
    useEffect(() => {
        if (!DEMO) return;
        const now = new Date().toISOString();
        const sample: ReplayEvent[] = [
            { event: "started", ts: now },
            { ts: now, data: { type: "message", role: "user", text: "你好，给我讲讲 two-stage SSE。" } },
            {
                ts: now,
                data: {
                    type: "decision",
                    tool_calls: [
                        { id: "t1", function: { name: "python_exec", arguments: { code: "print('hello\\nworld')", opt: { a: 1 } } } }
                    ]
                }
            },
            {
                ts: now,
                data: { type: "tool", name: "python_exec", reused: false, data: { exitCode: 0, tookMs: 123 }, text: "hello\nworld" }
            },
            { event: "finished", ts: now }
        ];
        setEvents(sample);
    }, [DEMO]);

    useEffect(() => () => abortRef.current?.abort(), []);

    /* ===== derived ===== */
    const filteredEvents = useMemo(() => {
        return events.filter((e) => {
            const typ = getEventType(e);
            if (typ === "message") return showMsg;
            if (typ === "decision") return showDec;
            if (typ === "tool") return showTool;
            return showOther; // started/finished/unknown
        });
    }, [events, showMsg, showDec, showTool, showOther]);

    useEffect(() => {
        if (scrollerRef.current) scrollerRef.current.scrollTop = scrollerRef.current.scrollHeight;
    }, [filteredEvents, loading]);

    const clearEvents = () => setEvents([]);

    async function startReplay() {
        abortRef.current?.abort();
        const ac = new AbortController();
        abortRef.current = ac;
        setLoading(true);
        setEvents([]);

        const qs = new URLSearchParams({ userId, conversationId, limit: String(limit) });
        if (stepId) qs.set("stepId", stepId);
        const url = `/ai/replay/ndjson?${qs.toString()}`;

        try {
            await readNdjson(
                url,
                (obj: unknown) => setEvents((prev) => [...prev, normalizeEvent(obj as ReplayEvent)]),
                ac.signal
            );
        } catch (err: any) {
            const msg = String(err?.message || err);
            // AbortError 是正常的（点击停止/再次开始会触发），静音
            if (err?.name === "AbortError" || /aborted/i.test(msg)) {
                // no-op
            } else {
                setEvents((prev) => [...prev, { event: "error", ts: new Date().toISOString(), data: { message: msg } }]);
                // eslint-disable-next-line no-console
                console.error("[ReplayCenter] readNdjson failed:", err);
            }
        } finally {
            setLoading(false);
        }
    }

    function stopReplay() {
        abortRef.current?.abort();
        setLoading(false);
    }

    function exportAsJson() {
        const blob = new Blob(
            [JSON.stringify({ userId, conversationId, stepId: stepId || undefined, count: events.length, events }, null, 2)],
            { type: "application/json" }
        );
        triggerDownload(blob, `replay-${conversationId}${stepId ? "-" + stepId : ""}.json`);
    }

    function exportAsNdjson() {
        const lines = events.map((e) => JSON.stringify(e)).join("\n") + "\n";
        const blob = new Blob([lines], { type: "application/x-ndjson" });
        triggerDownload(blob, `replay-${conversationId}${stepId ? "-" + stepId : ""}.ndjson`);
    }

    const bannerText = loading ? t.banners.streaming : (events.length === 0 ? t.banners.empty : t.banners.stopped);

    return (
        <ErrorBoundary>
            <div className="min-h-screen w-full bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
                {/* 覆盖 hljs 背景为透明，防止黑底 */}
                <style>{`
          .prose pre code.hljs { background: transparent !important; }
          .prose code.hljs     { background: transparent !important; }
          code.hljs            { background: transparent !important; }
        `}</style>

                {/* Header */}
                <header className="sticky top-0 z-10 border-b bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/60 dark:border-slate-800 dark:bg-slate-900/80 dark:supports-[backdrop-filter]:bg-slate-900/60">
                    <div className="mx-auto max-w-6xl px-4 py-3 flex items-center justify-between">
                        <div className="flex items-center gap-3">
                            <div className="h-9 w-9 rounded-2xl bg-gradient-to-tr from-blue-500 to-indigo-500 text-white grid place-items-center shadow-sm">
                                <Binary size={18} />
                            </div>
                            <div>
                                <h1 className="text-lg font-semibold leading-tight">{t.title}</h1>
                                <p className="text-xs text-slate-500 dark:text-slate-400">
                                    {t.subtitle} {DEMO && <span className="ml-2 rounded bg-amber-200/70 px-1.5 py-0.5 text-amber-900">DEMO</span>}
                                </p>
                            </div>
                        </div>

                        <div className="flex items-center gap-2">
                            <div className="inline-flex items-center rounded-xl border border-slate-300 bg-white p-1 text-sm dark:border-slate-700 dark:bg-slate-800">
                                <button
                                    onClick={() => setLang("zh")}
                                    className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "zh" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                    aria-pressed={lang === "zh"}
                                >
                                    <Languages size={14} /> 中文
                                </button>
                                <button
                                    onClick={() => setLang("en")}
                                    className={`flex items-center gap-1 rounded-lg px-2 py-1 ${lang === "en" ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                    aria-pressed={lang === "en"}
                                >
                                    EN
                                </button>
                            </div>
                        </div>
                    </div>
                </header>

                {/* Body */}
                <main className="mx-auto max-w-6xl px-4 py-6">
                    {/* banner */}
                    <div className="mb-4">
                        <Banner icon={<RefreshCw className={loading ? "animate-spin" : ""} size={16} />} text={bannerText} color={loading ? "slate" : "green"} />
                    </div>

                    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                                className="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
                        {/* 查询区 */}
                        <Section title={lang === "zh" ? "查询条件" : "Query"}>
                            <div className="grid gap-4 md:grid-cols-4">
                                <Field label={t.form.userId}>
                                    <input value={userId} onChange={(e) => setUserId(e.target.value)}
                                           className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                                </Field>
                                <Field label={t.form.convId}>
                                    <input value={conversationId} onChange={(e) => setConversationId(e.target.value)}
                                           className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                                </Field>
                                <Field label={t.form.stepId}>
                                    <input value={stepId} onChange={(e) => setStepId(e.target.value)}
                                           placeholder={lang === "zh" ? "留空=回放到最近 FINAL" : "empty = up to latest FINAL"}
                                           className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"/>
                                </Field>
                                <Field label={t.form.limit}>
                                    <input type="number" min={100} max={5000} value={limit} onChange={(e) => setLimit(Number(e.target.value))}
                                           className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"/>
                                </Field>
                            </div>
                        </Section>

                        {/* 操作区 */}
                        <Section title={lang === "zh" ? "操作" : "Actions"}>
                            <div className="flex flex-wrap items-center gap-2 md:sticky md:bottom-4 md:z-10 md:rounded-2xl md:border md:border-slate-200 md:bg-slate-50/80 md:p-3 md:backdrop-blur md:supports-[backdrop-filter]:bg-slate-50/60 transition-colors dark:md:border-slate-800 dark:md:bg-slate-900/70 dark:md:supports-[backdrop-filter]:bg-slate-900/60">
                                <button onClick={startReplay} disabled={loading}
                                        className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium text-white ${loading ? "bg-slate-400 dark:bg-slate-600" : "bg-blue-600 hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"}`}>
                                    <Play className="h-4 w-4" /> {t.form.start}
                                </button>
                                <button onClick={stopReplay} disabled={!loading}
                                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                    <Square className="h-4 w-4" /> {t.form.stop}
                                </button>

                                <span className="mx-2 opacity-50">|</span>
                                <button onClick={exportAsJson}
                                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                    <Download size={14} /> {t.form.exportJson}
                                </button>
                                <button onClick={exportAsNdjson}
                                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                    <Download size={14} /> {t.form.exportNdjson}
                                </button>

                                <span className="mx-2 opacity-50">|</span>
                                <button onClick={clearEvents}
                                        className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">
                                    <RefreshCw size={14} /> {t.form.refresh}
                                </button>

                                {/* 过滤切换 */}
                                <div className="ml-auto inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
                                    <Filter size={14} />
                                    <span className="px-1 opacity-70">{t.form.filter}:</span>
                                    <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg-white/5">
                                        <input type="checkbox" checked={showMsg} onChange={(e) => setShowMsg(e.target.checked)} />
                                        msg
                                    </label>
                                    <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg白/5">
                                        <input type="checkbox" checked={showDec} onChange={(e) => setShowDec(e.target.checked)} />
                                        dec
                                    </label>
                                    <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg黑/5 dark:hover:bg白/5">
                                        <input type="checkbox" checked={showTool} onChange={(e) => setShowTool(e.target.checked)} />
                                        tool
                                    </label>
                                    <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg黑/5 dark:hover:bg白/5">
                                        <input type="checkbox" checked={showOther} onChange={(e) => setShowOther(e.target.checked)} />
                                        other
                                    </label>
                                </div>

                                {/* Raw / Markdown / 高亮 切换（控制 data 预览） */}
                                <div className="inline-flex items-center gap-1 rounded-xl border border-slate-300 bg白 p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
                                    <button
                                        onClick={() => setMdView(false)}
                                        className={`px-2 py-1 rounded-lg ${!mdView ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                    >
                                        {t.view.raw}
                                    </button>
                                    <button
                                        onClick={() => setMdView(true)}
                                        className={`px-2 py-1 rounded-lg ${mdView ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                    >
                                        {t.view.md}
                                    </button>
                                    {mdView && (
                                        <>
                                            <span className="mx-1 opacity-40">|</span>
                                            <button
                                                onClick={() => setHighlightOn(v => !v)}
                                                className={`px-2 py-1 rounded-lg ${highlightOn ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                                            >
                                                {t.view.hl}
                                            </button>
                                        </>
                                    )}
                                </div>
                            </div>
                        </Section>

                        {/* 事件流 */}
                        <Section title={lang === "zh" ? "事件流" : "Event Stream"}>
                            <div
                                ref={scrollerRef}
                                className="border rounded-xl p-2 h-[460px] overflow-auto text-sm
                         bg-slate-50 text-slate-800 border-slate-200
                         dark:bg-slate-900 dark:text-slate-100 dark:border-slate-800
                         transition-colors duration-300"
                            >
                                {filteredEvents.length === 0 ? (
                                    <div className="text-slate-500 dark:text-slate-400 p-3">{bannerText}</div>
                                ) : (
                                    filteredEvents.map((e, idx) => (
                                        <EventRow
                                            key={idx}
                                            e={e}
                                            lang={lang}
                                            mdView={mdView}
                                            highlightOn={highlightOn}
                                            onCopy={() => {
                                                navigator.clipboard?.writeText(JSON.stringify(e, null, 2))
                                                    .then(() => {
                                                        setCopiedIdx(idx);
                                                        setTimeout(() => setCopiedIdx(null), 1200);
                                                    })
                                                    .catch(() => {/* ignore */});
                                            }}
                                            copied={copiedIdx === idx}
                                        />
                                    ))
                                )}
                            </div>
                        </Section>
                    </motion.div>
                </main>
            </div>
        </ErrorBoundary>
    );
}

/* ========== UI helpers ========== */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <section className="py-4">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div>
            {children}
        </section>
    );
}
function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <label className="block text-sm">
            <div className="mb-1 text-slate-500 dark:text-slate-400">{label}</div>
            {children}
        </label>
    );
}
function Banner({ icon, text, color }: { icon: React.ReactNode; text: string; color: "slate" | "green" | "red" }) {
    const tone =
        color === "green"
            ? "bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950 dark:text-emerald-200 dark:border-emerald-900"
            : color === "red"
                ? "bg-red-50 text-red-700 border-red-200 dark:bg-red-950 dark:text-red-200 dark:border-red-900"
                : "bg-slate-50 text-slate-700 border-slate-200 dark:bg-slate-900 dark:text-slate-200 dark:border-slate-800";
    return (
        <div className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm ${tone}`}>
            {icon}<span>{text}</span>
        </div>
    );
}

/* ===== 子项：事件行（data 外显 + 其余折叠；decision.arguments 直显为树/代码块） ===== */
function EventRow({
                      e, onCopy, copied, lang, mdView, highlightOn,
                  }: {
    e: ReplayEvent; onCopy: () => void; copied: boolean; lang: "zh" | "en";
    mdView: boolean; highlightOn: boolean;
}) {
    const typ = getEventType(e);
    const ts = getString(asRecord(e), "ts") ?? "";
    const icon = typ === "message" ? <MessageSquare size={14}/>
        : typ === "decision" ? <Workflow size={14}/>
            : typ === "tool" ? <Wrench size={14}/>
                : <Binary size={14}/>;

    const md = formatEventMarkdown(e, lang);
    const dataRec = asRecord(asRecord(e)["data"]);
    const decisionArgTrees = typ === "decision" ? extractDecisionArgsTrees(dataRec) : [];

    // 递归：若遇到 "arguments" 且为 JSON/对象 → 处理 code 字段为代码块；字符串则尝试解析否则按原样多行
    const renderArgsKey = (key: string, value: unknown, level: number) => {
        if (key !== "arguments") return null;

        const renderObjLike = (objLike: unknown) => {
            if (objLike === null || typeof objLike !== "object") return null;

            // 对象：分离 code 字段
            if (!Array.isArray(objLike)) {
                const rec = objLike as Record<string, unknown>;
                let codeStr: string | undefined = typeof rec.code === "string" ? rec.code : undefined;
                const rest: Record<string, unknown> = { ...rec };
                if (codeStr !== undefined) delete rest.code;

                return (
                    <div>
                        <div className="text-[12px] text-slate-500 mb-1">arguments</div>
                        {Object.keys(rest).length > 0 && (
                            <JsonViewer data={rest} level={level + 1} defaultOpen={false} renderKey={renderArgsKey} />
                        )}
                        {codeStr !== undefined && (
                            <>
                                <div className="mt-2 text-[12px] text-slate-500">code</div>
                                <pre className="mt-1 rounded-xl bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100 p-2 overflow-auto text-[12px]">
                  <code>{toDisplayMultiline(codeStr)}</code>
                </pre>
                            </>
                        )}
                    </div>
                );
            }

            // 数组：直接递归
            return (
                <div>
                    <div className="text-[12px] text-slate-500 mb-1">arguments</div>
                    <JsonViewer data={objLike} level={level + 1} defaultOpen={false} renderKey={renderArgsKey} />
                </div>
            );
        };

        if (typeof value === "string") {
            const parsed = deepTryParseJson(value);
            if (parsed !== null && typeof parsed === "object") {
                return renderObjLike(parsed);
            }
            return (
                <div>
                    <div className="text-[12px] text-slate-500 mb-1">arguments</div>
                    <pre className="mt-1 rounded-xl bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100 p-2 overflow-auto text-[12px]">
            <code>{toDisplayMultiline(value)}</code>
          </pre>
                </div>
            );
        }

        return renderObjLike(value);
    };

    // 顶层根树里遇到 data：用占位说明“已在上方展开”
    const renderRootKey = (key: string) => {
        if (key !== "data") return null;
        return (
            <div>
                <div className="text-[12px] text-slate-500">data</div>
                <em className="text-[12px] opacity-70">{lang === "zh" ? "已在上方展开显示" : "shown above"}</em>
            </div>
        );
    };

    const dataValue = asRecord(e)["data"];

    return (
        <div className="flex items-start gap-2 px-3 py-2 rounded-xl border bg-transparent border-slate-200 dark:border-slate-800 hover:bg-slate-100/60 dark:hover:bg-white/5 transition-colors">
            <div className="mt-0.5">{icon}</div>
            <div className="flex-1">
                <div className="text-[11px] text-slate-500 dark:text-slate-400">
                    {ts} · {typ || getString(asRecord(e), "event")}
                </div>

                {/* —— 上：data 外显（md/raw 预览 + decision arguments 树 + data 树(折叠)） */}
                <div className="mt-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-transparent p-2">
                    {/* 上半：人类可读预览 */}
                    {mdView ? (
                        <div className="prose prose-sm max-w-none dark:prose-invert prose-pre:bg-slate-100 prose-pre:text-slate-800 dark:prose-pre:bg-slate-800 dark:prose-pre:text-slate-100 prose-code:bg-slate-100 prose-code:text-slate-800 dark:prose-code:bg-slate-900 dark:prose-code:text-slate-100 prose-code:before:content-[''] prose-code:after:content-['']">
                            <SafeMarkdown source={md} allowHtml={false} highlight={highlightOn}/>
                        </div>
                    ) : (
                        <pre className="mt-1 rounded-xl bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100 p-2 overflow-auto whitespace-pre-wrap break-words">
              {toDisplayMultiline(md)}
            </pre>
                    )}

                    {/* 决策：把每个 tool_call 的 arguments 直接渲染为树（默认折叠），并单独显示 code */}
                    {decisionArgTrees.length > 0 && (
                        <div className="mt-2 space-y-2">
                            {decisionArgTrees.map((tree, i) => (
                                <div key={i}>
                                    <div className="text-[12px] text-slate-500 mb-1">arguments #{i + 1}</div>
                                    <JsonViewer data={tree} defaultOpen={false} renderKey={renderArgsKey} />
                                </div>
                            ))}
                        </div>
                    )}

                    {/* data 的 JSON 树（默认折叠） */}
                    <div className="mt-2">
                        <div className="text-[12px] text-slate-500 mb-1">data</div>
                        <JsonViewer data={dataValue} defaultOpen={false} renderKey={renderArgsKey} />
                    </div>
                </div>

                {/* —— 下：整条事件（默认折叠） */}
                <div className="mt-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-transparent p-2">
                    <JsonViewer data={e} defaultOpen={false} renderKey={renderRootKey} />
                </div>
            </div>

            <button onClick={onCopy} className="ml-2 opacity-80 hover:opacity-100" title={lang === "zh" ? "复制 JSON" : "Copy JSON"}>
                {copied ? <ClipboardCheck size={14}/> : <Clipboard size={14}/>}
            </button>
        </div>
    );
}

/* ===== 文本格式（Markdown 预览体） ===== */
function formatEventMarkdown(e: ReplayEvent, lang: "zh"|"en") {
    const data = asRecord(asRecord(e)["data"]);
    const typ = getEventType(e);

    if (typ === "message") {
        const role = getString(data, "role") ?? "assistant";
        const textRaw = getString(data, "text") ?? "";
        const text = toDisplayMultiline(textRaw);
        const hdr = `**[${role}]**`;
        return `${hdr}\n\n${text}`;
    }

    if (typ === "decision") {
        const calls = data["tool_calls"];
        const header = lang === "zh" ? "🤖 **决策工具**" : "🤖 **Decide tools**";
        const body = prettyToolCallsAsMarkdown(calls, lang);
        return `${header}\n\n${body}`;
    }

    if (typ === "tool") {
        const name = getString(data, "name") ?? "tool";
        const reused = getBoolean(data, "reused") ? (lang === "zh" ? "复用" : "reused") : (lang === "zh" ? "新执行" : "fresh");
        const payload = asRecord(data["data"]);
        const exitCode = getNumber(payload, "exitCode");
        const text = toDisplayMultiline(getString(data, "text") ?? "");
        const head = `🛠 **${name}** (${reused})${exitCode !== undefined ? ` · exit=${exitCode}` : ""}`;
        if (text) return `${head}\n\n${wrapMaybeAsCode(text)}`;
        return head;
    }

    if (getString(asRecord(e), "event") === "started")  return (lang === "zh" ? "▶ **开始回放**" : "▶ **Replay started**");
    if (getString(asRecord(e), "event") === "finished") return (lang === "zh" ? "■ **回放结束**" : "■ **Replay finished**");
    return "```json\n" + safeJSONStringify(e, 2) + "\n```";
}

// 👉 替换整个 prettyToolCallsAsMarkdown
function prettyToolCallsAsMarkdown(calls: unknown, lang: "zh" | "en"): string {
    const arr = Array.isArray(calls) ? calls : [];
    const seeBelow = lang === "zh" ? "（参数详见下方 arguments 树）" : "(see arguments tree below)";

    const detectLang = (code: string) => {
        if (/#include\b/.test(code)) return "cpp";
        if (/\b(def |import |numpy|pandas|matplotlib)\b/.test(code)) return "python";
        if (/\b(function|const|let|=>)\b/.test(code)) return "javascript";
        if (/\bclass\s+\w+\s*{/.test(code)) return "java";
        return ""; // 让高亮自动猜
    };

    return arr.map((c, i: number) => {
        const r = (c && typeof c === "object") ? (c as Record<string, unknown>) : {};
        const func = (r["function"] && typeof r["function"] === "object") ? (r["function"] as Record<string, unknown>) : {};
        const name =
            (typeof func["name"] === "string" && func["name"]) ||
            (typeof r["name"] === "string" && r["name"]) ||
            (typeof r["id"] === "string" && r["id"]) ||
            "tool";

        const rawArgs = func["arguments"] ?? r["arguments"];
        const parsed = deepTryParseJson(rawArgs);

        const title = `#${i + 1} ${name}`;

        // 如果是对象且包含 code 字符串 → 单独以代码块展示（不再用 json 包裹）
        if (parsed && typeof parsed === "object" && typeof (parsed as any).code === "string") {
            const codeText = toDisplayMultiline((parsed as any).code as string);
            const keys = Object.keys(parsed as Record<string, unknown>).filter(k => k !== "code");
            const summary =
                keys.length
                    ? (lang === "zh" ? `其余参数: ${keys.join(", ")}` : `other args: ${keys.join(", ")}`)
                    : "";

            const langTag = detectLang(codeText);
            return `${title}\n\n${seeBelow}\n${summary ? `\n${summary}\n` : ""}\n\`\`\`${langTag}\n${codeText}\n\`\`\``;
        }

        // 其他情况：不打印整段 JSON，避免把 code 当作 JSON 字符串高亮；只给提示
        return `${title}\n\n${seeBelow}`;
    }).join("\n\n");
}


function wrapMaybeAsCode(s: string): string {
    const looksLikeCode = /(;|{|}|\bclass\b|\bdef\b|\bfunction\b|\b#include\b|import\s+\w+)/.test(s) || s.includes("\n");
    if (!looksLikeCode) return s;
    const lang = /\b#include\b/.test(s) ? "cpp" : (/\bdef\b/.test(s) ? "python" : "");
    return `\`\`\`${lang}\n${s}\n\`\`\``;
}
