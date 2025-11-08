// 纯 JSON（可随请求发送）
export type ClientToolManifest = {
    name: string;                    // 唯一名，建议 slug
    displayName?: string;            // UI 名
    description: string;
    mode: "client";                  // 明确是前端工具
    parameters: any;                 // JSONSchema Draft 7 子集
    returns: any;                    // JSONSchema，约定 { type:"object", properties:{ result:{...} } }
    version?: string;                // 可选
};

// 前端可执行注册条目（不会发给服务器）
export type ClientTool = {
    manifest: ClientToolManifest;
    execute: (args: Record<string, any>, ctx: { userId: string; conversationId: string }) => Promise<any>;
};

// 存到本地的“工具包”（含图）
export type SavedToolBundle = {
    id: string;                      // uuid
    meta: {
        name: string;
        displayName?: string;
        description: string;
        version?: string;
        createdAt: number;
        updatedAt: number;
    };
    graph: any;                      // 你的 GraphJSON
    manifest: ClientToolManifest;    // 编译时产物（可随图一起存）
};
