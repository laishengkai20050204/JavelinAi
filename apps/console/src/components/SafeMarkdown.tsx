// src/components/SafeMarkdown.tsx
import React from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import type { PluggableList } from "unified";
import rehypeHighlight from "rehype-highlight";
// 仅当你确实要渲染 HTML 时再开启
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";

export interface SafeMarkdownProps {
    source: string;
    allowHtml?: boolean;
    highlight?: boolean;
    unstyled?: boolean;
    proseClassName?: string;
}

/** 软换行哨兵：SSE 收到空 delta 时插入 */
export const SOFT_NL = "\uE000"; // 私用区字符，不会自然出现

/** 白名单语言（用于 ```lang 后补换行；不做“拆分语言名合并”） */
const ALLOWED_LANGS = [
    "python","py","typescript","ts","javascript","js","jsx","tsx",
    "java","kotlin","kt","scala","go","golang","rust","rs",
    "cpp","c++","c","cs","csharp","swift","objective-c","objectivec","objc",
    "ruby","rb","php","perl","lua","r","matlab","octave","sql",
    "bash","sh","zsh","shell","powershell","ps1",
    "yaml","yml","json","html","xml","css","scss","less",
    "dart","haskell","hs","ocaml","clojure","elisp","lisp","scheme",
    "verilog","systemverilog","vhdl","assembly","asm",
] as const;

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const LANG_ALT = ALLOWED_LANGS.map(escapeRe).join("|");

/** 花括号风格语言：开启自动缩进 */
const BRACE_LANGS = new Set<string>([
    "c","cpp","c++","cs","csharp","java","kotlin","kt","scala",
    "javascript","js","jsx","typescript","ts","tsx",
    "go","golang","rust","rs","swift","php","objective-c","objectivec","objc",
]);

