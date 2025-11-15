package com.example.tools;

import com.example.api.dto.ToolResult;

import java.util.Map;

public interface AiTool {
    String name();

    String description();

    Map<String, Object> parametersSchema();

    /**
     * Execute the tool with normalized arguments.
     * <p>
     * 必须返回携带可读字段的结构，遵循以下约定：
     * <ol>
     *   <li>构造一段简短的 summary（例如“找到 3 个文件”、“python exit 0 ...”）。</li>
     *   <li>将 summary 写入返回数据：`payload.put("text", summary)` 或 `payload.put("message", summary)`。</li>
     *   <li>同时在顶层 data 上放置 `data.put("text", summary)`，以便记忆存储/提示词直接读取。</li>
     *   <li>其余结构化字段（args、payload、files 等）照常返回。</li>
     *   <li>如有错误场景，也要返回可读的 `text/message` 描述。</li>
     * </ol>
     * 不满足上述约定时，历史记录里的 content 会回退为 JSON，不利于 LLM 阅读。
     */
    ToolResult execute(Map<String, Object> args) throws Exception;
}
