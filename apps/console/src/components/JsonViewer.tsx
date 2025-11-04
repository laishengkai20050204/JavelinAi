// src/components/JsonViewer.tsx
import DOMPurify from "dompurify";
import hljs from "highlight.js";

type JsonValue = unknown;
type JVProps = { data: JsonValue; label?: string; defaultOpen?: boolean; level?: number };

const isRecord = (v: unknown): v is Record<string, unknown> =>
    v !== null && typeof v === "object" && !Array.isArray(v);

export function JsonViewer({ data, label, defaultOpen = false, level = 0 }: JVProps) {
    const isArr = Array.isArray(data);
    const isObjLike = isArr || isRecord(data);

    // 非对象（含字符串/数字/布尔等）直接渲染
    if (!isObjLike) {
        if (typeof data === "string" && /[\r\n]/.test(data)) {
            let html = "";
            try {
                html = hljs.highlightAuto(data).value;
            } catch {
                html = data.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
            }
            const safe = DOMPurify.sanitize(html);
            return (
                <pre className="rounded p-2 whitespace-pre-wrap text-[12px] overflow-x-auto">
          <code className="hljs" dangerouslySetInnerHTML={{ __html: safe }} />
        </pre>
            );
        }
        return (
            <code className="rounded bg-slate-800/70 px-1 py-0.5 text-[12px] text-emerald-200">
                {String(data)}
            </code>
        );
    }

    // 计算 keys / size（只在对象分支需要 keys）
    let keys: string[] = [];
    let size = 0;
    if (isArr) {
        size = (data as unknown[]).length;
    } else {
        keys = Object.keys(data as Record<string, unknown>);
        size = keys.length;
    }

    const summary = label ?? (isArr ? `Array(${size})` : `Object(${size})`);

    return (
        <details open={level < 1 ? defaultOpen : false} className="my-1">
            <summary className="cursor-pointer select-none text-[12px] text-slate-400">
                {summary}
            </summary>
            <div className="ml-3 mt-1 border-l border-slate-700 pl-3">
                {isArr
                    ? (data as unknown[]).map((v, i) => (
                        <div key={i} className="mb-1">
                            <div className="text-[12px] text-slate-500">[{i}]</div>
                            <JsonViewer data={v} level={level + 1} />
                        </div>
                    ))
                    : keys.map((k) => {
                        const value = (data as Record<string, unknown>)[k];
                        return (
                            <div key={k} className="mb-1">
                                <div className="text-[12px] text-slate-500">{k}</div>
                                <JsonViewer data={value} level={level + 1} />
                            </div>
                        );
                    })}
            </div>
        </details>
    );
}
