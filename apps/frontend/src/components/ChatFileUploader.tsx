// src/components/ChatFileUploader.tsx
import React, { useState } from "react";
import { Upload, Loader2 } from "lucide-react";

export interface UploadFileResponse {
    fileId?: number;           // 后端如果返回 fileId
    id?: number;               // 兼容只返回 id 的情况
    bucket: string;
    objectKey: string;
    url: string;
    size: number;
    contentType?: string | null;
}

export interface ChatFileUploaderProps {
    /** 后端基础地址，例如 "/" 或 "http://localhost:8080" */
    baseUrl?: string;
    /** 当前 userId（必传） */
    userId: string;
    /** 当前会话 ID（必传，用来跟后端关联 conversationId） */
    conversationId?: string;
    /** 上传成功回调 */
    onUploaded?: (resp: UploadFileResponse, file: File) => void;
    /** 上传失败回调 */
    onError?: (err: Error) => void;
    /** 额外的 className，方便外层加 margin */
    className?: string;
}

// 把任意 url 的前缀改成当前页面的域名（origin）
function rewriteToCurrentOrigin(url: string): string {
    if (typeof window === "undefined") return url;
    try {
        const u = new URL(url, window.location.origin);
        const origin = window.location.origin;
        // 保留路径 + query + hash，只替换 origin
        return origin + u.pathname + u.search + u.hash;
    } catch {
        // 解析失败（比如本来就是相对路径）就原样返回
        return url;
    }
}

const CURRENT_ORIGIN =
    typeof window !== "undefined" && window.location ? window.location.origin : "";

const DEFAULT_BASE_URL = CURRENT_ORIGIN || "/";

const joinUrl = (a: string, b: string) =>
    (a.endsWith("/") ? a.slice(0, -1) : a) + b;

export const ChatFileUploader: React.FC<ChatFileUploaderProps> = ({
                                                                      baseUrl = DEFAULT_BASE_URL,
                                                                      userId,
                                                                      conversationId,
                                                                      onUploaded,
                                                                      onError,
                                                                      className = "",
                                                                  }) => {
    const [selectedFile, setSelectedFile] = useState<File | null>(null);
    const [uploading, setUploading] = useState(false);
    const [errorText, setErrorText] = useState<string | null>(null);

    const canUpload = !!selectedFile && !!conversationId && !uploading;

    async function handleUpload() {
        if (!selectedFile || !conversationId || uploading) return;

        setUploading(true);
        setErrorText(null);

        try {
            const fd = new FormData();
            fd.append("file", selectedFile);
            fd.append("userId", (userId || "u1").trim() || "u1");
            fd.append("conversationId", conversationId);

            const res = await fetch(joinUrl(baseUrl, "/files/upload"), {
                method: "POST",
                body: fd,
            });

            if (!res.ok) {
                let msg = `HTTP ${res.status}`;
                try {
                    const t = await res.text();
                    if (t) msg += ` - ${t}`;
                } catch {
                    // ignore
                }
                throw new Error(msg);
            }

            const data = (await res.json()) as UploadFileResponse;

            // ⭐ 在这里把 url 的前缀改成当前域名
            const normalized: UploadFileResponse = {
                ...data,
                url: rewriteToCurrentOrigin(data.url),
            };

            onUploaded?.(normalized, selectedFile);
            setSelectedFile(null);
        } catch (e: any) {
            const err = e instanceof Error ? e : new Error(String(e));
            setErrorText(err.message);
            onError?.(err);
        } finally {
            setUploading(false);
        }
    }

    return (
        <div className={`flex flex-wrap items-center gap-2 ${className}`}>
            <input
                type="file"
                onChange={(e) => {
                    const f = e.target.files?.[0] ?? null;
                    setSelectedFile(f);
                    setErrorText(null);
                }}
                className="text-xs"
            />
            <button
                type="button"
                onClick={handleUpload}
                disabled={!canUpload}
                className="inline-flex items-center gap-1.5 rounded-2xl px-3 py-1.5 text-xs border border-slate-300/70 dark:border-slate-700 disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-800"
            >
                {uploading ? (
                    <>
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        正在上传…
                    </>
                ) : (
                    <>
                        <Upload className="h-3.5 w-3.5" />
                        上传文件
                    </>
                )}
            </button>

            {selectedFile && !uploading && (
                <span className="text-[11px] text-slate-500 dark:text-slate-400 max-w-[12rem] truncate">
          已选择：{selectedFile.name}
        </span>
            )}

            {!conversationId && (
                <span className="text-[11px] text-amber-600 dark:text-amber-400">
          请先创建会话再上传文件
        </span>
            )}

            {errorText && (
                <div className="basis-full text-[11px] text-red-600 dark:text-red-400 mt-1">
                    上传失败：{errorText}
                </div>
            )}
        </div>
    );
};
