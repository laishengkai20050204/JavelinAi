import { ClassicPreset } from "rete";
import { controlSocket, jsonSocket, stringSocket, Hooks } from "../basic"; // ✅ 加入 Hooks
import { registerNode } from "../../core/nodeRegistry";

/**
 * OpenUrl
 * - 控制流：in -> 打开 URL -> next
 * - URL/target 的优先级：
 *   url:    urlIn(连线) > args.url > 控件(url)
 *   target: targetIn(连线) > args.target > 控件(target)
 */
export class OpenUrlNode extends ClassicPreset.Node {
    constructor() {
        super("OpenUrl");

        // 控制流
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));

        // 数据输入
        this.addInput("urlIn", new ClassicPreset.Input(jsonSocket, "url"));
        this.addInput("targetIn", new ClassicPreset.Input(stringSocket, "target?"));

        // 控件
        this.addControl("url", new ClassicPreset.InputControl("text", { initial: "" }));
        this.addControl("target", new ClassicPreset.InputControl("text", { initial: "new-tab" })); // 'new-tab' | 'self'
    }
    data() { return { next: 1 }; }
}

function normalizeUrl(raw?: any): string | null {
    let s = raw;
    if (s && typeof s === "object") {
        // 常见形态：{url:"..."}/{href:"..."}；否则转 JSON 文本兜底
        s = (s.url ?? s.href ?? JSON.stringify(s));
    }
    s = String(s ?? "").trim();
    if (!s) return null;
    if (!/^[a-z]+:\/\//i.test(s)) s = "https://" + s.replace(/^\/+/, "");
    return s;
}

function normalizeTarget(raw?: any): "_blank" | "_self" {
    const s = String(raw ?? "").trim().toLowerCase();
    if (s === "self" || s === "_self") return "_self";
    return "_blank"; // new-tab 默认
}

// 小工具：从多个候选中取第一个非空
const pick = <T,>(...xs: T[]) => xs.find(v => {
    const s = (typeof v === "string") ? v.trim() : v;
    return s !== undefined && s !== null && s !== "";
});

registerNode({
    type: "OpenUrl",
    title: "Open URL",
    category: "io",
    create: () => new OpenUrlNode(),

    runtime: async (api, node) => {
        // 1) 输入口
        const [urlFromInput]    = await api.readInput(node.id, "urlIn");
        const [targetFromInput] = await api.readInput(node.id, "targetIn");

        // 2) 控件
        const urlCtrl    = api.readControl(node, "url");
        const targetCtrl = api.readControl(node, "target");

        // 3) 多通道读取 args：Hooks.getVar → api.getVar → api.vars → 全局兜底
        const getByPath = (obj: any, path: string) =>
            path.split(".").reduce((acc, k) => (acc == null ? undefined : acc[k]), obj);

        const getArg = (path: string) => {
            const hv = typeof Hooks.getVar === "function" ? Hooks.getVar(path) : undefined;
            const av = typeof (api as any).getVar === "function" ? (api as any).getVar(path) : undefined;
            const vv = (api as any)?.vars ? getByPath((api as any).vars, path) : undefined;
            const gv = (globalThis as any).__JAVELIN_SCOPE__ ? getByPath((globalThis as any).__JAVELIN_SCOPE__, path) : undefined;
            return hv ?? av ?? vv ?? gv;
        };

        const argUrl    = getArg("args.url")    ?? getArg("url");
        const argTarget = getArg("args.target") ?? getArg("target");

        // 4) 三重优先级：输入口 > args > 控件
        const finalUrl =
            (() => {
                let v = urlFromInput ?? argUrl ?? urlCtrl ?? "";
                if (v && typeof v === "object") v = v.url ?? v.href ?? JSON.stringify(v);
                v = String(v ?? "").trim();
                if (!v) return null;
                if (!/^https?:\/\//i.test(v)) v = "https://" + v.replace(/^\/+/, "");
                return v;
            })();

        const finalTarget =
            (() => {
                const raw = (targetFromInput ?? argTarget ?? targetCtrl ?? "new-tab");
                const s = String(raw).trim().toLowerCase();
                return (s === "self" || s === "_self") ? "_self" : "_blank";
            })();

        console.log("[OpenUrl.runtime] inputs", {
            urlFromInput, argUrl, urlCtrl,
            targetFromInput, argTarget, targetCtrl,
            finalUrl, finalTarget,
            hasHooks: typeof Hooks.getVar === "function"
        });

        // 5) 打开
        try {
            if (finalUrl) {
                if (typeof (api as any).openUrl === "function") {
                    await (api as any).openUrl(finalUrl, finalTarget);
                } else {
                    window.open(finalUrl, finalTarget);
                }
            }
        } catch (e) {
            console.warn("[OpenUrl.runtime] open failed:", e);
        }

        return { next: api.nextBy(node.id, "next") };
    }
});