/** 仅在出现 ``` 时处理：行首化、白名单 lang 后缺行补行、闭合后粘字补行、奇数补闭合 */
function fixFences(md: string): string {
    if (!md || md.indexOf("```") === -1) return md;

    let s = md.replace(/\r\n?/g, "\n");

    // 1) 非行首 ``` → 前面补换行
    s = s.replace(/([^\n])```/g, "$1\n```");

    // 2) 白名单语言：```<lang> 后若无换行则补换行（修复 ```javaimport）
    const needLangNewlineRe = new RegExp("(^|\\n)```[ \\t]*(" + LANG_ALT + ")(?![ \\t]*\\n)", "gi");
    s = s.replace(needLangNewlineRe, "$1```$2\n");

    // 3) 闭合 ``` 后若紧跟文字，补换行（不影响开围栏）
    s = s.replace(/```(?![a-z0-9_+-])([^\n])/gi, "```\n$1");

    // 4) 围栏数为奇数 → 末尾补一个 ```
    const n = (s.match(/```/g) || []).length;
    if (n % 2 === 1) s += "\n```";

    return s;
}

/** SOFT_NL 处理：
 *  围栏内头部（``` 到首个换行前）：忽略 SOFT_NL，避免把 ```cpp 拆开
 *  围栏内正文：第一个 SOFT_NL → '\n'；之后仅连续 ≥2 个 SOFT_NL → '\n'；单个忽略
 *  围栏外：连续 SOFT_NL 折叠为 1 个空格
 */
function applySoftBreaks(text: string): string {
    if (!text) return text;

    let inside = false;       // 是否处于 ``` 围栏内
    let inHeader = false;     // 是否在围栏头部（``` 到首个换行前）
    let firstSoftBody = false;
    let out = "";
    let i = 0;

    while (i < text.length) {
        // 捕获三连反引号，切换围栏状态
        if (text[i] === "`" && text[i + 1] === "`" && text[i + 2] === "`") {
            inside = !inside;
            if (inside) {
                inHeader = true;
                firstSoftBody = true;
            } else {
                inHeader = false;
                firstSoftBody = false;
            }
            out += "```";
            i += 3;
            continue;
        }

        if (inside) {
            // 显式换行：标记已离开头部
            if (text[i] === "\n") {
                inHeader = false;
                out += "\n";
                i += 1;
                continue;
            }

            // 围栏内处理 SOFT_NL
            if (text[i] === SOFT_NL) {
                let cnt = 0;
                while (text[i] === SOFT_NL) { cnt++; i++; }

                if (inHeader) {
                    // 头部阶段：忽略，避免把 ```cpp 拆成 c\npp
                    continue;
                }
                if (firstSoftBody) {
                    out += "\n";       // 正文内第一个 SOFT_NL → 换行
                    firstSoftBody = false;
                } else if (cnt >= 2) {
                    out += "\n";       // 之后仅当连续 ≥2 个才换行
                }
                // 单个且不是首个：忽略
                continue;
            }

            // 其它普通字符
            out += text[i];
            i += 1;
            continue;
        }

        // —— 围栏外：SOFT_NL 折叠为 1 个空格
        if (text[i] === SOFT_NL) {
            while (text[i] === SOFT_NL) i += 1;
            if (!out.endsWith(" ") && !out.endsWith("\n")) out += " ";
            continue;
        }

        out += text[i];
        i += 1;
    }

    return out;
}

/** —— 自动缩进（C/Java/JS/… 花括号风格） —— */

// 每层缩进的空格数
const INDENT_SIZE = 4;

// 去掉字符串与行/块注释，仅用于统计当行 { } 数量（尽量避免误判）
function sanitizeForBraceCount(line: string, inBlockComment: { v: boolean }): string {
    let out = "";
    let i = 0;
    let inStr: '"' | "'" | null = null;

    while (i < line.length) {
        if (inBlockComment.v) {
            if (line[i] === "*" && line[i + 1] === "/") {
                inBlockComment.v = false; i += 2; continue;
            }
            i++; // 丢弃块注释内容
            continue;
        }

        // 行注释
        if (!inStr && line[i] === "/" && line[i + 1] === "/") break;

        // 块注释开始
        if (!inStr && line[i] === "/" && line[i + 1] === "*") {
            inBlockComment.v = true; i += 2; continue;
        }

        // 字符串开始/结束
        if (!inStr && (line[i] === '"' || line[i] === "'")) { inStr = line[i] as '"' | "'"; i++; continue; }
        if (inStr) {
            if (line[i] === "\\" && i + 1 < line.length) { i += 2; continue; } // 转义
            if (line[i] === inStr) { inStr = null; i++; continue; }
            i++; continue;
        }

        out += line[i++];
    }
    return out;
}

function autoIndentCStyle(code: string): string {
    const lines = code.replace(/\r\n?/g, "\n").split("\n");

    let depth = 0;
    const out: string[] = [];
    const bc = { v: false }; // inBlockComment 跨行状态

    for (const raw of lines) {
        const trimmed = raw.trim();
        if (trimmed.length === 0) { out.push(""); continue; }

        const forCount = sanitizeForBraceCount(raw, bc);

        // 本行是否以 } 开头（在计数视图下）
        const startsWithClose = /^[\s\t]*[}\])]/.test(forCount);

        // 输出缩进：若以 } 开头，先回退一层
        const indentLevel = Math.max(depth - (startsWithClose ? 1 : 0), 0);
        const indent = " ".repeat(indentLevel * INDENT_SIZE);
        out.push(indent + trimmed);

        // 更新深度：根据 { } 计数（忽略字符串与注释）
        const opens = (forCount.match(/{/g) || []).length;
        const closes = (forCount.match(/}/g) || []).length;
        depth = Math.max(0, depth + opens - closes);
    }

    return out.join("\n");
}

/** 在 ```lang 代码围栏内，对花括号语言进行自动缩进 */
function autoIndentFences(md: string): string {
    if (!md || md.indexOf("```") === -1) return md;

    const fenceRe = /(^|\n)```[ \t]*([^\n]*)\n([\s\S]*?)\n```/g;
    return md.replace(fenceRe, (_m, pfx: string, langRaw: string, body: string) => {
        const lang = (langRaw || "").trim().toLowerCase().split(/\s+/)[0];
        if (!BRACE_LANGS.has(lang)) {
            return `${pfx}\`\`\`${langRaw}\n${body}\n\`\`\``; // 原样返回
        }
        const indented = autoIndentCStyle(body);
        return `${pfx}\`\`\`${langRaw}\n${indented}\n\`\`\``;
    });
}

type CodeProps = React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & {
    inline?: boolean;
    className?: string;
    children?: React.ReactNode;
};

export default function SafeMarkdown({
                                         source,
                                         allowHtml = false,
                                         highlight = true,
                                         unstyled = false,
                                         proseClassName = "",
                                     }: SafeMarkdownProps) {
    // 1) 处理 SSE 软换行 → 2) 修围栏 → 3) 自动缩进（仅花括号语言）
    const prepared = React.useMemo(() => applySoftBreaks(source ?? ""), [source]);
    const fenced = React.useMemo(() => fixFences(prepared), [prepared]);
    const md = React.useMemo(() => autoIndentFences(fenced), [fenced]);

    // 动态加载 highlight 主题
    React.useEffect(() => {
        if (typeof window === "undefined") return;
        let current: "light" | "dark" | null = null;
        const apply = async (isDark: boolean) => {
            const next: "light" | "dark" = isDark ? "dark" : "light";
            if (current === next) return;
            current = next;
            if (isDark) await import("highlight.js/styles/github-dark.css");
            else await import("highlight.js/styles/github.css");
        };
        const mql = window.matchMedia("(prefers-color-scheme: dark)");
        apply(mql.matches);
        const onChange = (e: MediaQueryListEvent) => apply(e.matches);
        mql.addEventListener("change", onChange);
        return () => mql.removeEventListener("change", onChange);
    }, []);

    const rehypePlugins: PluggableList = [];
    if (highlight) rehypePlugins.push(rehypeHighlight);
    if (allowHtml) rehypePlugins.push(rehypeRaw, rehypeSanitize);

    // 无高亮时也清晰的代码样式
    const components: Components = {
        code({ inline, className, children, ...props }: CodeProps) {
            const cls = className ?? "";
            const hasSyntax = /\blanguage-/.test(cls) || /\bhljs\b/.test(cls);

            if (inline) {
                const base = "rounded-md font-mono text-[0.9em]";
                const fallback =
                    "bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-slate-100 px-1 py-0.5";
                return (
                    <code className={`${base} ${hasSyntax ? "" : fallback} ${cls}`} {...props}>
                        {children}
                    </code>
                );
            }

            if (hasSyntax) {
                return (
                    <pre className="rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-auto p-3">
            <code className={cls} {...props}>
              {children}
            </code>
          </pre>
                );
            }

            return (
                <pre className="rounded-xl border border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-3 shadow-sm overflow-auto whitespace-pre-wrap break-words">
        <code className="font-mono">{children}</code>
      </pre>
            );
        },
    };

    const wrapperClass = unstyled
        ? undefined
        : ["prose prose-sm dark:prose-invert max-w-none", proseClassName].filter(Boolean).join(" ");

    return (
        <div className={wrapperClass}>
            <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={rehypePlugins} components={components}>
                {md}
            </ReactMarkdown>
        </div>
    );
}
