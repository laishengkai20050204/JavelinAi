// src/App.tsx
import React, { Suspense, lazy } from 'react';
import { Routes, Route, Link, Navigate } from 'react-router-dom';
import JavelinMinimalChat from './pages/JavelinMinimalChat.tsx';

// 按需加载：避免 Rete 相关包在 /chat 首屏加载
const ReteToolBuilderPage = lazy(() => import('./pages/ReteToolBuilderPage.tsx'));

export default function App() {
    return (
        <div className="min-h-screen">
            {/* 可选：简单导航 */}
            <nav style={{ padding: 12, borderBottom: '1px solid #eee' }}>
                <Link to="/chat" style={{ marginRight: 12 }}>Chat</Link>
                <Link to="/tools/builder">Tool Builder</Link>
            </nav>

            <Suspense fallback={<div style={{ padding: 16 }}>Loading…</div>}>
                <Routes>
                    <Route path="/" element={<Navigate to="/chat" replace />} />
                    <Route path="/chat" element={<JavelinMinimalChat />} />
                    <Route path="/tools/builder" element={<ReteToolBuilderPage />} />
                    <Route path="*" element={<div style={{ padding: 16 }}>404 Not Found</div>} />
                </Routes>
            </Suspense>
        </div>
    );
}
