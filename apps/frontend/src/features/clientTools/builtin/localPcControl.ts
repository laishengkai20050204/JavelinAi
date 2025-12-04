// src/features/clientTools/builtin/localPcControl.ts
import type { ClientTool } from "../types";

const DEFAULT_PC_AGENT_BASE_URL = "http://127.0.0.1:5001";
const PC_AGENT_API_KEY = ""; // 和 FastAPI 那边对上

// ==== 前端基础工具函数：和 ChatFileUploader 一致的 joinUrl / rewriteToCurrentOrigin ====

const CURRENT_ORIGIN =
    typeof window !== "undefined" && window.location ? window.location.origin : "";
const BACKEND_BASE_URL = CURRENT_ORIGIN || "/";

const joinUrl = (a: string, b: string) =>
    (a.endsWith("/") ? a.slice(0, -1) : a) + b;

function rewriteToCurrentOrigin(url: string): string {
    if (typeof window === "undefined") return url;
    try {
        const u = new URL(url, window.location.origin);
        const origin = window.location.origin;
        let path = u.pathname || "/";

        // ⭐ 如果路径还没有 /minio/ 前缀，就补上
        if (!path.startsWith("/minio/")) {
            if (!path.startsWith("/")) path = "/" + path;
            path = "/minio" + path;
        }

        return origin + path + u.search + u.hash;
    } catch {
        return url;
    }
}

// data:image/png;base64,... → File
function dataUrlToFile(dataUrl: string, filename: string): File {
    const [header, base64] = dataUrl.split(",", 2);
    const mimeMatch = header.match(/data:(.*?);base64/);
    const mime = mimeMatch ? mimeMatch[1] : "application/octet-stream";

    const binary = atob(base64);
    const len = binary.length;
    const arr = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        arr[i] = binary.charCodeAt(i);
    }
    const blob = new Blob([arr], { type: mime });
    return new File([blob], filename, { type: mime });
}

// ==== 调用 PC Agent 的基础封装 ====

async function callPcAgent<TReq, TRes>(
    path: string,
    body: TReq
): Promise<TRes> {
    const resp = await fetch(`${DEFAULT_PC_AGENT_BASE_URL}${path}`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "X-API-Key": PC_AGENT_API_KEY,
        },
        body: JSON.stringify(body),
    });
    if (!resp.ok) {
        const text = await resp.text();
        throw new Error(`PC agent error: ${resp.status} ${resp.statusText}: ${text}`);
    }
    return (await resp.json()) as TRes;
}

async function callPcAgentGet<TRes>(
    path: string,
    searchParams?: Record<string, any>
): Promise<TRes> {
    const url = new URL(`${DEFAULT_PC_AGENT_BASE_URL}${path}`);
    if (searchParams) {
        for (const [k, v] of Object.entries(searchParams)) {
            if (v === undefined || v === null) continue;
            url.searchParams.set(k, String(v));
        }
    }

    const resp = await fetch(url.toString(), {
        method: "GET",
        headers: {
            "X-API-Key": PC_AGENT_API_KEY,
        },
    });
    if (!resp.ok) {
        const text = await resp.text();
        throw new Error(`PC agent GET error: ${resp.status} ${resp.statusText}: ${text}`);
    }
    return (await resp.json()) as TRes;
}

type LocalPcAction =
    | "get_screen"
    | "screenshot"
    | "move_mouse"
    | "click"
    | "scroll"
    | "press_key"
    | "write_text"
    // ==== 新增：PID / 进程 / 应用相关 ====
    | "list_processes"
    | "get_process"
    | "screenshot_process"
    | "focus_process"
    | "terminate_process"
    | "list_apps"
    | "launch_app"
    | "list_managed";

