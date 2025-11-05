// src/components/MarkdownViewer.tsx
import React from "react";
import SafeMarkdown from "./SafeMarkdown";

type MarkdownViewerProps = {
    /** 要展示的文本（会在 Markdown 模式下作为 Markdown 解析） */
    source: string;
    /** 初始视图：'markdown' | 'raw'，默认 'markdown' */
    defaultView?: "markdown" | "raw";
    /** Markdown 模式下是否默认开启代码高亮，默认 true */
    defaultHighlight?: boolean;
    /** 是否在 source 变化时自动滚动到底部，默认 true */
    autoScroll?: boolean;
    /** 容器最大高度（px），默认 280 */
    maxHeight?: number;
    /** 允许 Markdown 内联 HTML，默认 false（更安全） */
    allowHtml?: boolean;
    /** 外层容器 className（可选） */
    className?: string;
};

export default function MarkdownViewer({
                                           source,
                                           defaultView = "markdown",
                                           defaultHighlight = true,
                                           autoScroll = true,
                                           maxHeight = 280,
                                           allowHtml = false,
                                           className = "",
                                       }: MarkdownViewerProps) {
    const [mdView, setMdView] = React.useState(defaultView === "markdown");
    const [highlightOn, setHighlightOn] = React.useState(defaultHighlight);

    const scrollRef = React.useRef<HTMLDivElement | null>(null);

    // 自动滚动到底部
    React.useEffect(() => {
        if (!autoScroll) return;
        const el = scrollRef.current;
        if (el) el.scrollTop = el.scrollHeight;
    }, [source, autoScroll]);

    return (
        <div className={`rounded-2xl border bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
            <div className="mb-2 flex items-center justify-between">
                <div className="text-sm font-medium">SSE token（增量拼接）</div>
                <div className="inline-flex items-center gap-1 rounded-xl border border-slate-300 bg-white p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
                    <button
                        onClick={() => setMdView(false)}
                        className={`px-2 py-0.5 rounded-lg ${!mdView ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                    >
                        Raw
                    </button>
                    <button
                        onClick={() => setMdView(true)}
                        className={`px-2 py-0.5 rounded-lg ${mdView ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                    >
                        Markdown
                    </button>
                    {mdView && (
                        <>
                            <span className="mx-1 opacity-40">|</span>
                            <button
                                onClick={() => setHighlightOn((v) => !v)}
                                className={`px-2 py-0.5 rounded-lg ${highlightOn ? "bg-slate-200 dark:bg-slate-700" : ""}`}
                            >
                                高亮
                            </button>
                        </>
                    )}
                </div>
            </div>

            <div ref={scrollRef} className="rounded-xl overflow-auto" style={{ maxHeight }}>
                {mdView ? (
                    <div className="prose prose-sm dark:prose-invert max-w-none">
                        <SafeMarkdown source={source} allowHtml={allowHtml} highlight={highlightOn} />
                    </div>
                ) : (
                    <pre
                        className="rounded-xl bg-slate-50 text-slate-800 border border-slate-200
                       dark:bg-slate-900 dark:text-slate-100 dark:border-slate-800
                       p-3 overflow-auto text-sm font-mono whitespace-pre-wrap break-words
                       transition-colors duration-300"
                    >
{source}
          </pre>
                )}
            </div>
        </div>
    );
}
