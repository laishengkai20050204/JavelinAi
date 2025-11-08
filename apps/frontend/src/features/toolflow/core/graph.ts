// apps/frontend/src/features/toolflow/core/graph.ts
import type { NodeEditor } from "rete";
import type { GetSchemes } from "rete";

export type GraphJSON = {
    version: number;
    nodes: Array<{
        id: string | number;
        type: string; // 节点 label
        position: { x: number; y: number };
        controls: Record<string, unknown>;
    }>;
    connections: Array<{
        from: { id: string | number; port: string };
        to: { id: string | number; port: string };
    }>;
};

// 清理“悬空的”连线（端点不存在）
export function cleanupDanglingConnections<S extends GetSchemes<any, any>>(editor: NodeEditor<S>) {
    const nodeIds = new Set(editor.getNodes().map((n) => n.id as any));
    for (const c of [...editor.getConnections()]) {
        const ok = nodeIds.has(c.source as any) && nodeIds.has(c.target as any);
        if (!ok) {
            try { (editor as any).removeConnection?.(c); }
            catch { try { (editor as any).removeConnection?.((c as any).id); } catch {} }
        }
    }
}
