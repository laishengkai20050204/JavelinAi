import { ClassicPreset } from "rete";
import { jsonSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

function safeGetItem(store: Storage | undefined, key: string): string | undefined {
    try { return store?.getItem?.(key) ?? undefined; } catch { return undefined; }
}

function readCookies(): Record<string, string> {
    if (typeof document === "undefined") return {};
    const out: Record<string, string> = {};
    const raw = document.cookie || "";
    raw.split(";").forEach(p => {
        const [k, ...rest] = p.split("=");
        if (!k) return;
        out[k.trim()] = decodeURIComponent((rest.join("=") || "").trim());
    });
    return out;
}

export class BrowserHeadersNode extends ClassicPreset.Node {
    constructor() {
        super("BrowserHeaders");
        // 可配置项：哪些来源、候选 key、是否加 Bearer、是否带 CSRF
        this.addControl("useLocalStorage", new ClassicPreset.InputControl("text", { initial: "true" }));
        this.addControl("useSessionStorage", new ClassicPreset.InputControl("text", { initial: "true" }));
        this.addControl("useCookies", new ClassicPreset.InputControl("text", { initial: "true" }));
        this.addControl("bearerify", new ClassicPreset.InputControl("text", { initial: "true" }));
        this.addControl("tokenKeys", new ClassicPreset.InputControl("text", { initial: "token,access_token,jwt,id_token,auth_token,Authorization" }));
        this.addControl("csrfKeys", new ClassicPreset.InputControl("text", { initial: "csrf_token,XSRF-TOKEN,CSRF-TOKEN" }));

        this.addOutput("out", new ClassicPreset.Output(jsonSocket, "headers"));
    }

    data() {
        if (typeof window === "undefined") return { out: {} };

        const getCtrl = (k: string) => {
            const c: any = (this as any).controls?.get?.(k) ?? (this as any).controls?.[k];
            return typeof c?.getValue === "function" ? c.getValue() : c?.value;
        };

        const yes = (v: any) => String(v ?? "").trim().toLowerCase() === "true";
        const useLS = yes(getCtrl("useLocalStorage"));
        const useSS = yes(getCtrl("useSessionStorage"));
        const useCK = yes(getCtrl("useCookies"));
        const bearerify = yes(getCtrl("bearerify"));

        const tokenKeys = String(getCtrl("tokenKeys") ?? "")
            .split(",").map(s => s.trim()).filter(Boolean);
        const csrfKeys = String(getCtrl("csrfKeys") ?? "")
            .split(",").map(s => s.trim()).filter(Boolean);

        const cookies = useCK ? readCookies() : {};

        const pickFirst = (...vals: (string | undefined)[]) =>
            vals.find(v => typeof v === "string" && v.trim().length > 0);

        const fromStores = (keys: string[]) => {
            const ls = useLS ? window.localStorage : undefined;
            const ss = useSS ? window.sessionStorage : undefined;
            for (const k of keys) {
                const v = pickFirst(
                    safeGetItem(ls, k),
                    safeGetItem(ss, k),
                    useCK ? cookies[k] : undefined
                );
                if (v) return v;
            }
            return undefined;
        };

        let token = fromStores(tokenKeys);
        if (token && bearerify && !token.toLowerCase().startsWith("bearer ")) {
            token = `Bearer ${token}`;
        }
        const csrf = pickFirst(
            (typeof document !== "undefined"
                ? (document.querySelector('meta[name="csrf-token"]') as HTMLMetaElement | null)?.content
                : undefined),
            fromStores(csrfKeys)
        );

        const out: Record<string, string> = { Accept: "application/json" };
        if (token) out["Authorization"] = token;
        if (csrf) out["X-CSRF-Token"] = csrf;

        return { out };
    }
}

registerNode({
    type: "BrowserHeaders",
    title: "BrowserHeaders",
    category: "io",
    create: () => new BrowserHeadersNode()
});