export const localPcControlTool: ClientTool = {
    manifest: {
        name: "local_pc_control",
        description:
            "Control local PC via local agent: screen info, screenshot (and upload), mouse/keyboard, and PID-based process/app operations.",
        "x-execTarget": "client",
        parameters: {
            type: "object",
            properties: {
                action: {
                    type: "string",
                    description: "The type of operation to perform",
                    enum: [
                        "get_screen",
                        "screenshot",
                        "move_mouse",
                        "click",
                        "scroll",
                        "press_key",
                        "write_text",
                        // 新增
                        "list_processes",
                        "get_process",
                        "screenshot_process",
                        "focus_process",
                        "terminate_process",
                        "list_apps",
                        "launch_app",
                        "list_managed",
                    ],
                },
                params: {
                    description: "Parameters for the given action",
                    oneOf: [
                        // === get_screen ===
                        {
                            type: "object",
                            properties: {},
                        },
                        // === screenshot ===
                        {
                            type: "object",
                            properties: {},
                        },
                        // === move_mouse ===
                        {
                            type: "object",
                            properties: {
                                x: {
                                    type: "number",
                                    description: "Target X coordinate (pixels)",
                                },
                                y: {
                                    type: "number",
                                    description: "Target Y coordinate (pixels)",
                                },
                                duration: {
                                    type: "number",
                                    description: "Move duration in seconds",
                                    default: 0,
                                },
                            },
                            required: ["x", "y"],
                        },
                        // === click ===
                        {
                            type: "object",
                            properties: {
                                button: {
                                    type: "string",
                                    enum: ["left", "right", "middle"],
                                    default: "left",
                                },
                                clicks: {
                                    type: "number",
                                    default: 1,
                                },
                                interval: {
                                    type: "number",
                                    default: 0.1,
                                },
                            },
                            required: [],
                        },
                        // === scroll ===
                        {
                            type: "object",
                            properties: {
                                amount: {
                                    type: "number",
                                    description: "Positive for up, negative for down",
                                },
                            },
                            required: ["amount"],
                        },
                        // === press_key ===
                        {
                            type: "object",
                            properties: {
                                key: {
                                    type: "string",
                                    description:
                                        "Key name, e.g. 'enter', 'esc', 'a', 'ctrl'",
                                },
                                presses: {
                                    type: "number",
                                    default: 1,
                                },
                                interval: {
                                    type: "number",
                                    default: 0.05,
                                },
                            },
                            required: ["key"],
                        },
                        // === write_text ===
                        {
                            type: "object",
                            properties: {
                                text: {
                                    type: "string",
                                    description: "Text to type",
                                },
                                interval: {
                                    type: "number",
                                    default: 0.02,
                                },
                            },
                            required: ["text"],
                        },
                        // === list_processes ===
                        {
                            type: "object",
                            properties: {
                                limit: {
                                    type: "number",
                                    description: "Max number of processes to return (1-1000)",
                                },
                                keyword: {
                                    type: "string",
                                    description: "Filter by name/exe/cmdline substring",
                                },
                                only_main: {
                                    type: "boolean",
                                    description: "Only return inferred main processes",
                                },
                                only_managed: {
                                    type: "boolean",
                                    description: "Only return agent-managed processes",
                                },
                            },
                            required: [],
                        },
                        // === get_process ===
                        {
                            type: "object",
                            properties: {
                                pid: {
                                    type: "number",
                                    description: "Process PID",
                                },
                            },
                            required: ["pid"],
                        },
                        // === screenshot_process ===
                        {
                            type: "object",
                            properties: {
                                pid: {
                                    type: "number",
                                    description: "Process PID whose window to capture",
                                },
                                bring_to_front: {
                                    type: "boolean",
                                    description: "Whether to bring window to front first",
                                    default: true,
                                },
                            },
                            required: ["pid"],
                        },
                        // === focus_process ===
                        {
                            type: "object",
                            properties: {
                                pid: {
                                    type: "number",
                                    description: "Process PID",
                                },
                            },
                            required: ["pid"],
                        },
                        // === terminate_process ===
                        {
                            type: "object",
                            properties: {
                                pid: {
                                    type: "number",
                                    description: "Process PID to terminate",
                                },
                                force: {
                                    type: "boolean",
                                    description: "If true, use kill() instead of terminate()",
                                    default: false,
                                },
                            },
                            required: ["pid"],
                        },
                        // === list_apps ===
                        {
                            type: "object",
                            properties: {},
                        },
                        // === launch_app ===
                        {
                            type: "object",
                            properties: {
                                app_id: {
                                    type: "string",
                                    description: "App ID to launch",
                                },
                            },
                            required: ["app_id"],
                        },
                        // === list_managed ===
                        {
                            type: "object",
                            properties: {},
                        },
                    ],
                },
            },
            required: ["action", "params"],
        },
        "x-returns": {
            type: "object",
            properties: {
                status: { type: "string" },
                x: { type: "number" },
                y: { type: "number" },
            },
        },
    },

    async execute(args, ctx) {
        const action = args.action as LocalPcAction;
        const params = args.params ?? {};

        // 一个小工具方法：把 screenshot 响应上传到 MinIO 并返回 payload
        const uploadScreenshot = async (
            data: { width: number; height: number; dataUrl: string },
            filenamePrefix: string,
            extraNote?: string,
            extraFields?: Record<string, any>,
        ) => {
            const ts = new Date().toISOString().replace(/[:.]/g, "-");
            const filename = `${filenamePrefix}-${ts}.png`;
            const file = dataUrlToFile(data.dataUrl, filename);

            const userId = (ctx.userId || "u1").trim() || "u1";
            const conversationId = ctx.conversationId || "unknown";

            const fd = new FormData();
            fd.append("file", file);
            fd.append("userId", userId);
            fd.append("conversationId", conversationId);

            const uploadRes = await fetch(
                joinUrl(BACKEND_BASE_URL, "/files/upload"),
                {
                    method: "POST",
                    body: fd,
                },
            );
            if (!uploadRes.ok) {
                let msg = `HTTP ${uploadRes.status}`;
                try {
                    const t = await uploadRes.text();
                    if (t) msg += ` - ${t}`;
                } catch {
                    // ignore
                }
                throw new Error(`upload screenshot failed: ${msg}`);
            }

            const uploadData = (await uploadRes.json()) as {
                bucket: string;
                objectKey: string;
                url: string;
                size: number;
                contentType?: string | null;
                fileId?: number;
                id?: number;
            };

            const normalizedUrl = rewriteToCurrentOrigin(uploadData.url);

            console.log("[local_pc_control] screenshot uploaded", {
                uploadData,
                normalizedUrl,
                userId,
                conversationId,
            });

            return {
                payload: {
                    type: "image_file",
                    value: {
                        width: data.width,
                        height: data.height,
                        bucket: uploadData.bucket,
                        objectKey: uploadData.objectKey,
                        url: normalizedUrl,
                        size: uploadData.size,
                        contentType: uploadData.contentType ?? null,
                        fileId: uploadData.fileId ?? uploadData.id ?? null,
                        filename,
                        note:
                            extraNote ??
                            "screenshot captured via local_pc_control and uploaded via /files/upload",
                        ...(extraFields || {}),
                    },
                },
            };
        };

        switch (action) {
            // ===== 基础信息 =====
            case "get_screen": {
                const resp = await fetch(`${DEFAULT_PC_AGENT_BASE_URL}/screen`, {
                    method: "GET",
                    headers: {
                        "X-API-Key": PC_AGENT_API_KEY,
                    },
                });
                if (!resp.ok) {
                    const text = await resp.text();
                    throw new Error(
                        `PC agent /screen error: ${resp.status} ${resp.statusText}: ${text}`,
                    );
                }
                return await resp.json(); // { width, height }
            }

            // ===== 全屏截图并上传 =====
            case "screenshot": {
                const resp = await fetch(`${DEFAULT_PC_AGENT_BASE_URL}/screenshot`, {
                    method: "GET",
                    headers: {
                        "X-API-Key": PC_AGENT_API_KEY,
                    },
                });
                if (!resp.ok) {
                    const text = await resp.text();
                    throw new Error(
                        `PC agent /screenshot error: ${resp.status} ${resp.statusText}: ${text}`,
                    );
                }
                const data = (await resp.json()) as {
                    width: number;
                    height: number;
                    dataUrl: string;
                };

                return await uploadScreenshot(
                    data,
                    "screenshot",
                    "full-screen screenshot captured via local_pc_control and uploaded via /files/upload",
                );
            }

            // ===== 鼠标控制 =====
            case "move_mouse": {
                const { x, y, duration = 0 } = params;
                return await callPcAgent("/mouse/move", { x, y, duration });
            }

            case "click": {
                const { button = "left", clicks = 1, interval = 0.1 } = params;
                return await callPcAgent("/mouse/click", {
                    button,
                    clicks,
                    interval,
                });
            }

            case "scroll": {
                const { amount } = params;
                return await callPcAgent("/mouse/scroll", { amount });
            }

            // ===== 键盘控制 =====
            case "press_key": {
                const { key, presses = 1, interval = 0.05 } = params;
                return await callPcAgent("/keyboard/press", {
                    key,
                    presses,
                    interval,
                });
            }

            case "write_text": {
                const { text, interval = 0.02 } = params;
                return await callPcAgent("/keyboard/write", {
                    text,
                    interval,
                });
            }

            // ===== 进程列表 / 查询 =====
            case "list_processes": {
                const { limit, keyword, only_main, only_managed } = params;
                return await callPcAgentGet("/processes", {
                    limit,
                    keyword,
                    only_main,
                    only_managed,
                });
            }

            case "get_process": {
                const { pid } = params;
                if (typeof pid !== "number") {
                    throw new Error("get_process requires numeric pid");
                }
                return await callPcAgentGet(`/process/${pid}`);
            }

            // ===== 指定 PID 的窗口截图并上传 =====
            case "screenshot_process": {
                const { pid, bring_to_front = true } = params;
                if (typeof pid !== "number") {
                    throw new Error("screenshot_process requires numeric pid");
                }

                const url = new URL(
                    `${DEFAULT_PC_AGENT_BASE_URL}/process/${pid}/screenshot`,
                );
                url.searchParams.set("bring_to_front", String(bring_to_front));

                const resp = await fetch(url.toString(), {
                    method: "GET",
                    headers: {
                        "X-API-Key": PC_AGENT_API_KEY,
                    },
                });
                if (!resp.ok) {
                    const text = await resp.text();
                    throw new Error(
                        `PC agent /process/${pid}/screenshot error: ${resp.status} ${resp.statusText}: ${text}`,
                    );
                }

                const data = (await resp.json()) as {
                    pid: number;
                    windowCropping: boolean;
                    width: number;
                    height: number;
                    dataUrl: string;
                };

                return await uploadScreenshot(
                    data,
                    `process-${pid}`,
                    "window screenshot captured via local_pc_control (PID-based) and uploaded via /files/upload",
                    {
                        pid: data.pid,
                        windowCropping: data.windowCropping,
                    },
                );
            }

            // ===== 前台焦点 / 终止进程 =====
            case "focus_process": {
                const { pid } = params;
                if (typeof pid !== "number") {
                    throw new Error("focus_process requires numeric pid");
                }
                return await callPcAgent(`/process/${pid}/focus`, {});
            }

            case "terminate_process": {
                const { pid, force = false } = params;
                if (typeof pid !== "number") {
                    throw new Error("terminate_process requires numeric pid");
                }
                const path = `/process/${pid}/terminate?force=${force ? "true" : "false"}`;
                return await callPcAgent(path, {});
            }

            // ===== 应用目录 / 启动 / 管理的进程 =====
            case "list_apps": {
                return await callPcAgentGet("/apps");
            }

            case "launch_app": {
                const { app_id } = params;
                if (typeof app_id !== "string" || !app_id) {
                    throw new Error("launch_app requires app_id");
                }
                return await callPcAgent(`/apps/${encodeURIComponent(app_id)}/launch`, {});
            }

            case "list_managed": {
                return await callPcAgentGet("/managed");
            }

            default:
                throw new Error(`Unknown local_pc_control action: ${String(action)}`);
        }
    },
};
