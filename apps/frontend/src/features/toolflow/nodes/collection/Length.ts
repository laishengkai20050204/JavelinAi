import { ClassicPreset } from "rete";
import { jsonSocket, numberSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

class LengthNode extends ClassicPreset.Node {
    constructor() {
        super("Length");
        this.addInput("x", new ClassicPreset.Input(jsonSocket, "x"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: any) {
        const x = inputs.x?.[0];
        let out = 0;
        if (typeof x === "string" || Array.isArray(x)) out = x.length;
        else if (x && typeof x === "object") out = Object.keys(x).length;
        else out = 0;
        return { out };
    }
}

registerNode({
    type: "Length",
    title: "Length",
    category: "collection",
    create: () => new LengthNode()
});
