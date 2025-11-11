// apps/frontend/src/features/toolflow/nodes/ops/arithmetic.ts
import { ClassicPreset } from "rete";
import { registerNode } from "../../core/nodeRegistry";
import { numberSocket } from "../basic";

// 安全转 number
const num = (v: any, d = 0) => {
    const n = typeof v === "number" ? v : Number(v);
    return Number.isFinite(n) ? n : d;
};

/** Add(a,b) -> out */
class AddNode extends ClassicPreset.Node {
    static type = "Add";
    constructor() {
        super("Add"); // label=Add —— 与 type 一致
        this.addInput("a", new ClassicPreset.Input(numberSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(numberSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: any) {
        const a = num(inputs?.a?.[0]);
        const b = num(inputs?.b?.[0]);
        return { out: a + b };
    }
}
registerNode({
    type: AddNode.type,
    title: "Add",
    category: "logic",
    create: () => new AddNode(),
});

/** Sub(a,b) -> out */
class SubNode extends ClassicPreset.Node {
    static type = "Sub";
    constructor() {
        super("Sub");
        this.addInput("a", new ClassicPreset.Input(numberSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(numberSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: any) {
        const a = num(inputs?.a?.[0]);
        const b = num(inputs?.b?.[0]);
        return { out: a - b };
    }
}
registerNode({
    type: SubNode.type,
    title: "Sub",
    category: "logic",
    create: () => new SubNode(),
});

/** Mul(a,b) -> out */
class MulNode extends ClassicPreset.Node {
    static type = "Mul";
    constructor() {
        super("Mul");
        this.addInput("a", new ClassicPreset.Input(numberSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(numberSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: any) {
        const a = num(inputs?.a?.[0]);
        const b = num(inputs?.b?.[0]);
        return { out: a * b };
    }
}
registerNode({
    type: MulNode.type,
    title: "Mul",
    category: "logic",
    create: () => new MulNode(),
});

/** Div(a,b) -> out (b=0 返回 Infinity) */
class DivNode extends ClassicPreset.Node {
    static type = "Div";
    constructor() {
        super("Div");
        this.addInput("a", new ClassicPreset.Input(numberSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(numberSocket, "b"));
        this.addOutput("out", new ClassicPreset.Output(numberSocket, "out"));
    }
    data(inputs: any) {
        const a = num(inputs?.a?.[0]);
        const b = num(inputs?.b?.[0]);
        return { out: b === 0 ? Infinity : a / b };
    }
}
registerNode({
    type: DivNode.type,
    title: "Div",
    category: "logic",
    create: () => new DivNode(),
});
