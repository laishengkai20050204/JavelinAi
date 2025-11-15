// src/components/SafeMarkdown.tsx
import React from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import type { PluggableList } from "unified";
import rehypeHighlight from "rehype-highlight";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";

export interface SafeMarkdownProps {
    source: string;
    /** 是否允许渲染 HTML（启用时会同时开启严格白名单的 sanitize） */
    allowHtml?: boolean;
    /** 是否开启语法高亮（rehype-highlight） */
    highlight?: boolean;
    /** 关闭 prose 样式包装（自行控制外层样式） */
    unstyled?: boolean;
    /** 追加到 prose 容器上的 className */
    proseClassName?: string;
}

/** 统一换行（把 CRLF/CR 归一为 LF） */
function normalize(md: string | undefined | null) {
    if (!md) return "";
    return md.replace(/\r\n?/g, "\n");
}

/**
 * 将“内部地址”（127.0.0.1 / localhost / host.docker.internal / 9000/9001端口）
 * 的前缀改成当前页面的 origin，保留路径 + 查询参数 + hash。
 */
function rewriteInternalUrl(url: string): string {
    if (typeof window === "undefined") return url;
    try {
        const u = new URL(url, window.location.origin);

        const internalHosts = ["127.0.0.1", "localhost", "host.docker.internal"];
        const internalPorts = ["9000", "9001"];

        const isInternalHost = internalHosts.includes(u.hostname);
        const isInternalPort = internalPorts.includes(u.port);

        // 只改内部地址，外网链接保持原样
        if (!isInternalHost && !isInternalPort) return url;

        const origin = window.location.origin;
        let path = u.pathname || "/";

        // 已经是 /minio/... 就不重复加
        if (!path.startsWith("/minio/")) {
            if (!path.startsWith("/")) path = "/" + path;
            path = "/minio" + path; // /minio/<bucket>/...
        }

        return origin + path + u.search + u.hash;
    } catch {
        return url;
    }
}


/** 基于默认 schema 的最小扩展：允许代码高亮 class（language-xxx / hljs） */
const sanitizeSchema = {
    ...defaultSchema,
    attributes: {
        ...defaultSchema.attributes,
        code: [
            ...(defaultSchema.attributes?.code || []),
            ["className", /^language-[a-z0-9+-]+$/i],
        ],
        pre: [
            ...(defaultSchema.attributes?.pre || []),
            ["className", /^.+$/],
        ],
        span: [
            ...(defaultSchema.attributes?.span || []),
            ["className", /^hljs.*$/],
        ],
    },
};

type CodeProps = React.DetailedHTMLProps<
    React.HTMLAttributes<HTMLElement>,
    HTMLElement
> & {
    inline?: boolean;
    className?: string;
    children?: React.ReactNode;
};

type LinkProps = React.DetailedHTMLProps<
    React.AnchorHTMLAttributes<HTMLAnchorElement>,
    HTMLAnchorElement
>;

type ImgProps = React.DetailedHTMLProps<
    React.ImgHTMLAttributes<HTMLImageElement>,
    HTMLImageElement
>;


export default function SafeMarkdown({
                                         source,
                                         allowHtml = false,
                                         highlight = true,
                                         unstyled = false,
                                         proseClassName = "",
                                     }: SafeMarkdownProps) {
    // 仅做最小预处理：换行归一
    const md = React.useMemo(() => normalize(source), [source]);

    const rehypePlugins: PluggableList = [];
    if (highlight) rehypePlugins.push(rehypeHighlight);
    if (allowHtml) {
        rehypePlugins.push(rehypeRaw); // 先 raw 再 sanitize
        rehypePlugins.push([rehypeSanitize, sanitizeSchema]);
    } else {
        rehypePlugins.push([rehypeSanitize, sanitizeSchema]);
    }

    const components: Components = {
        // 新增：统一处理链接 href
        a({ href, children, ...props }: LinkProps) {
            const raw = typeof href === "string" ? href : "";
            const safeHref = raw ? rewriteInternalUrl(raw) : raw;

            let label: React.ReactNode = children;

            if (raw && safeHref && React.Children.count(children) === 1) {
                const only = React.Children.toArray(children)[0];
                if (typeof only === "string" && only.trim() === raw.trim()) {
                    // 如果显示文字本来就是原始 url，就一起替换成重写后的
                    label = safeHref;
                }
            }

            return (
                <a href={safeHref} {...props}>
                    {label}
                </a>
            );
        },

        // 新增：统一处理图片 src
        img({ src, ...props }: ImgProps) {
            const raw = typeof src === "string" ? src : "";
            const safeSrc = raw ? rewriteInternalUrl(raw) : raw;

            return <img src={safeSrc} {...props} />;
        },

        // 原来的 code 渲染保持不变
        code({ inline, className, children, ...props }: CodeProps) {
            const hasSyntax =
                /\blanguage-/.test(className || "") || /\bhljs\b/.test(className || "");

            if (inline) {
                const base = "rounded-md font-mono text-[0.9em] px-1 py-0.5";
                const fallback =
                    "bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-slate-100";
                return (
                    <code className={`${base} ${hasSyntax ? "" : fallback} ${className || ""}`} {...props}>
                        {children}
                    </code>
                );
            }

            if (hasSyntax) {
                return (
                    <pre className="rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-auto p-3">
                    <code className={className} {...props}>
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
        : ["prose prose-sm dark:prose-invert max-w-none", proseClassName]
            .filter(Boolean)
            .join(" ");

    return (
        <div className={wrapperClass}>
            <ReactMarkdown
                remarkPlugins={[remarkGfm, remarkBreaks]}
                rehypePlugins={rehypePlugins}
                components={components}
            >
                {md}
            </ReactMarkdown>
        </div>
    );
}


