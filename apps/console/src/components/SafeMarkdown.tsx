// src/components/SafeMarkdown.tsx
import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { PluggableList } from "unified";
import rehypeHighlight from "rehype-highlight";
// 可选：仅当你确实要渲染用户提供的 HTML 时才开启
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";

export interface SafeMarkdownProps {
    source: string;
    allowHtml?: boolean;  // 是否允许文中原生 HTML（默认 false）
    highlight?: boolean;  // 是否启用代码高亮（默认 true）
}

function fixFences(md: string): string {
    if (!md) return md;
    let s = md.replace(/\r\n?/g, "\n");
    // 非行首 ``` ：前面补换行
    s = s.replace(/([^\n])```/g, "$1\n```");
    // ```lang 后没换行（如 ```cpp#include / ```pythondef）
    s = s.replace(/(^|\n)```([a-z0-9_+-]+)(?=\S)/gi, "$1```$2\n");
    // 闭合 ``` 后面粘文字，补换行
    s = s.replace(/```(?![a-z0-9_+-])([^\n])/gi, "```\n$1");
    // 围栏数为奇数，最后补一个闭合
    const n = (s.match(/```/g) || []).length;
    if (n % 2 === 1) s += "\n```";
    return s;
}

const SafeMarkdown: React.FC<SafeMarkdownProps> = ({ source, allowHtml = false, highlight = true }) => {
    const md = React.useMemo(() => fixFences(source ?? ""), [source]);

    // —— 根据系统主题动态加载 highlight.js 主题（github / github-dark）
    React.useEffect(() => {
        if (typeof window === "undefined") return;

        let current: "light" | "dark" | null = null;

        const apply = async (isDark: boolean) => {
            const next: "light" | "dark" = isDark ? "dark" : "light";
            if (current === next) return;         // 避免重复加载
            current = next;

            if (isDark) {
                await import("highlight.js/styles/github-dark.css");
            } else {
                await import("highlight.js/styles/github.css");
            }
            // 说明：多次切换时会注入多个 <style>，但后加载的样式优先生效；
            // 切换频率极低（随系统主题），无需手动卸载。
        };

        const mql = window.matchMedia("(prefers-color-scheme: dark)");
        apply(mql.matches);

        const onChange = (e: MediaQueryListEvent) => apply(e.matches);
        mql.addEventListener("change", onChange);
        return () => mql.removeEventListener("change", onChange);
    }, []); // 仅初始化一次

    const rehypePlugins: PluggableList = [];
    if (highlight) rehypePlugins.push(rehypeHighlight);
    if (allowHtml) rehypePlugins.push(rehypeRaw, rehypeSanitize);

    return (
        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={rehypePlugins}>
            {md}
        </ReactMarkdown>
    );
};

export default SafeMarkdown;
