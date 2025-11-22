// src/pages/JavelinMinimalChat.tsx
import React from "react";
import { motion } from "framer-motion";
import {
    Send,
    Sparkles,
    Loader2,
    Bug,
    Menu,
    Plus,
    Pencil,
    Search,
    Trash2,
} from "lucide-react";
import { ChatFileUploader } from "../components/ChatFileUploader";
import { useJavelinMinimalChat } from "./JavelinMinimalChat.hooks";
import {
    ChatBubble,
    EmptyState,
    ConfirmDialog,
    RenameDialog,
} from "./JavelinMinimalChat.ui";

export default function JavelinMinimalChat() {
    const {
        conversations,
        activeId,
        convMessages,
        sidebarOpen,
        setSidebarOpen,
        query,
        setQuery,
        busy,
        showDebug,
        setShowDebug,
        input,
        setInput,
        confirmDelId,
        setConfirmDelId,
        renameId,
        setRenameId,
        userId,
        setUserId,
        toolPanelOpen,
        setToolPanelOpen,
        exposed,
        setExposed,
        pendingLinks,
        setPendingLinks,
        clientTools,
        remoteConversations, // 目前没直接用，但保留
        remoteLoading,
        remoteError,
        remoteImporting,
        listRef,
        messages,
        displayMessages,
        ndjsonEvents,
        sseEvents,
        filteredConversations,
        setActiveId,
        handleSend,
        handleKey,
        handleStop,
        refreshRemoteHistory,
        importHistoryConversation,
        createConversation,
        requestRename,
        doRename,
        requestDelete,
        doDelete,
        handleFileUploaded,
        handleUploadError,
        openOne,
        baseUrl,
        ndjsonPath,
        useDemo,
        setEvents,
    } = useJavelinMinimalChat();

    const apiUrl =
        baseUrl && baseUrl !== "/"
            ? (baseUrl.endsWith("/") ? baseUrl.slice(0, -1) : baseUrl) + ndjsonPath
            : ndjsonPath;

    return (
        <div className="h-full w-full bg-white text-slate-900">
            <div className="flex h-full">
                {/* 左侧：会话栏 */}
                <aside
                    className={`flex h-full flex-col border-r border-slate-200 bg-slate-50 transition-[width] duration-200 ${
                        sidebarOpen ? "w-64" : "w-0 sm:w-64"
                    }`}
                >
                    <div
                        className={`flex h-full flex-col overflow-hidden ${
                            sidebarOpen
                                ? "opacity-100"
                                : "opacity-0 pointer-events-none sm:opacity-100 sm:pointer-events-auto"
                        }`}
                    >
                        {/* 顶部：标题 + 新建按钮 */}
                        <div className="flex items-center justify-between px-3 pt-3 pb-2">
                            <div className="flex items-center gap-2">
                                <div className="flex h-7 w-7 items-center justify-center rounded-xl bg-slate-900 text-white">
                                    <Sparkles className="h-3.5 w-3.5" />
                                </div>
                                <div>
                                    <div className="text-xs font-semibold">会话</div>
                                    <div className="text-[11px] text-slate-500">
                                        共 {conversations.length || 0} 个
                                    </div>
                                    <button
                                        type="button"
                                        onClick={refreshRemoteHistory}
                                        disabled={remoteLoading}
                                        className="mt-1 text-[10px] text-slate-500 underline-offset-2 hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                                    >
                                        {remoteLoading ? "同步历史中…" : "同步历史"}
                                    </button>
                                </div>
                            </div>
                            <button
                                onClick={createConversation}
                                className="inline-flex items-center justify-center rounded-xl border border-slate-300 bg-white px-2 py-1 text-[11px] hover:bg-slate-100"
                            >
                                <Plus className="h-3 w-3" />
                                <span className="ml-1">新建</span>
                            </button>
                        </div>

                        {/* 搜索框 */}
                        <div className="relative px-3 pb-2">
                            <Search className="pointer-events-none absolute left-5 top-2.5 h-3.5 w-3.5 text-slate-400" />
                            <input
                                value={query}
                                onChange={(e) => setQuery(e.target.value)}
                                placeholder="搜索会话…"
                                className="w-full rounded-2xl border border-slate-200 bg-white pl-8 pr-3 py-1.5 text-[13px] outline-none placeholder:text-slate-400 focus:border-slate-400"
                            />
                            {remoteError && (
                                <div className="mt-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-1.5 text-[11px] text-rose-600">
                                    {remoteError}
                                </div>
                            )}
                        </div>

                        {/* 工具开关 */}
                        <div className="px-3 pb-2">
                            <div className="rounded-2xl border border-slate-200 bg-white">
                                <button
                                    onClick={() => setToolPanelOpen((v) => !v)}
                                    className="flex w-full items-center justify-between px-3 py-2 text-[11px] font-medium"
                                >
                                    <span>
                                        客户端工具：已启用{" "}
                                        {
                                            Object.values(exposed).filter(Boolean)
                                                .length
                                        }
                                        /{clientTools.length}
                                    </span>
                                    <span className="text-[10px] text-slate-500">
                                        {toolPanelOpen ? "收起" : "展开"}
                                    </span>
                                </button>
                                {toolPanelOpen && (
                                    <div className="max-h-40 space-y-1 overflow-auto px-2 pb-2">
                                        {clientTools.length === 0 && (
                                            <div className="px-2 py-3 text-[11px] text-slate-500">
                                                暂无客户端工具（去 Tool Builder 新建）
                                            </div>
                                        )}
                                        {clientTools.map((t) => {
                                            const name =
                                                t.manifest?.name || "(unnamed)";
                                            const desc =
                                                t.manifest?.description || "";
                                            const on = !!exposed[name];
                                            return (
                                                <label
                                                    key={name}
                                                    className="flex items-center gap-2 rounded-xl px-2 py-1 hover:bg-slate-50"
                                                >
                                                    <input
                                                        type="checkbox"
                                                        checked={on}
                                                        onChange={(e) => {
                                                            const v =
                                                                e.target.checked;
                                                            setExposed(
                                                                (
                                                                    prev: Record<
                                                                        string,
                                                                        boolean
                                                                    >,
                                                                ) => {
                                                                    const next =
                                                                        {
                                                                            ...prev,
                                                                            [name]: v,
                                                                        };
                                                                    try {
                                                                        localStorage.setItem(
                                                                            "javelin.chat.v3.tools.selection",
                                                                            JSON.stringify(
                                                                                next,
                                                                            ),
                                                                        );
                                                                    } catch {
                                                                        // ignore
                                                                    }
                                                                    return next;
                                                                },
                                                            );
                                                        }}
                                                    />
                                                    <div className="min-w-0">
                                                        <div className="truncate text-xs font-medium">
                                                            {name}
                                                        </div>
                                                        {desc && (
                                                            <div className="truncate text-[11px] text-slate-500">
                                                                {desc}
                                                            </div>
                                                        )}
                                                    </div>
                                                </label>
                                            );
                                        })}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* 会话列表 */}
                        <div className="flex-1 overflow-y-auto px-3 pb-3">
                            {filteredConversations.length === 0 && (
                                <div className="py-6 text-center text-xs text-slate-500">
                                    没有匹配的会话
                                </div>
                            )}
                            <div className="space-y-1">
                                {filteredConversations.map((c) => {
                                    const isActive = c.id === activeId;
                                    const isRemote = !!c.fromRemote;
                                    const isImporting =
                                        isRemote && remoteImporting === c.id;
                                    const handleSelect = () => {
                                        if (isRemote) {
                                            if (!isImporting) {
                                                void importHistoryConversation(
                                                    c.id,
                                                );
                                            }
                                            return;
                                        }
                                        setActiveId(c.id);
                                        setEvents([]);
                                        setInput("");
                                    };
                                    const lastMsg =
                                        convMessages[c.id]?.[
                                        convMessages[c.id].length - 1
                                            ];

                                    return (
                                        <div
                                            key={c.id}
                                            role="button"
                                            tabIndex={0}
                                            onClick={handleSelect}
                                            onKeyDown={(e) => {
                                                if (
                                                    e.key === "Enter" ||
                                                    e.key === " "
                                                ) {
                                                    e.preventDefault();
                                                    handleSelect();
                                                }
                                            }}
                                            className={`w-full rounded-2xl border px-3 py-2 text-left text-sm transition ${
                                                isRemote
                                                    ? "border-slate-300 bg-white hover:border-slate-400 hover:bg-slate-50 text-slate-900"
                                                    : isActive
                                                        ? "border-slate-900 bg-slate-900 text-white"
                                                        : "border-transparent bg-transparent hover:bg-slate-100"
                                            } ${
                                                isImporting
                                                    ? "cursor-wait opacity-60"
                                                    : ""
                                            }`}
                                        >
                                            <div className="flex items-center gap-2">
                                                <div className="flex-1 min-w-0 truncate text-xs font-medium">
                                                    {c.title || "新会话"}
                                                </div>
                                                {!isRemote && (
                                                    <div className="flex items-center gap-1 text-[10px] text-slate-400">
                                                        <button
                                                            type="button"
                                                            onPointerDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onMouseDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                requestRename(
                                                                    c.id,
                                                                );
                                                            }}
                                                            className="rounded p-0.5 hover:bg-slate-100"
                                                            title="重命名"
                                                        >
                                                            <Pencil className="h-3 w-3" />
                                                        </button>
                                                        <button
                                                            type="button"
                                                            onPointerDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onMouseDown={(
                                                                e,
                                                            ) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                            }}
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                requestDelete(
                                                                    c.id,
                                                                );
                                                            }}
                                                            className="rounded p-0.5 hover:bg-slate-100"
                                                            title="删除"
                                                        >
                                                            <Trash2 className="h-3 w-3" />
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                            <div className="mt-0.5 text-[11px] text-slate-500 line-clamp-2">
                                                {lastMsg?.content ?? "(空)"}
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>

                        {/* 左下角 userId 输入 */}
                        <div className="border-t border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-600">
                            <div className="mb-1 text-[10px] uppercase tracking-wide text-slate-400">
                                用户标识（userId）
                            </div>
                            <div className="flex items-center gap-2">
                                <input
                                    id="userIdInput"
                                    value={userId}
                                    onChange={(e) => setUserId(e.target.value)}
                                    placeholder="u1"
                                    className="h-7 flex-1 rounded-2xl border border-slate-300 bg-white px-3 text-xs outline-none placeholder:text-slate-400 focus:border-slate-500"
                                />
                            </div>
                        </div>
                    </div>
                </aside>

                {/* 右侧主区域 */}
                <div className="flex h-full flex-1 flex-col">
                    {/* 顶部栏 */}
                    <header className="flex h-12 flex-none items-center justify-between border-b border-slate-200 bg-white px-3 sm:px-6">
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => setSidebarOpen((v) => !v)}
                                className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-slate-300 bg-white hover:bg-slate-100 sm:hidden"
                                title="切换会话栏"
                            >
                                <Menu className="h-4 w-4" />
                            </button>
                            <div className="text-sm font-medium">Javelin · Chat</div>
                            <span className="hidden text-xs text-slate-500 sm:inline">
                                {useDemo
                                    ? "Demo 模式"
                                    : "已连接后端 · SSE + NDJSON"}
                            </span>
                        </div>
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => setShowDebug((v) => !v)}
                                className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-2.5 py-1 text-[11px] hover:bg-slate-100"
                            >
                                <Bug className="h-3.5 w-3.5" />
                                {showDebug ? "隐藏事件" : "事件"}
                            </button>
                            {busy ? (
                                <button
                                    onClick={handleStop}
                                    className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-3 py-1.5 text-[11px] hover:bg-slate-100"
                                >
                                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                    停止
                                </button>
                            ) : (
                                <button
                                    onClick={createConversation}
                                    className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-3 py-1.5 text-[11px] hover:bg-slate-100"
                                >
                                    <Plus className="h-3.5 w-3.5" />
                                    新对话
                                </button>
                            )}
                        </div>
                    </header>

                    {/* 中间：消息 + 调试 + 输入区 */}
                    <main className="flex flex-1 justify-center bg-white overflow-hidden">
                        <div className="flex h-full w-full max-w-3xl flex-col px-3 sm:px-6">
                            {/* 消息 & 调试 */}
                            <div className="flex-1 min-h-0 flex flex-col">
                                {/* 消息列表 */}
                                <div
                                    ref={listRef}
                                    className="flex-1 min-h-0 flex flex-col overflow-y-auto py-4"
                                >
                                    {messages.length === 0 && <EmptyState />}

                                    <div className="space-y-3">
                                        {displayMessages.map((m) => (
                                            <motion.div
                                                key={m.id}
                                                initial={{ opacity: 0, y: 8 }}
                                                animate={{ opacity: 1, y: 0 }}
                                                transition={{ duration: 0.18 }}
                                            >
                                                <ChatBubble role={m.role} text={m.content} />
                                            </motion.div>
                                        ))}
                                        {busy && !messages.some((x) => x.id === "draft") && (
                                            <motion.div
                                                initial={{ opacity: 0, y: 8 }}
                                                animate={{ opacity: 1, y: 0 }}
                                                transition={{ duration: 0.18 }}
                                            >
                                                <ChatBubble role="assistant" text="" typing />
                                            </motion.div>
                                        )}
                                    </div>
                                </div>

                                {/* 调试事件 */}
                                {showDebug && (
                                    <div className="mb-3 grid gap-3 border-t border-slate-200 pt-3 text-xs md:grid-cols-2">
                                        <div className="rounded-xl border border-slate-200 bg-slate-50 p-2">
                                            <div className="mb-1 text-[11px] font-medium text-slate-500">
                                                NDJSON Events
                                            </div>
                                            <div className="max-h-48 overflow-auto font-mono text-[11px] leading-relaxed whitespace-pre-wrap">
                                                {ndjsonEvents.map((e, i) => (
                                                    <div
                                                        key={`n-${i}`}
                                                        className="border-b border-slate-200/70 py-0.5 last:border-0"
                                                    >
                                                        {JSON.stringify(e)}
                                                    </div>
                                                ))}
                                                {ndjsonEvents.length === 0 && (
                                                    <div className="py-2 text-[11px] text-slate-500">
                                                        暂无 NDJSON 事件
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                        <div className="rounded-xl border border-slate-200 bg-slate-50 p-2">
                                            <div className="mb-1 text-[11px] font-medium text-slate-500">
                                                SSE Events
                                            </div>
                                            <div className="max-h-48 overflow-auto font-mono text-[11px] leading-relaxed whitespace-pre-wrap">
                                                {sseEvents.map((e, i) => (
                                                    <div
                                                        key={`s-${i}`}
                                                        className="border-b border-slate-200/70 py-0.5 last:border-0"
                                                    >
                                                        {JSON.stringify(e)}
                                                    </div>
                                                ))}
                                                {sseEvents.length === 0 && (
                                                    <div className="py-2 text-[11px] text-slate-500">
                                                        暂无 SSE 事件
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                )}
                            </div>

                            {/* 底部输入区 */}
                            <div className="flex-none border-t border-slate-200 bg白 py-3">
                                {/* 输入框 */}
                                <div className="relative rounded-3xl border border-slate-300 bg-slate-50 px-4 py-2">
                                    <textarea
                                        value={input}
                                        onChange={(e) => setInput(e.target.value)}
                                        onKeyDown={handleKey}
                                        placeholder="向我提问任何问题…（Enter 发送，Shift+Enter 换行）"
                                        className="h-14 max-h-40 w-full resize-none bg-transparent py-2 pr-10 text-sm leading-6 outline-none placeholder:text-slate-500"
                                    />
                                    <button
                                        onClick={() => void handleSend()}
                                        disabled={busy || !input.trim() || !activeId}
                                        className="absolute bottom-2.5 right-2.5 inline-flex h-8 w-8 items-center justify-center rounded-full bg-slate-900 text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
                                        title="发送"
                                    >
                                        {busy ? (
                                            <Loader2 className="h-4 w-4 animate-spin" />
                                        ) : (
                                            <Send className="h-4 w-4" />
                                        )}
                                    </button>
                                </div>

                                {/* 文件上传 */}
                                <ChatFileUploader
                                    className="mt-2"
                                    baseUrl={baseUrl}
                                    userId={userId}
                                    conversationId={activeId}
                                    onUploaded={handleFileUploaded}
                                    onError={handleUploadError}
                                />

                                {/* 接口信息 */}
                                <div className="mt-2 text-[11px] text-slate-500">
                                    {useDemo ? (
                                        <>
                                            Demo 模式 · 正在使用本地模拟 NDJSON。 修改{" "}
                                            <code className="font-mono">USE_DEMO=false</code>{" "}
                                            即可连接后端。
                                        </>
                                    ) : (
                                        <>
                                            POST{" "}
                                            <code className="font-mono">{apiUrl}</code>{" "}
                                            · Accept:{" "}
                                            <code className="font-mono">
                                                application/x-ndjson
                                            </code>{" "}
                                            · userId=
                                            <code className="font-mono">
                                                {userId || "u1"}
                                            </code>{" "}
                                            · conversationId=
                                            <code className="font-mono">{activeId}</code>
                                        </>
                                    )}
                                </div>
                            </div>
                        </div>
                    </main>
                </div>
            </div>

            {/* 删除 / 重命名 Dialog */}
            <ConfirmDialog
                open={!!confirmDelId}
                title="删除会话"
                message="确定要删除这个会话吗？此操作不可恢复。"
                danger
                confirmText="删除"
                cancelText="取消"
                onConfirm={() => {
                    if (confirmDelId) {
                        const id = confirmDelId;
                        setConfirmDelId(null);
                        doDelete(id);
                    }
                }}
                onCancel={() => setConfirmDelId(null)}
            />

            <RenameDialog
                open={!!renameId}
                initial={
                    renameId
                        ? conversations.find((c) => c.id === renameId)?.title ||
                        "新会话"
                        : "新会话"
                }
                onSave={(t: string) => {
                    if (renameId) {
                        doRename(renameId, t);
                        setRenameId(null);
                    }
                }}
                onCancel={() => setRenameId(null)}
            />

            {/* 待打开链接队列 */}
            {pendingLinks.length > 0 && (
                <div className="fixed bottom-4 right-4 z-50 w-[22rem] max-w-[92vw] rounded-xl border border-slate-200 bg-white p-3 shadow-xl">
                    <div className="mb-2 flex items-center justify-between">
                        <div className="text-sm font-medium">
                            选择要打开的链接（{pendingLinks.length}）
                        </div>
                        <button
                            onClick={() => setPendingLinks([])}
                            className="rounded-lg border border-slate-300 px-2 py-1 text-[11px] hover:bg-slate-50"
                        >
                            清空
                        </button>
                    </div>

                    <div className="max-h-56 space-y-2 overflow-auto">
                        {pendingLinks.map((it) => (
                            <div
                                key={it.id}
                                className="flex items-center gap-2 rounded-lg bg-slate-50 px-2 py-1.5"
                            >
                                <div className="min-w-0 flex-1 text-xs">
                                    <div className="font-mono truncate text-slate-900">
                                        {(() => {
                                            try {
                                                return new URL(it.url).host;
                                            } catch {
                                                return it.url;
                                            }
                                        })()}
                                    </div>
                                    <div className="truncate text-[11px] text-slate-500">
                                        {it.url}
                                    </div>
                                </div>
                                <button
                                    onMouseDown={(e) => {
                                        e.preventDefault();
                                        openOne(it.id);
                                    }}
                                    className="rounded-lg bg-slate-900 px-2 py-1 text-[11px] font-medium text-white hover:bg-slate-800"
                                    title="打开此链接"
                                >
                                    打开
                                </button>
                                <button
                                    onClick={() =>
                                        setPendingLinks((prev) =>
                                            prev.filter((x) => x.id !== it.id),
                                        )
                                    }
                                    className="rounded-lg border border-slate-300 px-2 py-1 text-[11px] hover:bg-slate-50"
                                    title="移除"
                                >
                                    ×
                                </button>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
