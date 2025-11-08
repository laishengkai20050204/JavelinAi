// apps/frontend/src/features/toolflow/core/nodeRegistry.ts
import type {ClassicPreset} from "rete";

// 运行时上下文 & API（用 any 避免循环依赖）
export type RunContext = { vars: Record<string, any>; logs: any[] };
export type RunAPI = {
    editor: any; engine: any; ctx: RunContext;
    readInput: (id: any, port: string) => Promise<any[]>;
    readControl: (node: any, name: string) => any;
    nextBy: (id: any, port: string) => any;
    invalidate: () => Promise<void>;
    setCache: (id: any, v: any) => void;
    getCache: (id: any) => any;
};
export type NodeRuntime = (api: RunAPI, node: any) => Promise<{ next?: any } | void>;


export type ToolNodeCategory =
    | "control"    // Start / End / If / While / Delay ...
    | "variable"   // SetVar / GetVar ...
    | "literal"    // Number / String / Boolean ...
    | "logic"      // Add / Compare / And / Or ...
    | "collection" // JSON / List / Map/Filter ...
    | "io"         // HTTP / ToolCall / Logger / Output ...
    | "other";

export interface ToolNodeDefinition {
    /** 节点类型/label（必须唯一，对应 Node.label） */
    type: string;
    /** 菜单里显示的名字（可以和 type 一样） */
    title: string;
    /** 用于 Palette/菜单分组 */
    category: ToolNodeCategory;
    /** 运行时上下文 */
    runtime?: NodeRuntime;

    /** 实际创建 Rete 节点实例 */
    create(): ClassicPreset.Node;

}

const runtimeRegistry = new Map<string, NodeRuntime>();

/** 内部注册表 */
const registry = new Map<string, ToolNodeDefinition>();

/** 外部调用：注册一个节点定义 */
export function registerNode(def: ToolNodeDefinition) {
    if (registry.has(def.type)) {
        console.warn("[toolflow] duplicate node type:", def.type);
    }
    if (def.runtime) runtimeRegistry.set(def.type, def.runtime);
    registry.set(def.type, def);
}

/** 拿到所有定义（一般用不到，除非你想自己渲染 Palette） */
export function getAllNodeDefs(): ToolNodeDefinition[] {
    return Array.from(registry.values());
}

/** 给 ContextMenu 用的 items: [标题, 工厂函数] */
export function getContextMenuItems(): Array<[string, () => ClassicPreset.Node]> {
    return getAllNodeDefs().map((def) => [
        def.title,
        () => def.create()
    ]);
}

/** 给左侧 Palette 用：按分类分组 */
export function getNodeDefsByCategory(): Map<ToolNodeCategory, ToolNodeDefinition[]> {
    const map = new Map<ToolNodeCategory, ToolNodeDefinition[]>();
    for (const def of registry.values()) {
        if (!map.has(def.category)) map.set(def.category, []);
        map.get(def.category)!.push(def);
    }
    return map;
}

/** 根据 type/label 找工厂，给 importGraph 还原节点用 */
export function resolveNodeFactory(type: string): (() => ClassicPreset.Node) | undefined {
    const def = registry.get(type);
    return def?.create;
}

export function getRuntime(type: string) {
    return runtimeRegistry.get(type);
}