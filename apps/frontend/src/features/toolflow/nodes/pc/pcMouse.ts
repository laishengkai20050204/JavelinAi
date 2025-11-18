// apps/frontend/src/features/toolflow/nodes/pc/pcMouse.ts
import { ClassicPreset } from "rete";
import { controlSocket, numberSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

const CURRENT_ORIGIN =
    typeof window !== "undefined" && window.location ? window.location.origin : "";
const DEFAULT_BASE_URL = CURRENT_ORIGIN || "";

// ===== 通用小工具函数 =====
function toNumber(v: any, fallback: number): number {
    const n = typeof v === "number" ? v : Number(v);
    return Number.isFinite(n) ? n : fallback;
}

// 从 api.ctx 里拿 baseUrl，没有就用当前站点
function getBaseUrl(api: any): string {
    const raw = (api?.ctx?.pcAgentBaseUrl as string | undefined) ?? DEFAULT_BASE_URL;
    if (!raw) return "";
    return raw.endsWith("/") ? raw.slice(0, -1) : raw;
}

// ===================== MouseMove =====================
export class MouseMoveNode extends ClassicPreset.Node {
    constructor() {
        super("MouseMove");

        // 控制流
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));

        // 数据输入口：x / y / duration
        this.addInput("xIn", new ClassicPreset.Input(numberSocket, "x"));
        this.addInput("yIn", new ClassicPreset.Input(numberSocket, "y"));
        this.addInput("durIn", new ClassicPreset.Input(numberSocket, "duration"));

        // 控件默认值
        this.addControl(
            "x",
            new ClassicPreset.InputControl("number", { initial: 100 })
        );
        this.addControl(
            "y",
            new ClassicPreset.InputControl("number", { initial: 100 })
        );
        this.addControl(
            "duration",
            new ClassicPreset.InputControl("number", { initial: 0 })
        );
    }

    data() {
        return { next: 1 };
    }
}

registerNode({
    type: "MouseMove",
    title: "MouseMove",
    category: "control",
    create: () => new MouseMoveNode(),
    runtime: async (api: any, node: any) => {
        console.log("[MouseMoveNode] runtime ENTER", node.id);

        const [xFromInput] = await api.readInput(node.id, "xIn");
        const [yFromInput] = await api.readInput(node.id, "yIn");
        const [durFromInput] = await api.readInput(node.id, "durIn");

        const xCtrl = Number(api.readControl(node, "x") ?? 0);
        const yCtrl = Number(api.readControl(node, "y") ?? 0);
        const durCtrl = Number(api.readControl(node, "duration") ?? 0);

        const x = toNumber(xFromInput, xCtrl);
        const y = toNumber(yFromInput, yCtrl);
        let duration = toNumber(durFromInput, durCtrl);
        if (!Number.isFinite(duration) || duration < 0) duration = 0;

        const baseUrl = getBaseUrl(api);

        try {
            const res = await fetch(`${baseUrl}/mouse/move`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ x, y, duration }),
            });

            if (!res.ok) {
                const text = await res.text();
                console.error(
                    "[MouseMoveNode] 调用失败:",
                    res.status,
                    res.statusText,
                    text
                );
            } else {
                const json = await res.json();
                console.log("[MouseMoveNode] 调用成功:", json);
            }
        } catch (err) {
            console.error("[MouseMoveNode] fetch 失败:", err);
        }

        return { next: api.nextBy(node.id, "next") };
    },
});

// ===================== MouseClick =====================
export class MouseClickNode extends ClassicPreset.Node {
    constructor() {
        super("MouseClick");

        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));

        this.addInput("clicksIn", new ClassicPreset.Input(numberSocket, "clicks"));
        this.addInput(
            "intervalIn",
            new ClassicPreset.Input(numberSocket, "interval(s)")
        );

        this.addControl(
            "button",
            new ClassicPreset.InputControl("text", { initial: "left" })
        );
        this.addControl(
            "clicks",
            new ClassicPreset.InputControl("number", { initial: 1 })
        );
        this.addControl(
            "interval",
            new ClassicPreset.InputControl("number", { initial: 0.1 })
        );
    }

    data() {
        return { next: 1 };
    }
}

registerNode({
    type: "MouseClick",
    title: "MouseClick",
    category: "control",
    create: () => new MouseClickNode(),
    runtime: async (api: any, node: any) => {
        console.log("[MouseClickNode] runtime ENTER", node.id);

        const [clicksFromInput] = await api.readInput(node.id, "clicksIn");
        const [intervalFromInput] = await api.readInput(node.id, "intervalIn");

        const buttonCtrl = (api.readControl(node, "button") ?? "left") as string;
        const clicksCtrl = Number(api.readControl(node, "clicks") ?? 1);
        const intervalCtrl = Number(api.readControl(node, "interval") ?? 0.1);

        const rawButton = (buttonCtrl || "").toLowerCase();
        const button =
            rawButton === "right"
                ? "right"
                : rawButton === "middle"
                    ? "middle"
                    : "left";

        const clicks = toNumber(clicksFromInput, clicksCtrl);
        const interval = toNumber(intervalFromInput, intervalCtrl);

        const baseUrl = getBaseUrl(api);

        try {
            const res = await fetch(`${baseUrl}/mouse/click`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ button, clicks, interval }),
            });

            if (!res.ok) {
                const text = await res.text();
                console.error(
                    "[MouseClickNode] 调用失败:",
                    res.status,
                    res.statusText,
                    text
                );
            } else {
                const json = await res.json();
                console.log("[MouseClickNode] 调用成功:", json);
            }
        } catch (err) {
            console.error("[MouseClickNode] fetch 失败:", err);
        }

        return { next: api.nextBy(node.id, "next") };
    },
});

// ===================== MouseScroll =====================
export class MouseScrollNode extends ClassicPreset.Node {
    constructor() {
        super("MouseScroll");

        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));

        this.addInput("amountIn", new ClassicPreset.Input(numberSocket, "amount"));

        this.addControl(
            "amount",
            new ClassicPreset.InputControl("number", { initial: -500 })
        );
    }

    data() {
        return { next: 1 };
    }
}

registerNode({
    type: "MouseScroll",
    title: "MouseScroll",
    category: "control",
    create: () => new MouseScrollNode(),
    runtime: async (api: any, node: any) => {
        console.log("[MouseScrollNode] runtime ENTER", node.id);

        const [amountFromInput] = await api.readInput(node.id, "amountIn");
        const amountCtrl = Number(api.readControl(node, "amount") ?? 0);
        const amount = toNumber(amountFromInput, amountCtrl);

        const baseUrl = getBaseUrl(api);

        try {
            const res = await fetch(`${baseUrl}/mouse/scroll`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ amount }),
            });

            if (!res.ok) {
                const text = await res.text();
                console.error(
                    "[MouseScrollNode] 调用失败:",
                    res.status,
                    res.statusText,
                    text
                );
            } else {
                const json = await res.json();
                console.log("[MouseScrollNode] 调用成功:", json);
            }
        } catch (err) {
            console.error("[MouseScrollNode] fetch 失败:", err);
        }

        return { next: api.nextBy(node.id, "next") };
    },
});
