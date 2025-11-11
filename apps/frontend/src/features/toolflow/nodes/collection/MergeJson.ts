import { ClassicPreset } from "rete";
import { jsonSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry.tsx"

export class MergeJsonNode extends ClassicPreset.Node {
    constructor() {
        super("MergeJson");
        this.addInput("a", new ClassicPreset.Input(jsonSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(jsonSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(jsonSocket, "out"));
    }
    data(inputs: { a?: any[]; b?: any[] }) {
        const a = (inputs.a?.[0] && typeof inputs.a[0] === "object") ? inputs.a[0] : {};
        const b = (inputs.b?.[0] && typeof inputs.b[0] === "object") ? inputs.b[0] : {};
        return { out: { ...a, ...b } }; // 浅合并：b 覆盖 a
    }
}

registerNode({
    type: "MergeJson",
    title: "MergeJson",
    category: "collection",
    create: () => new MergeJsonNode()
});
