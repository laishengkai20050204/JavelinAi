// src/features/clientTools/builtin/localPcControl.ts
import type { ClientTool } from "../types";

const DEFAULT_PC_AGENT_BASE_URL = "http://127.0.0.1:5001";
const PC_AGENT_API_KEY = "change-me-please"; // 和 FastAPI 那边对上

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

        // ⭐ 关键：如果路径还没有 /minio/ 前缀，就补上
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

type LocalPcAction =
    | "get_screen"
    | "screenshot"
    | "move_mouse"
    | "click"
    | "scroll"
    | "press_key"
    | "write_text";

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

export const localPcControlTool: ClientTool = {
    manifest: {
        name: "local_pc_control", // 工具名，用这个给后端暴露
        description:
            "Control local PC: get screen size, screenshot (and upload), move mouse, click, scroll, press keys, write text.",
        "x-execTarget": "client",
        // 给 LLM 的参数 JSON Schema
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
                    ],
                },
                params: {
                    description: "Parameters for the given action",
                    oneOf: [
                        {
                            // ✅ get_screen: 不需要任何参数
                            type: "object",
                            properties: {},
                        },
                        {
                            // screenshot: 也不需要参数（后面可以扩展区域截图）
                            type: "object",
                            properties: {},
                        },
                        {
                            // move_mouse
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
                        {
                            // click
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
                        {
                            // scroll
                            type: "object",
                            properties: {
                                amount: {
                                    type: "number",
                                    description: "Positive for up, negative for down",
                                },
                            },
                            required: ["amount"],
                        },
                        {
                            // press_key
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
                        {
                            // write_text
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
                    ],
                },
            },
            required: ["action", "params"],
        },
        // 返回结构大概：status/x/y 或 screenshot 的 file 信息
        "x-returns": {
            type: "object",
            properties: {
                status: { type: "string" },
                x: { type: "number" },
                y: { type: "number" },
            },
        },
    },

    // 真正执行逻辑（在浏览器里跑）
    async execute(args, ctx) {
        const action = args.action as LocalPcAction;
        const params = args.params ?? {};

        switch (action) {
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
                // 返回 { width, height }
                return await resp.json();
            }

            case "screenshot": {
                // 1) 先向 PC agent 要一张截图（dataUrl）
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

                // 2) 把 dataUrl 转成 File
                const ts = new Date().toISOString().replace(/[:.]/g, "-");
                const filename = `screenshot-${ts}.png`;
                const file = dataUrlToFile(data.dataUrl, filename);

                // 3) 走你现有的 /files/upload 接口上传到 MinIO
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

                // 4) 返回给 LLM：告诉它“截图已上传”，并带上 URL + 元信息
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
                            note: "screenshot captured via local_pc_control and uploaded via /files/upload",
                        },
                    },
                };
            }

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

            default:
                throw new Error(`Unknown local_pc_control action: ${String(action)}`);
        }
    },
};
