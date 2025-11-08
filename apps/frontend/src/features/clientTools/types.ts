// src/features/clientTools/types.ts

export type ClientToolManifestFn = {
    name: string;
    description: string;
    parameters: any;           // JSON Schema Draft-7 子集
    "x-execTarget": "client";  // 固定 client
    "x-returns"?: any;         // 建议声明返回结构
};

export type ClientTool = {
    manifest: ClientToolManifestFn;  // 仅 function 内部结构
    execute: (
        args: Record<string, any>,
        ctx: { userId: string; conversationId: string }
    ) => Promise<any>;
};

export type SavedToolBundle = {
    id: string; // uuid
    meta: {
        name: string;
        displayName?: string;
        description: string;
        version?: string;
        createdAt: number;
        updatedAt: number;
    };
    graph: any;                      // GraphJSON
    manifest: ClientToolManifestFn;  // 编译产物
};
