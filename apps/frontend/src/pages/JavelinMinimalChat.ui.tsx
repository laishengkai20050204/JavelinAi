// src/pages/JavelinMinimalChat.ui.tsx
import React, { useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { AlertTriangle, Bot, Pencil } from "lucide-react";
import SafeMarkdown from "../components/SafeMarkdown";
import type { Role } from "./JavelinMinimalChat.hooks";

interface DialogBaseProps {
    open: boolean;
    children: React.ReactNode;
    onClose?: () => void;
}

function DialogBase({ open, children, onClose }: DialogBaseProps) {
    return (
        <AnimatePresence>
            {open && (
                <motion.div
                    className="fixed inset-0 z-50 flex items-center justify-center"
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                >
                    <motion.div
                        className="absolute inset-0 bg-black/40"
                        onClick={onClose}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                    />
                    <motion.div
                        role="dialog"
                        aria-modal="true"
                        className="relative z-10 w-[92vw] max-w-sm rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-xl"
                        initial={{ y: 14, scale: 0.98, opacity: 0 }}
                        animate={{
                            y: 0,
                            scale: 1,
                            opacity: 1,
                            transition: { type: "spring", stiffness: 320, damping: 28 },
                        }}
                        exit={{ y: 6, scale: 0.98, opacity: 0, transition: { duration: 0.15 } }}
                    >
                        {children}
                    </motion.div>
                </motion.div>
            )}
        </AnimatePresence>
    );
}

export interface ConfirmDialogProps {
    open: boolean;
    title: string;
    message: string;
    confirmText?: string;
    cancelText?: string;
    danger?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}

export function ConfirmDialog({
                                  open,
                                  title,
                                  message,
                                  confirmText = "确认",
                                  cancelText = "取消",
                                  danger = false,
                                  onConfirm,
                                  onCancel,
                              }: ConfirmDialogProps) {
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div
                    className={`p-2 rounded-xl ${
                        danger ? "bg-red-600 text-white" : "bg-slate-900 text-white"
                    } dark:bg-white dark:text-slate-900`}
                >
                    <AlertTriangle className="h-4 w-4" />
                </div>
                <div className="font-semibold">{title}</div>
            </div>
            <div className="text-sm text-slate-600 dark:text-slate-300 mb-4">{message}</div>
            <div className="flex justify-end gap-2">
                <button
                    onClick={onCancel}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                    {cancelText}
                </button>
                <button
                    onClick={onConfirm}
                    className={`px-3 py-1.5 text-sm rounded-xl border ${
                        danger
                            ? "border-red-600 text-red-600 hover:bg-red-50"
                            : "border-slate-900 text-slate-900 hover:bg-slate-100"
                    } dark:border-white dark:text-white dark:hover:bg-white/10`}
                >
                    {confirmText}
                </button>
            </div>
        </DialogBase>
    );
}

export interface RenameDialogProps {
    open: boolean;
    initial: string;
    onSave: (t: string) => void;
    onCancel: () => void;
}

export function RenameDialog({ open, initial, onSave, onCancel }: RenameDialogProps) {
    const [val, setVal] = useState(initial);
    useEffect(() => {
        setVal(initial);
    }, [initial, open]);
    return (
        <DialogBase open={open} onClose={onCancel}>
            <div className="flex items-center gap-2 mb-2">
                <div className="p-2 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900">
                    <Pencil className="h-4 w-4" />
                </div>
                <div className="font-semibold">重命名会话</div>
            </div>
            <input
                value={val}
                onChange={(e) => setVal(e.target.value)}
                className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 bg-transparent px-3 py-2 text-sm outline-none"
                placeholder="会话名称"
            />
            <div className="flex justify-end gap-2 mt-3">
                <button
                    onClick={onCancel}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-300/70 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                    取消
                </button>
                <button
                    onClick={() => {
                        const t = val.trim();
                        if (t) onSave(t);
                    }}
                    className="px-3 py-1.5 text-sm rounded-xl border border-slate-900 text-slate-900 hover:bg-slate-100 dark:border-white dark:text-white dark:hover:bg-white/10"
                >
                    保存
                </button>
            </div>
        </DialogBase>
    );
}

export function EmptyState() {
    return (
        <div className="h-full w-full flex items-center justify-center">
            <div className="text-center max-w-sm">
                <div className="mx-auto h-12 w-12 rounded-2xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center mb-3">
                    <Bot className="h-6 w-6" />
                </div>
                <div className="font-semibold">开始一段新对话</div>
                <div className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                    使用左侧栏管理会话历史。
                </div>
            </div>
        </div>
    );
}

export interface ChatBubbleProps {
    role: Role;
    text: string;
    typing?: boolean;
}

export function ChatBubble({ role, text, typing }: ChatBubbleProps) {
    const isUser = role === "user";
    return (
        <div className={`flex gap-3 ${isUser ? "justify-end" : "justify-start"}`}>
            {!isUser && (
                <div className="shrink-0 h-8 w-8 rounded-xl bg-slate-900 text-white dark:bg-white dark:text-slate-900 flex items-center justify-center">
                    <Bot className="h-4 w-4" />
                </div>
            )}
            <div
                className={`max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-6 shadow-sm border ${
                    isUser
                        ? "bg-sky-600 text-white border-sky-700"
                        : "bg-white/85 dark:bg-slate-900/60 border-slate-200 dark:border-slate-800"
                }`}
            >
                {typing ? (
                    <TypingDots />
                ) : isUser ? (
                    text || <span className="opacity-60">…</span>
                ) : (
                    <SafeMarkdown
                        source={text}
                        allowHtml={false}
                        highlight={true}
                        proseClassName="m-0 p-0"
                    />
                )}
            </div>
            {isUser && <div className="shrink-0" />}
        </div>
    );
}

function TypingDots() {
    return (
        <div className="inline-flex items-center gap-1">
            <span
                className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce"
                style={{ animationDelay: "-0.25s" }}
            />
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce" />
            <span
                className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-bounce"
                style={{ animationDelay: "0.25s" }}
            />
        </div>
    );
}
