import { ClassicPreset } from "rete";
import { numberSocket, jsonSocket, booleanSocket } from "../basic";
import { registerNode } from "../../core/nodeRegistry";

class CompareNode extends ClassicPreset.Node {
    constructor() {
        super("Compare");
        this.addInput("a", new ClassicPreset.Input(jsonSocket, "a"));
        this.addInput("b", new ClassicPreset.Input(jsonSocket, "b"));
        this.addControl("op", new ClassicPreset.InputControl("text", { initial: "==" })); // "==","!=",">",">=","<","<="
        this.addOutput("out", new ClassicPreset.Output(booleanSocket, "out"));
    }
    data(inputs: any) {
        const a = inputs.a?.[0];
        const b = inputs.b?.[0];
        const opCtrl: any = (this as any).controls?.get?.("op") ?? (this as any).controls?.op;
        const op = String(opCtrl?.value ?? "==");
        let out = false;
        switch (op) {
            case "==": out = a == b; break;
            case "!=": out = a != b; break;
            case ">":  out = Number(a) >  Number(b); break;
            case ">=": out = Number(a) >= Number(b); break;
            case "<":  out = Number(a) <  Number(b); break;
            case "<=": out = Number(a) <= Number(b); break;
            default: out = false;
        }
        return { out: !!out };
    }
}

registerNode({
    type: "Compare",
    title: "Compare (==, >, ...)",
    category: "logic",
    create: () => new CompareNode()
});
