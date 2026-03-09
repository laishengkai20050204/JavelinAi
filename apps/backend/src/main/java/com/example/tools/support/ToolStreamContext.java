package com.example.tools.support;

public final class ToolStreamContext {
    private ToolStreamContext() {}

    private static final ThreadLocal<ToolStreamObserver> TL = new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, ToolStreamObserver> BY_STEP =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setObserver(ToolStreamObserver observer) {
        if (observer == null) TL.remove();
        else TL.set(observer);
    }

    public static ToolStreamObserver getObserver() {
        return TL.get();
    }

    // ★ 新增：按 stepId 绑定，跨线程稳定
    public static void bind(String stepId, ToolStreamObserver observer) {
        if (org.springframework.util.StringUtils.hasText(stepId) && observer != null) {
            BY_STEP.put(stepId, observer);
        }
    }

    public static void unbind(String stepId) {
        if (org.springframework.util.StringUtils.hasText(stepId)) {
            BY_STEP.remove(stepId);
        }
    }

    public static ToolStreamObserver getObserver(String stepId) {
        ToolStreamObserver obs = TL.get();
        if (obs != null) return obs;
        if (!org.springframework.util.StringUtils.hasText(stepId)) return null;
        return BY_STEP.get(stepId);
    }
}
