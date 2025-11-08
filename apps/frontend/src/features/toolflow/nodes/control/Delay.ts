import { ClassicPreset } from "rete";
import { controlSocket, numberSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

/**
 * Delay
 * - 控制流：in -> 等待 ms -> next
 * - ms 可来自数据输入口(msIn) 或 数字控件(ms)，优先用输入口
 */
export class DelayNode extends ClassicPreset.Node {
    constructor() {
        super("Delay");
        // 控制流入口/出口
        this.addInput("in", new ClassicPreset.Input(controlSocket, "in"));
        this.addOutput("next", new ClassicPreset.Output(controlSocket, "next"));

        // （可选）数据输入口：动态指定毫秒数
        this.addInput("msIn", new ClassicPreset.Input(numberSocket, "ms"));

        // 数字控件：默认 500ms
        this.addControl(
            "ms",
            new ClassicPreset.InputControl("number", { initial: 500 })
        );
    }

    // 控制流节点通常只需要暴露 "next" 键即可
    data() {
        return { next: 1 };
    }
}

registerNode({
    type: "Delay",
    title: "Delay (ms)",
    category: "control",
    create: () => new DelayNode(),

    // 运行时语义：等待指定毫秒，再走 next
    runtime: async (api, node) => {
        const [msFromInput] = await api.readInput(node.id, "msIn");
        const msCtrl = Number(api.readControl(node, "ms") ?? 0);
        let ms = Number(msFromInput ?? msCtrl ?? 0);
        if (!Number.isFinite(ms) || ms < 0) ms = 0;

        await new Promise((r) => setTimeout(r, ms));

        // Delay 不修改状态，无需 api.invalidate()
        return { next: api.nextBy(node.id, "next") };
    }
});
