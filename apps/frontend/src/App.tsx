// src/App.tsx
import React, { Suspense, lazy } from "react";
import { Routes, Route, Link, Navigate, useLocation } from "react-router-dom";
import { Bot, Wrench } from "lucide-react";
import JavelinMinimalChat from "./pages/JavelinMinimalChat";

const ReteToolBuilderPage = lazy(() => import("./pages/ReteToolBuilderPage"));

// ✅ 左侧“路由导航”——做成很细的 icon 栏
function SidebarNav() {
    const location = useLocation();
    const path = location.pathname;
    const isActive = (p: string) =>
        path === p || (p !== "/" && path.startsWith(p));

    return (
        <aside className="flex h-full w-16 sm:w-20 flex-col border-r border-slate-200 bg-white">
            {/* 顶部小 logo 区：高度很小、字体很小 */}
            <div className="px-1.5 pt-2 pb-1 border-b border-slate-200">
                <div className="text-[11px] font-semibold text-slate-900 text-center">
                    J
                </div>
                <div className="mt-0.5 text-[9px] text-slate-500 text-center">
                    Lab
                </div>
            </div>

            {/* 导航项：垂直居中 + 图标为主，文字很小 */}
            <nav className="flex-1 px-1 py-2 space-y-1 text-[11px]">
                <Link
                    to="/chat"
                    className={`flex flex-col items-center gap-0.5 rounded-xl px-1.5 py-1.5 hover:bg-slate-100 ${
                        isActive("/chat")
                            ? "bg-slate-900 text-white"
                            : "text-slate-700"
                    }`}
                >
                    <Bot className="h-4 w-4" />
                    <span className="text-[9px] leading-none">Chat</span>
                </Link>

                <Link
                    to="/tools/builder"
                    className={`flex flex-col items-center gap-0.5 rounded-xl px-1.5 py-1.5 hover:bg-slate-100 ${
                        isActive("/tools/builder")
                            ? "bg-slate-900 text-white"
                            : "text-slate-700"
                    }`}
                >
                    <Wrench className="h-4 w-4" />
                    <span className="text-[9px] leading-none">Tools</span>
                </Link>
            </nav>

            {/* 底部小字信息 */}
            <div className="px-1.5 pb-2 text-[9px] text-slate-400 text-center">
                v0.1
            </div>
        </aside>
    );
}

export default function App() {
    return (
        // 整体：左侧是超窄路由栏，右侧是你的 Chat/ToolBuilder 页面
        <div className="flex h-screen bg-slate-50">
            <SidebarNav />

            <div className="flex min-h-0 flex-1 flex-col">
                <Suspense fallback={<div className="p-4 text-sm">Loading…</div>}>
                    <Routes>
                        <Route path="/" element={<Navigate to="/chat" replace />} />
                        <Route path="/chat" element={<JavelinMinimalChat />} />
                        <Route path="/tools/builder" element={<ReteToolBuilderPage />} />
                        <Route
                            path="*"
                            element={
                                <div className="p-4 text-sm text-slate-600">
                                    404 Not Found
                                </div>
                            }
                        />
                    </Routes>
                </Suspense>
            </div>
        </div>
    );
}
