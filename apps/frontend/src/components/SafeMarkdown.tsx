// src/components/SafeMarkdown.tsx
import React from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import type { PluggableList } from "unified";
import rehypeHighlight from "rehype-highlight";
import rehypeRaw from "rehype-raw";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";

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


/** 基于默认 schema 的最小扩展：允许代码高亮 class（language-xxx / hljs）+ KaTeX class */
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
            ["className", /^katex.*$/], // 允许 KaTeX 的 class
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

    // remark 插件：GFM + 自动换行 + 数学公式
    const remarkPlugins: PluggableList = [remarkGfm, remarkBreaks, remarkMath];

    // rehype 插件
    const rehypePlugins: PluggableList = [];

    // 只在允许 HTML 的时候解析 & 清洗 HTML
    if (allowHtml) {
        // 1) 把 Markdown 里的 raw HTML 解析成节点
        rehypePlugins.push(rehypeRaw);
        // 2) 对这些 HTML 节点做安全过滤
        rehypePlugins.push([rehypeSanitize, sanitizeSchema]);
    }

    // 代码高亮（只处理 <code>，相对安全）
    if (highlight) {
        rehypePlugins.push(rehypeHighlight);
    }

    // 数学公式渲染（remark-math 产生的 math 节点 -> KaTeX）
    rehypePlugins.push(rehypeKatex);

    // 工具函数：判断是不是图片链接
    function isImageUrl(url: string): boolean {
        return /\.(png|jpe?g|gif|webp|svg)(\?.*)?$/i.test(url);
    }

    const components: Components = {
        a({ href, children, ...props }: LinkProps) {
            const raw = typeof href === "string" ? href : "";

            const childrenArray = React.Children.toArray(children);
            const isSingleTextChild =
                childrenArray.length === 1 &&
                typeof childrenArray[0] === "string" &&
                childrenArray[0].trim() === raw.trim();

            // 如果 href 本身是图片地址，并且 label 就是原始 URL → 当成图片渲染
            if (raw && isSingleTextChild && isImageUrl(raw)) {
                return <img src={raw} alt="" />;
            }

            // 否则按普通链接
            return (
                <a href={raw} {...props}>
                    {children}
                </a>
            );
        },

        img({ src, ...props }: ImgProps) {
            const raw = typeof src === "string" ? src : "";
            return <img src={raw} {...props} />;
        },

        code({ inline, className, children, ...props }: CodeProps) {
            const hasSyntax =
                /\blanguage-/.test(className || "") || /\bhljs\b/.test(className || "");

            // ✅ 行内代码：只能渲染 <code>，绝对不要出现 <pre>
            if (inline) {
                const base = "rounded-md font-mono text-[0.9em] px-1 py-0.5";
                const fallback =
                    "bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-slate-100";

                return (
                    <code
                        className={`${base} ${hasSyntax ? "" : fallback} ${className || ""}`}
                        {...props}
                    >
                        {children}
                    </code>
                );
            }

            // ✅ 代码块：这里才允许用 <pre><code>
            const preClassWhenSyntax =
                "rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-auto p-3";
            const preClassPlain =
                "rounded-xl border border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-3 shadow-sm overflow-auto whitespace-pre-wrap break-words";

            const preClass = hasSyntax ? preClassWhenSyntax : preClassPlain;

            return (
                <pre className={preClass}>
                    <code className={className || "font-mono"} {...props}>
                        {children}
                    </code>
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
                remarkPlugins={remarkPlugins}
                rehypePlugins={rehypePlugins}
                components={components}
            >
                {md}
            </ReactMarkdown>
        </div>
    );
}
