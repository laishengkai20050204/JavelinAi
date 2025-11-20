package com.example.config;

// 比如放在 com.example.config 下面
public enum ToolContextRenderMode {

    /**
     * 1) 历史 + 当前 都保留完整工具结构：
     *    - assistant.tool_calls
     *    - role="tool"
     */
    ALL_TOOL,

    /**
     * 2) 历史摘要 + 当前保留工具：
     *    - 历史 FINAL：折叠成 assistant 文本摘要
     *    - 当前 step：完整工具结构 (assistant.tool_calls + role="tool")
     */
    CURRENT_TOOL_HISTORY_SUMMARY,

    /**
     * 3) 历史 + 当前 都只给摘要：
     *    - 不再发任何 role="tool" / tool_calls
     *    - 只发自然语言摘要（必要时带 message_id 指针）
     */
    ALL_SUMMARY
}
