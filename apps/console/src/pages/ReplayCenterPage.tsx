// src/pages/ReplayCenterPage.tsx
import React, { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import {
    Play, Square, Download, Filter, RefreshCw, Languages,
    Clipboard, ClipboardCheck, Binary, MessageSquare, Workflow, Wrench
} from "lucide-react";
import { readNdjson } from "../lib/ndjson";
import { useSharedIds } from "../lib/sharedIds";
import SafeMarkdown from "../components/SafeMarkdown";

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

    /* ===== 与 NdjsonSseDemoPage 相同的视图切换 ===== */
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
            await readNdjson(url, (obj: unknown) => setEvents((prev) => [...prev, obj as ReplayEvent]), ac.signal);
        } catch {
            // ignore abort/network errors
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
                            <p className="text-xs text-slate-500 dark:text-slate-400">{t.subtitle}</p>
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

                            {/* 过滤切换 + 图标，确保 Filter 被使用 */}
                            <div className="ml-auto inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
                                <Filter size={14} />
                                <span className="px-1 opacity-70">{t.form.filter}:</span>
                                <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg-white/5">
                                    <input type="checkbox" checked={showMsg} onChange={(e) => setShowMsg(e.target.checked)} />
                                    msg
                                </label>
                                <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg-white/5">
                                    <input type="checkbox" checked={showDec} onChange={(e) => setShowDec(e.target.checked)} />
                                    dec
                                </label>
                                <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg-white/5">
                                    <input type="checkbox" checked={showTool} onChange={(e) => setShowTool(e.target.checked)} />
                                    tool
                                </label>
                                <label className="inline-flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-black/5 dark:hover:bg-white/5">
                                    <input type="checkbox" checked={showOther} onChange={(e) => setShowOther(e.target.checked)} />
                                    other
                                </label>
                            </div>

                            {/* Raw / Markdown / 高亮 切换（与 NdjsonSseDemoPage 一致，确保 setMdView / setHighlightOn 被使用） */}
                            <div className="inline-flex items-center gap-1 rounded-xl border border-slate-300 bg-white p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
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
                                            navigator.clipboard.writeText(JSON.stringify(e, null, 2)).then(() => {
                                                setCopiedIdx(idx);
                                                setTimeout(() => setCopiedIdx(null), 1200);
                                            });
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

/* ===== 子项：事件行（用 SafeMarkdown，风格与 NdjsonSseDemoPage 一致） ===== */
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

    return (
        <div className="flex items-start gap-2 px-3 py-2 rounded-xl border
                    bg-transparent border-slate-200 dark:border-slate-800
                    hover:bg-slate-100/60 dark:hover:bg-white/5 transition-colors">
            <div className="mt-0.5">{icon}</div>
            <div className="flex-1">
                <div className="text-[11px] text-slate-500 dark:text-slate-400">{ts} · {typ || getString(asRecord(e), "event")}</div>

                {mdView ? (
                    <div
                        className="prose prose-sm max-w-none dark:prose-invert
                       prose-pre:bg-slate-100 prose-pre:text-slate-800
                       dark:prose-pre:bg-slate-800 dark:prose-pre:text-slate-100
                       prose-code:bg-slate-100 prose-code:text-slate-800
                       dark:prose-code:bg-slate-900 dark:prose-code:text-slate-100
                       prose-code:before:content-[''] prose-code:after:content-['']"
                    >
                        <SafeMarkdown source={md} allowHtml={false} highlight={highlightOn}/>
                    </div>
                ) : (
                    <pre className="mt-1 rounded-xl bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100 p-2 overflow-auto whitespace-pre-wrap break-words">
            {toDisplayMultiline(md)}
          </pre>
                )}
            </div>
            <button onClick={onCopy} className="ml-2 opacity-80 hover:opacity-100" title={lang === "zh" ? "复制 JSON" : "Copy JSON"}>
                {copied ? <ClipboardCheck size={14}/> : <Clipboard size={14}/>}
            </button>
        </div>
    );
}

/* ===== 文本格式（与之前一致，但输出给 SafeMarkdown） ===== */
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

function prettyToolCallsAsMarkdown(calls: unknown, lang: "zh" | "en"): string {
    const arr = Array.isArray(calls) ? calls : [];
    const label = lang === "zh" ? "参数" : "Args";
    return arr.map((c, i: number) => {
        const r = asRecord(c);
        const func = asRecord(r["function"]);
        const name =
            getString(func, "name") ||
            getString(r, "name") ||
            getString(r, "id") ||
            "tool";
        const rawArgs = func["arguments"] ?? r["arguments"];
        const parsed = deepTryParseJson(rawArgs);
        const title = `#${i + 1} ${name}`;
        return `${title}\n\n**${label}**\n\`\`\`json\n${safeJSONStringify(parsed, 2)}\n\`\`\``;
    }).join("\n\n");
}

function wrapMaybeAsCode(s: string): string {
    const looksLikeCode = /(;|{|}|\bclass\b|\bdef\b|\bfunction\b|\b#include\b|import\s+\w+)/.test(s) || s.includes("\n");
    if (!looksLikeCode) return s;
    const lang = /\b#include\b/.test(s) ? "cpp" : (/\bdef\b/.test(s) ? "python" : "");
    return `\`\`\`${lang}\n${s}\n\`\`\``;
}
function safeJSONStringify(v: unknown, space = 0) {
    try { return JSON.stringify(v, null, space); } catch { return String(v); }
}
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

/* ===================== utils ===================== */
function triggerDownload(blob: Blob, filename: string) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}

/* ====== 类型辅助与守卫 ====== */
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

/* 把字面量 \n / \r\n / \t / \uXXXX 还原成真实字符 */
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
