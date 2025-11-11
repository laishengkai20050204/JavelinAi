import { ClassicPreset } from "rete";
import { controlSocket, jsonSocket, stringSocket, Hooks } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

/** ---- 安全辅助 ---- **/
const SAFE_SCHEMES = ["http:", "https:"] as const;
function normalizeUrl(raw?: any): string | null {
    let s = raw;
    if (s && typeof s === "object") s = s.url ?? s.href ?? JSON.stringify(s);
    s = String(s ?? "").trim();
    if (!s) return null;

    // 补协议
    if (!/^[a-z]+:\/\//i.test(s)) s = "https://" + s.replace(/^\/+/, "");

    try {
        const u = new URL(s);
        if (!SAFE_SCHEMES.includes(u.protocol as any)) return null;
        return u.toString();
    } catch {
        return null;
    }
}
function normalizeTarget(raw?: any): "_blank" | "_self" {
    const s = String(raw ?? "").trim().toLowerCase();
    return s === "self" || s === "_self" ? "_self" : "_blank";
}

// 短期去重：避免同 URL 连续多次打开
let _lastOpen = { url: "", ts: 0 };
const dedup = (url: string, ms = 800) => {
    const now = Date.now();
    if (_lastOpen.url === url && now - _lastOpen.ts < ms) return true;
    _lastOpen = { url, ts: now };
    return false;
};

// 事件派发（被拦截时由 UI 兜底）
function emitPending(url: string, target: "_blank" | "_self") {
    const payload = { url, target, ts: Date.now() };
    (globalThis as any).__JAVELIN_PENDING_LINK__ = payload;
    if (typeof window !== "undefined") {
        window.dispatchEvent(new CustomEvent("javelin:pending-open-url", { detail: payload } as any));
    }
}

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
        this.addControl("target", new ClassicPreset.InputControl("text", { initial: "new-tab" }));
    }
    data() { return { next: 1 }; } // 只暴露控制流
}

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

        // 3) args 多通道（Hooks → api.getVar → api.vars → 全局）
        const getByPath = (obj: any, path: string) =>
            path.split(".").reduce((acc, k) => (acc == null ? undefined : acc[k]), obj);
        const getArg = (path: string) => {
            const hv = typeof Hooks?.getVar === "function" ? Hooks.getVar(path) : undefined;
            const av = typeof (api as any)?.getVar === "function" ? (api as any).getVar(path) : undefined;
            const vv = (api as any)?.vars ? getByPath((api as any).vars, path) : undefined;
            const gv = (globalThis as any).__JAVELIN_SCOPE__ ? getByPath((globalThis as any).__JAVELIN_SCOPE__, path) : undefined;
            return hv ?? av ?? vv ?? gv;
        };
        const argUrl    = getArg("args.url")    ?? getArg("url");
        const argTarget = getArg("args.target") ?? getArg("target");

        // 4) 计算最终 url/target（输入口 > args > 控件）
        const candidates = urlFromInput ?? argUrl ?? urlCtrl ?? "";
        const arr = Array.isArray(candidates) ? candidates : [candidates];
        const urls = arr
            .map(normalizeUrl)
            .filter((x): x is string => !!x)
            .slice(0, 3); // 最多尝试 3 个，避免一次性触发多重拦截

        const finalTarget = normalizeTarget(targetFromInput ?? argTarget ?? targetCtrl ?? "new-tab");

        // 5) 打开（优先使用 api.openUrl；否则 window.open；被拦截则事件兜底）
        try {
            for (const u of urls) {
                if (dedup(u)) continue;

                if (typeof (api as any).openUrl === "function") {
                    await (api as any).openUrl(u, finalTarget);
                } else if (typeof window !== "undefined") {
                    const w = window.open(u, finalTarget);
                    if (!w) emitPending(u, finalTarget);
                }
            }
        } catch (e) {
            console.warn("[OpenUrl.runtime] open failed:", e);
        }

        // 6) 仅返回控制流（与“以前”的外观一致）
        return { next: api.nextBy(node.id, "next") };
    }
});
