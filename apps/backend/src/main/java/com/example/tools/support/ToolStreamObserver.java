// ToolStreamObserver.java
package com.example.tools.support;

import java.util.Map;

public interface ToolStreamObserver {

    /**
     * 工具产生增量输出（比如 LLM token、thinking token）时调用。
     *
     * @param stepId   当前步骤 ID（用于前端区分是哪个 step）
     * @param toolName 工具名
     * @param payload  任意结构的增量数据，后面会序列化成 NDJSON/SSE
     */
    void onDelta(String stepId, String toolName, Map<String, Object> payload);
}
