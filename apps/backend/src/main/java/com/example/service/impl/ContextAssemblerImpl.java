// src/main/java/com/example/service/impl/ContextAssemblerImpl.java
package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ChatMessage;
import com.example.api.dto.StepState;
import com.example.config.EffectiveProps;
import com.example.config.ToolContextRenderMode;
import com.example.service.ContextAssembler;
import com.example.service.ConversationMemoryService;
import com.example.util.Fingerprint;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssemblerImpl implements ContextAssembler {

    private final ConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;
    private final EffectiveProps effectiveProps;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 系统提示词模板（日期由 buildSystemPrompt() 注入）
     */
    static final String SYSTEM_PROMPT_TEMPLATE = """
You are a concise, accurate assistant in a developer-oriented tool-calling system.

Today is %s.

You can call functions ("tools") provided by the system, such as `python_exec` for running Python code
in an isolated workspace with files and packages.

Behavior:
- Prefer calling tools for non-trivial coding, calculation, data processing, web access, memory lookup,
  or file/image handling, instead of only reasoning in natural language.
- Answer directly only when you can reliably do so without tools.
- For complex, multi-step tasks, you may first call a planning tool (e.g. `plan_task`) to design a plan,
  then follow that plan with further tool calls and finally give the user an answer.

Error handling and retries:
- Tool results may contain fields such as `status`, `success`, `error_type`, `can_retry`,
  `retry_count`, `max_retries`, or similar.
- Carefully read each tool result before deciding what to do next.
- If a result indicates a transient or retryable error (e.g. network failure, timeout, rate limit)
  and it is marked as retryable (for example `can_retry` is true or `retry_count < max_retries`),
  you should normally call the same tool again, possibly with slightly adjusted arguments.
- Give up on a tool only when it is clearly not retryable (for example `can_retry` is false,
  the maximum retry count has been reached, or the error is permanent such as "resource does not exist"
  or "permission denied").
- When you give up on a tool, explain to the user what failed, what has already been tried,
  and any reasonable next steps.

Tool calling protocol:
- Use the function-calling interface to invoke tools; do NOT describe tool calls in plain text.
- Never output raw JSON that looks like a tool invocation (for example with fields like "name"
  and "arguments") in your message content.

Formatting:
- All answers to the user must be valid Markdown.
- Use LaTeX math syntax for equations: `$...$` for inline math, `$$...$$` for display math.
- Do not put LaTeX inside ```code fences``` unless the user explicitly asks to see the raw LaTeX.
- When you need to show an image:
  - NEVER embed base64 data or `data:` URLs. Do not output long base64 strings such as
    `data:image/png;base64,...` in your messages.
  - If a tool result contains both a normal URL (for example in fields like `url`, `image_url`,
    `file_url`) and base64 image data, ALWAYS use the URL and completely omit the base64 content.
  - Display image URLs using Markdown image syntax, e.g. `![](https://example.com/image.png)`
    or `![description](https://example.com/image.png)`, so the image can be rendered inline.
- For non-image URLs, use normal Markdown links like `[text](https://example.com)`.

Final answers:
- After using tools (including any necessary retries), summarize what you did, which tools were used,
  any retries or failures, and give a clear, concise conclusion for the user.
""";

    @Override
    public Mono<AssembledContext> assemble(StepState st) {

        // 0) 绑定 stepId -> (userId, conversationId)
        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }
        final String userId = (st.req() != null) ? st.req().userId() : null;
        final String conversationId = (st.req() != null) ? st.req().conversationId() : null;

        // 1) 读取历史 FINAL + 当前 step 记录
        int limit = (effectiveProps != null)
                ? Math.max(1, effectiveProps.memoryMaxMessages())
                : 12;

        final List<Map<String, Object>> finalRows =
                (userId != null && conversationId != null)
                        ? memoryService.getContext(userId, conversationId, limit)
                        : List.of();

        final List<Map<String, Object>> stepRows =
                (st.stepId() != null && !st.stepId().isBlank())
                        ? memoryService.getContext(userId, conversationId, st.stepId(), limit)
                        : List.of();

        final List<Map<String, Object>> rows = new ArrayList<>(finalRows);
        if (!stepRows.isEmpty()) {
            rows.addAll(stepRows);
        }

        // 2) 渲染模式：决定历史/当前是否保留工具结构
        ToolContextRenderMode renderMode =
                (effectiveProps != null && effectiveProps.toolContextRenderMode() != null)
                        ? effectiveProps.toolContextRenderMode()
                        : ToolContextRenderMode.CURRENT_TOOL_HISTORY_SUMMARY;

        // 3) 构造传给模型的 messages
        List<Map<String, Object>> modelMessages = new ArrayList<>();
        modelMessages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        final List<ChatMessage> plainTexts = new ArrayList<>();
        final List<Map<String, Object>> structured = new ArrayList<>();

        Set<String> seenDecisionIds = new HashSet<>();

        // 历史 FINAL
        for (Map<String, Object> r : finalRows) {
            handleRow(r, modelMessages, plainTexts, structured, seenDecisionIds, true, renderMode);
        }
        // 当前 step
        for (Map<String, Object> r : stepRows) {
            handleRow(r, modelMessages, plainTexts, structured, seenDecisionIds, false, renderMode);
        }

        // 4) 计算上下文哈希（基于 rows + structured）
        String hash;
        try {
            String base = objectMapper.writeValueAsString(
                    Map.of("rows", rows, "structured", structured)
            );
            hash = Fingerprint.sha256(base);
        } catch (Exception e) {
            hash = Fingerprint.sha256(
                    (rows == null ? 0 : rows.size()) + ":" +
                            (structured == null ? 0 : structured.size())
            );
        }

        log.trace("[TRACE M1] assembled modelMessages size={} last={} digest={}",
                modelMessages.size(),
                MsgTrace.lastLine(modelMessages),
                MsgTrace.digest(modelMessages));

        return Mono.just(new AssembledContext(plainTexts, hash, structured, modelMessages));
    }

    // ---------- 工具方法 ----------

    private String toJsonString(Object payload) {
        if (payload == null) return null;
        if (payload instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRow(
            Map<String, Object> r,
            List<Map<String, Object>> modelMessages,
            List<ChatMessage> plainTexts,
            List<Map<String, Object>> structured,
            Set<String> seenDecisionIds,
            boolean fromFinal,
            ToolContextRenderMode mode
    ) {
        String roleStr = String.valueOf(r.getOrDefault("role", "user"));
        Object content = r.get("content");
        String text = (content instanceof String s) ? s
                : (content == null ? "" : String.valueOf(content));
        String payloadJson = toJsonString(r.get("payload"));

        // 两个布尔开关：根据模式 + 是否历史，决定保留结构还是摘要
        boolean keepToolStruct =
                (mode == ToolContextRenderMode.ALL_TOOL) ||
                        (!fromFinal && mode == ToolContextRenderMode.CURRENT_TOOL_HISTORY_SUMMARY);

        boolean summarizeOnly =
                (mode == ToolContextRenderMode.ALL_SUMMARY) ||
                        (fromFinal && mode == ToolContextRenderMode.CURRENT_TOOL_HISTORY_SUMMARY);

        // ---------- assistant ----------
        if ("assistant".equalsIgnoreCase(roleStr)) {
            boolean isDecision = false;

            if (payloadJson != null && !payloadJson.isBlank()) {
                try {
                    var root = objectMapper.readTree(payloadJson);
                    if ("assistant_decision".equals(root.path("type").asText(""))
                            && root.has("tool_calls")) {

                        List<Map<String, Object>> tcs = new ArrayList<>();
                        for (var n : root.path("tool_calls")) {
                            String id = n.path("id")
                                    .asText("call_" + UUID.randomUUID().toString()
                                            .replace("-", "")
                                            .substring(0, 12));
                            String name = n.path("function").path("name").asText("unknown_tool");
                            String args = n.path("function").path("arguments").asText("{}");

                            tcs.add(Map.of(
                                    "id", id,
                                    "type", "function",
                                    "function", Map.of("name", name, "arguments", args)
                            ));
                            seenDecisionIds.add(id);
                        }

                        Map<String, Object> assistantWithToolCalls = Map.of(
                                "role", "assistant",
                                "content", "",
                                "tool_calls", tcs
                        );
                        // 仅用于审计/哈希
                        structured.add(assistantWithToolCalls);
                        isDecision = true;

                        if (keepToolStruct) {
                            // 模式 1 / 模式 2 当前步骤：保留 tool_calls 结构
                            modelMessages.add(assistantWithToolCalls);
                        } else if (summarizeOnly) {
                            // 模式 2 的历史、或模式 3：只给自然语言摘要
                            String summary = "（模型计划调用工具：" +
                                    tcs.stream()
                                            .map(tc -> String.valueOf(
                                                    ((Map<?, ?>) tc.get("function")).get("name")))
                                            .toList()
                                    + "）";
                            modelMessages.add(Map.of("role", "assistant", "content", summary));
                            plainTexts.add(new ChatMessage("assistant", summary));
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            if (isDecision) {
                // assistant_decision 不再作为普通文本重复塞进上下文
                return;
            }

            // 普通 assistant 文本
            if (!text.isBlank()) {
                modelMessages.add(Map.of("role", "assistant", "content", text));
                plainTexts.add(new ChatMessage("assistant", text));
            }
            return;
        }

        // ---------- tool ----------
        if ("tool".equalsIgnoreCase(roleStr)) {
            String toolName = null, toolCallId = null, argsStr = "{}";
            String toolContent = (text == null) ? "" : text;

            if (payloadJson != null && !payloadJson.isBlank()) {
                try {
                    var root = objectMapper.readTree(payloadJson);
                    toolName = root.path("name").asText(null);
                    toolCallId = root.path("tool_call_id").asText(null);

                    // args 兜底逻辑
                    String persistedArgs = root.path("args").asText(null);
                    if (persistedArgs != null && !persistedArgs.isBlank()) {
                        try {
                            objectMapper.readTree(persistedArgs);
                            argsStr = persistedArgs;
                        } catch (Exception ignore) {
                        }
                    }
                    if ("{}".equals(argsStr)) {
                        String ek = root.path("data").path("_executedKey").asText(null);
                        if (ek != null) {
                            int idx = ek.indexOf("::");
                            if (idx >= 0 && idx + 2 < ek.length()) {
                                String maybeJson = ek.substring(idx + 2);
                                try {
                                    objectMapper.readTree(maybeJson);
                                    argsStr = maybeJson;
                                } catch (Exception ignore) {
                                }
                            }
                        }
                    }

                    // content 兜底：从 payload.data.payload / result 里抠
                    if (toolContent == null || toolContent.isBlank()) {
                        String v1 = root.path("data").path("payload").path("value").asText("");
                        String v2 = root.path("data").path("payload").isTextual()
                                ? root.path("data").path("payload").asText("") : "";
                        String v3 = root.path("data").path("result").asText("");
                        String v4 = root.path("result").asText("");
                        if (!v1.isBlank()) toolContent = v1;
                        else if (!v2.isBlank()) toolContent = v2;
                        else if (!v3.isBlank()) toolContent = v3;
                        else if (!v4.isBlank()) toolContent = v4;
                        else {
                            var dp = root.path("data").path("payload");
                            if (!dp.isMissingNode() && !dp.isNull()) {
                                toolContent = dp.isTextual()
                                        ? dp.asText()
                                        : objectMapper.writeValueAsString(dp);
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            if (toolCallId == null) {
                toolCallId = "call_" + UUID.randomUUID().toString()
                        .replace("-", "")
                        .substring(0, 12);
            }
            if (toolName == null) {
                toolName = "unknown_tool";
            }

            Map<String, Object> assistantWithToolCall = Map.of(
                    "role", "assistant",
                    "content", "",
                    "tool_calls", List.of(Map.of(
                            "id", toolCallId,
                            "type", "function",
                            "function", Map.of("name", toolName, "arguments", argsStr)
                    ))
            );
            Map<String, Object> toolMsg = Map.of(
                    "role", "tool",
                    "tool_call_id", toolCallId,
                    "name", toolName,
                    "content", toolContent
            );

            // structured（审计用）
            if (!seenDecisionIds.contains(toolCallId)) {
                structured.add(assistantWithToolCall);
                seenDecisionIds.add(toolCallId);
            }
            structured.add(toolMsg);

            if (keepToolStruct) {
                // 模式 1 或 “当前步骤 + 模式 2”：按 OpenAI 协议发 role=tool
                modelMessages.add(toolMsg);
            } else if (summarizeOnly) {

                if (mode == ToolContextRenderMode.ALL_SUMMARY && !fromFinal) {
                    // ✅ 模式 3 且当前步骤：用自然语言把这次工具调用讲出来（包括输出）
                    String summary = summarizeCurrentToolInteraction(toolName, argsStr, toolContent);
                    modelMessages.add(Map.of("role", "assistant", "content", summary));
                    plainTexts.add(new ChatMessage("assistant", summary));
                } else {
                    // 历史摘要（模式 2 的历史，或模式 3 的历史）
                    Object rowIdObj = r.get("id"); // conversation_messages.id
                    String messageId = (rowIdObj == null) ? null : String.valueOf(rowIdObj);

                    String summary = summarizeToolInteraction(toolName, messageId, argsStr);
                    modelMessages.add(Map.of("role", "assistant", "content", summary));
                    plainTexts.add(new ChatMessage("assistant", summary));
                }
            }
            return;
        }

        // ---------- 其它角色（user / system / etc.） ----------
        modelMessages.add(Map.of("role", roleStr, "content", text));
        plainTexts.add(new ChatMessage(roleStr, text));
    }

    // 每次需要系统提示词时调用这个方法
    public static String buildSystemPrompt() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String dateStr = today.format(DATE_FMT);
        return SYSTEM_PROMPT_TEMPLATE.formatted(dateStr);
    }

    /**
     * 为历史工具调用生成一个短摘要，放进 assistant 文本里，提示：
     * - 工具名
     * - 消息 id（供 inspect_tool_output 使用）
     * - 简短参数 / 代码预览
     */
    private String summarizeToolInteraction(String toolName, String messageId, String argsJson) {
        String safeToolName = (toolName == null || toolName.isBlank())
                ? "unknown_tool"
                : toolName;

        StringBuilder sb = new StringBuilder();

        sb.append("【历史工具调用摘要】\n");
        sb.append("- 工具名: ").append(safeToolName).append("\n");

        boolean hasId = (messageId != null && !messageId.isBlank());
        if (hasId) {
            sb.append("- message_id: ").append(messageId).append("\n");
        } else {
            sb.append("- message_id: （未知或未记录）\n");
        }

        // 针对 python_exec，给一个极短的 code 预览
        if ("python_exec".equals(safeToolName) && argsJson != null && !argsJson.isBlank()) {
            try {
                var node = objectMapper.readTree(argsJson);
                String code = node.path("code").asText(null);
                if (code != null && !code.isBlank()) {
                    String preview = code.strip();
                    int newline = preview.indexOf('\n');
                    if (newline >= 0) {
                        preview = preview.substring(0, newline);
                    }
                    int maxLen = 80;
                    if (preview.length() > maxLen) {
                        preview = preview.substring(0, maxLen) + "...";
                    }
                    sb.append("- 执行代码示例: ").append(preview).append("\n");
                }
            } catch (Exception ignore) {
            }
        }

        sb.append("\n如果你需要查看这次调用的完整输出，");
        sb.append("请调用工具 `inspect_tool_output`。");

        if (hasId) {
            sb.append("参数请**直接照抄**下面这段 JSON：\n");
            sb.append("{\"message_id\": ").append(messageId).append("}\n");
        } else {
            sb.append("并把相应的消息ID填入参数 `message_id` 中，例如：`{\"message_id\": 123}`。\n");
        }

        return sb.toString();
    }

    /**
     * 当前 step 的工具调用，用自然语言说明“我刚刚用哪个工具干了什么，并附上这次的输出内容”。
     * 不再要求模型去调用 inspect_tool_output。
     */
    private String summarizeCurrentToolInteraction(String toolName, String argsJson, String toolContent) {
        String safeToolName = (toolName == null || toolName.isBlank())
                ? "unknown_tool"
                : toolName;

        StringBuilder sb = new StringBuilder();
        sb.append("我刚刚调用了工具 `").append(safeToolName).append("` 完成了一步操作。\n");

        // 可选：简单带一点参数信息（避免太长）
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                var node = objectMapper.readTree(argsJson);
                String goal = node.path("goal").asText(null);
                String code = node.path("code").asText(null);

                if (goal != null && !goal.isBlank()) {
                    sb.append("- 任务目标: ").append(goal).append("\n");
                }
                if (code != null && !code.isBlank()) {
                    String preview = code.strip();
                    int newline = preview.indexOf('\n');
                    if (newline >= 0) preview = preview.substring(0, newline);
                    int maxLen = 80;
                    if (preview.length() > maxLen) preview = preview.substring(0, maxLen) + "...";
                    sb.append("- 执行代码示例: ").append(preview).append("\n");
                }
            } catch (Exception ignore) {
                // 参数解析失败就不额外噪音
            }
        }

        sb.append("\n工具输出的主要内容如下：\n");
        if (toolContent == null || toolContent.isBlank()) {
            sb.append("（工具没有返回可读内容，可能只是产生了文件或副作用。）");
        } else {
            String out = toolContent;
            int maxOutLen = 4000;
            if (out.length() > maxOutLen) {
                out = out.substring(0, maxOutLen) + "\n...（已截断，后面内容略）";
            }
            sb.append(out);
        }

        return sb.toString();
    }
}
