import { ClassicPreset } from "rete";
import { booleanSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

/** And: a && b -> out */
class AndNode extends ClassicPreset.Node {
    constructor() {
        super("And");
        this.addInput("a", new ClassicPreset.Input(booleanSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(booleanSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(booleanSocket, "out"));
    }
    data(inputs: any) {
        const a = !!inputs.a?.[0];
        const b = !!inputs.b?.[0];
        return { out: a && b };
    }
}
registerNode({
    type: "And",
    title: "And",
    category: "logic",
    create: () => new AndNode()
});

/** Or: a || b -> out */
class OrNode extends ClassicPreset.Node {
    constructor() {
        super("Or");
        this.addInput("a", new ClassicPreset.Input(booleanSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(booleanSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(booleanSocket, "out"));
    }
    data(inputs: any) {
        const a = !!inputs.a?.[0];
        const b = !!inputs.b?.[0];
        return { out: a || b };
    }
}
registerNode({
    type: "Or",
    title: "Or",
    category: "logic",
    create: () => new OrNode()
});

/** Not: !x -> out */
class NotNode extends ClassicPreset.Node {
    constructor() {
        super("Not");
        this.addInput("x", new ClassicPreset.Input(booleanSocket, "x"));
        this.addOutput("out", new ClassicPreset.Output(booleanSocket, "out"));
    }
    data(inputs: any) {
        const x = !!inputs.x?.[0];
        return { out: !x };
    }
}
registerNode({
    type: "Not",
    title: "Not",
    category: "logic",
    create: () => new NotNode()
});
