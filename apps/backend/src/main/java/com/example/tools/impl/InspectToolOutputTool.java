package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.service.ConversationMemoryService;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class InspectToolOutputTool implements AiTool {

    private final ConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "inspect_tool_output";
    }

    @Override
    public String description() {
        return "Inspect the full output of a historical tool message stored in conversation_messages by its ID.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // 只需要一个参数：message_id（主键）
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message_id", Map.of(
                                "type", "integer",
                                "description", "The primary key ID of the tool message in conversation_messages table."
                        )
                ),
                "required", List.of("message_id")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        Long messageId = parseLong(args.get("message_id"));
        if (messageId == null || messageId <= 0) {
            String error = "message_id must be a positive integer";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "inspect_tool_output_result");
            payload.put("ok", false);
            payload.put("error", error);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payload", payload);
            data.put("text", error);
            return ToolResult.success(null, name(), false, data);
        }

        // 从 DB 取出这一条记录（约定：实现 findMessageById）
        Map<String, Object> row = memoryService.findMessageById(messageId);
        if (row == null || row.isEmpty()) {
            String error = "No conversation_messages row found for id=" + messageId;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "inspect_tool_output_result");
            payload.put("ok", false);
            payload.put("message_id", messageId);
            payload.put("error", error);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payload", payload);
            data.put("text", error);
            return ToolResult.success(null, name(), false, data);
        }

        String role = stringOrEmpty(row.get("role"));
        String content = stringOrEmpty(row.get("content"));
        String payloadJson = stringOrEmpty(row.get("payload"));

        // 尝试从 payload 解析出工具名等信息（沿用你在 ContextAssembler 里的解析习惯）
        JsonNode payloadNode = null;
        String toolName = "unknown_tool";

        if (!payloadJson.isBlank()) {
            try {
                payloadNode = objectMapper.readTree(payloadJson);
                if (payloadNode.hasNonNull("name")) {
                    toolName = payloadNode.path("name").asText();
                }
            } catch (Exception e) {
                log.warn("[inspect_tool_output] failed to parse payload json for id={}", messageId, e);
            }
        }

        // 做一个 summary_text，方便 LLM 直接阅读
        String summaryText = buildSummaryText(messageId, role, toolName, content, payloadNode);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "inspect_tool_output_result");
        payload.put("ok", true);
        payload.put("message_id", messageId);
        payload.put("role", role);
        payload.put("tool_name", toolName);
        payload.put("content", content);
        payload.put("payload_raw", payloadJson);
        payload.put("payload_parsed", payloadNode);     // ??????????????? JsonNode?????????? null
        payload.put("summary_text", summaryText);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload", payload);
        data.put("text", summaryText);

        return ToolResult.success(null, name(), false, data);

    }

    // --------- 小工具方法 ---------

    private Long parseLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String buildSummaryText(Long messageId,
                                    String role,
                                    String toolName,
                                    String content,
                                    JsonNode payloadNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loaded historical message from conversation_messages.\n");
        sb.append("message_id: ").append(messageId).append("\n");
        sb.append("role: ").append(role).append("\n");
        sb.append("tool_name (from payload.name): ").append(toolName).append("\n\n");

        // content 截断一下，避免太长撑爆上下文
        String shortContent = content == null ? "" : content.trim();
        if (shortContent.length() > 2000) {
            shortContent = shortContent.substring(0, 2000) + "\n...(truncated)...";
        }

        sb.append("=== content (original message content) ===\n");
        sb.append(shortContent).append("\n");

        // payload 作为 debug 信息可选附带一点
        if (payloadNode != null && !payloadNode.isNull()) {
            try {
                String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(payloadNode);
                if (pretty.length() > 2000) {
                    pretty = pretty.substring(0, 2000) + "\n...(payload truncated)...";
                }
                sb.append("\n=== payload JSON (for debugging / extra context) ===\n");
                sb.append(pretty);
            } catch (Exception ignore) {
            }
        }

        return sb.toString();
    }
}
