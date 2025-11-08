import { ClassicPreset } from "rete";
import { jsonSocket, numberSocket, stringSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

class IndexNode extends ClassicPreset.Node {
    constructor() {
        super("Index");
        this.addInput("obj", new ClassicPreset.Input(jsonSocket, "obj/arr"));
        this.addInput("key", new ClassicPreset.Input(jsonSocket, "key/index"));
        this.addOutput("out", new ClassicPreset.Output(jsonSocket, "out"));
    }
    data(inputs: any) {
        const obj = inputs.obj?.[0];
        const key = inputs.key?.[0];
        let out: any = undefined;
        if (Array.isArray(obj)) {
            const i = Number(key);
            out = Number.isFinite(i) ? obj[i] : undefined;
        } else if (obj && typeof obj === "object") {
            out = obj[String(key)];
        } else {
            out = undefined;
        }
        return { out };
    }
}

registerNode({
    type: "Index",
    title: "Index (obj[key])",
    category: "collection",
    create: () => new IndexNode()
});
