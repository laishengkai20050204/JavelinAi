import React from "react";
import DOMPurify from "dompurify";
import hljs from "highlight.js";

type JVProps = {
    data: unknown;
    label?: string;
    defaultOpen?: boolean;
    level?: number;
    /** 针对对象键的定制渲染；返回非 null/undefined 时使用你的渲染结果 */
    renderKey?: (key: string, value: unknown, level: number) => React.ReactNode | null | undefined;
};

const isRecord = (v: unknown): v is Record<string, unknown> =>
    v !== null && typeof v === "object" && !Array.isArray(v);

const isObjLike = (v: unknown): v is Record<string, unknown> | unknown[] =>
    Array.isArray(v) || isRecord(v);

/** 字符串是否可被 JSON.parse 成对象/数组 */
function tryParseJsonString(s: string): unknown | undefined {
    const t = s.trim();
    // 简单启发：以 { 或 [ 开头更可能是 JSON
    if (!(t.startsWith("{") || t.startsWith("["))) return undefined;
    try {
        const parsed = JSON.parse(t);
        if (isObjLike(parsed)) return parsed;
    } catch {}
    return undefined;
}

/** 把字面量 \n / \r\n / \t / \uXXXX 还原为真实字符 */
function unescapeLiterals(s: string): string {
    try {
        // 先把字符串装进一个 JSON 字面量再 parse
        const unescaped = JSON.parse(
            `"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`
        );
        return unescaped as string;
    } catch {
        return s
            .replace(/\\r\\n/g, "\n")
            .replace(/\\n/g, "\n")
            .replace(/\\t/g, "\t");
    }
}

/** 高亮成 <pre><code class="hljs">... */
function renderCodeBlock(raw: string) {
    let html = "";
    try {
        html = hljs.highlightAuto(raw).value;
    } catch {
        html = raw
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
    }
    const safe = DOMPurify.sanitize(html);
    return (
        <pre className="rounded p-2 whitespace-pre-wrap text-[12px] overflow-x-auto">
      <code className="hljs" dangerouslySetInnerHTML={{ __html: safe }} />
    </pre>
    );
}

export function JsonViewer({
                               data,
                               label,
                               defaultOpen = false,
                               level = 0,
                               renderKey,
                           }: JVProps) {
    // 1) 非对象：字符串/数字/布尔/null
    if (!isObjLike(data)) {
        if (typeof data === "string") {
            // 1a) 如果该字符串其实是 JSON → 解析后递归渲染
            const parsed = tryParseJsonString(data);
            if (parsed !== undefined) {
                return <JsonViewer data={parsed} level={level + 1} defaultOpen={false} renderKey={renderKey} />;
            }

            // 1b) 普通字符串：先把字面量换行/制表等反转义，再决定是否按代码块显示
            const s = unescapeLiterals(data);
            if (/\r|\n/.test(s)) return renderCodeBlock(s);

            return (
                <code className="rounded bg-slate-800/70 px-1 py-0.5 text-[12px] text-emerald-200">
                    {s}
                </code>
            );
        }

        return (
            <code className="rounded bg-slate-800/70 px-1 py-0.5 text-[12px] text-emerald-200">
                {String(data)}
            </code>
        );
    }

    // 2) 对象/数组
    const isArr = Array.isArray(data);
    const keys = isArr ? [] : Object.keys(data as Record<string, unknown>);
    const size = isArr ? (data as unknown[]).length : keys.length;
    const summary = label ?? (isArr ? `Array(${size})` : `Object(${size})`);

    // 仅顶层用 defaultOpen，子层不传 open 属性（可自由折叠/展开）
    const topOpenProps = level === 0 && defaultOpen ? { open: true } : {};

    return (
        <details {...topOpenProps} className="my-1">
            <summary className="cursor-pointer select-none text-[12px] text-slate-400">
                {summary}
            </summary>

            <div className="ml-3 mt-1 border-l border-slate-700 pl-3">
                {isArr
                    ? (data as unknown[]).map((v, i) => (
                        <div key={i} className="mb-1">
                            <div className="text-[12px] text-slate-500">[{i}]</div>
                            <JsonViewer data={v} level={level + 1} renderKey={renderKey} />
                        </div>
                    ))
                    : keys.map((k) => {
                        const v = (data as Record<string, unknown>)[k];

                        // 给外部一个机会“接管”该键（例如把 arguments 字符串 JSON 化）
                        const custom = renderKey?.(k, v, level);
                        if (custom !== null && custom !== undefined) {
                            return (
                                <div key={k} className="mb-1">
                                    {custom}
                                </div>
                            );
                        }

                        // 默认渲染：键名 + 递归值
                        return (
                            <div key={k} className="mb-1">
                                <div className="text-[12px] text-slate-500">{k}</div>
                                <JsonViewer data={v} level={level + 1} renderKey={renderKey} />
                            </div>
                        );
                    })}
            </div>
        </details>
    );
}
