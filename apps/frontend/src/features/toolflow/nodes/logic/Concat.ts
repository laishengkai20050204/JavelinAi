import { ClassicPreset } from "rete";
import { stringSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

/** Concat: String(a) + String(b) -> out */
class ConcatNode extends ClassicPreset.Node {
    constructor() {
        super("Concat");
        this.addInput("a", new ClassicPreset.Input(stringSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(stringSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(stringSocket, "out"));
    }
    data(inputs: any) {
        const a = inputs.a?.[0];
        const b = inputs.b?.[0];
        return { out: String(a ?? "") + String(b ?? "") };
    }
}

registerNode({
    type: "Concat",
    title: "Concat",
    category: "logic",
    create: () => new ConcatNode()
});
