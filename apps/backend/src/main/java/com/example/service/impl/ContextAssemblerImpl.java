package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ChatMessage;
import com.example.api.dto.StepState;
import com.example.config.EffectiveProps;
import com.example.service.ContextAssembler;
import com.example.service.ConversationMemoryService;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.util.Fingerprint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    static final String SYSTEM_PROMPT_TEMPLATE = """
You are a concise, accurate assistant in a developer-oriented tool-calling system.

Today is %s.

You have access to function-calling tools (see the provided tool list). One key tool is `python_exec`,
which can run Python code in an isolated workspace with packages and files.

General rules:
- Prefer calling tools for non-trivial coding, calculation, data processing, web access, memory lookup,
  or file/image handling, instead of only reasoning in natural language.
- For simple questions you can reliably answer from your own knowledge, you may answer directly.
- For complex, multi-step tasks, you may first call a planning tool (e.g. `plan_task`) to outline the
  goal and steps, then follow that plan with further tool calls and a final answer.

Formatting rules:
- All answers must be valid Markdown.
- When you need to write mathematical expressions or equations, use LaTeX math syntax:
  - use `$...$` for inline math;
  - use `$$...$$` for display equations on their own line.
- Do NOT put LaTeX math inside ```code fences``` unless the user explicitly asks to see the raw LaTeX.

Tool-calling protocol:
- When calling a tool, you MUST use the structured `tool_calls` field.
- Do NOT describe tool calls in normal text, and do NOT put tool-call JSON into `content`.
- Assistant messages that contain `tool_calls` should usually have empty `content`.
- After tools finish, read their outputs carefully and give the user a clear, concise summary and conclusion.
""";

    @Override
    public Mono<AssembledContext> assemble(StepState st) {

        // 0) 绑定 stepId -> (userId, conversationId)
        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }
        final String userId = (st.req() != null) ? st.req().userId() : null;
        final String conversationId = (st.req() != null) ? st.req().conversationId() : null;

        // 2) 读取上下文（只读 FINAL；条数从“生效配置”读取，默认>=1）
        int limit = (effectiveProps != null) ? Math.max(1, effectiveProps.memoryMaxMessages()) : 12;

        // 1) 历史 FINAL（保留原行为，受 limit 约束）
        final List<Map<String, Object>> finalRows =
                (userId != null && conversationId != null)
                        ? memoryService.getContext(userId, conversationId, limit)
                        : List.of();

        final List<Map<String, Object>> stepRows =
                (st.stepId() != null && !st.stepId().isBlank())
                        ? memoryService.getContext(userId, conversationId, st.stepId(), limit)
                        : List.of();

        final List<Map<String, Object>> rows = new ArrayList<>(finalRows);
        if (!stepRows.isEmpty()) rows.addAll(stepRows);


        // 3) 单趟构造最终 messages（严格按 DB 顺序）


        List<Map<String, Object>> modelMessages = new ArrayList<>();
        modelMessages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // 可选：为了向后兼容你原有 AssembledContext 字段，顺便维护：
        final List<ChatMessage> plainTexts = new ArrayList<>();        // 非工具行的扁平文本（user/assistant）
        final List<Map<String, Object>> structured = new ArrayList<>(); // 仅工具相关（assistant(tool_calls)+tool）

        // 已出现过的决策（避免重复合成）
        Set<String> seenDecisionIds = new HashSet<>();


        // ① 先处理历史 FINAL：不再给 LLM 塞 tool_calls，只生成自然语言摘要
        for (Map<String, Object> r : finalRows) {
            handleRow(r, modelMessages, plainTexts, structured, seenDecisionIds, true);
        }

        // ② 再处理当前 step：保持原有行为（还原成 assistant(tool_calls)+tool）
        for (Map<String, Object> r : stepRows) {
            handleRow(r, modelMessages, plainTexts, structured, seenDecisionIds, false);
        }


        // 4) 计算上下文哈希（基于 rows + “工具对”）
        String hash;
        try {
            String base = objectMapper.writeValueAsString(Map.of("rows", rows, "structured", structured));
            hash = Fingerprint.sha256(base);
        } catch (Exception e) {
            hash = Fingerprint.sha256((rows == null ? 0 : rows.size()) + ":" + (structured == null ? 0 : structured.size()));
        }

        log.trace("[TRACE M1] assembled modelMessages size={} last={} digest={}",
                modelMessages.size(),
                MsgTrace.lastLine(modelMessages),
                MsgTrace.digest(modelMessages));

        // 5) 返回（保持你原 AssembledContext 的语义）
        return Mono.just(new AssembledContext(plainTexts, hash, structured, modelMessages));
    }


    private String toJsonString(Object payload) {
        if (payload == null) return null;
        if (payload instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T nullTo(T val, T fallback) {
        return val != null ? val : fallback;
    }

    private void handleRow(
            Map<String, Object> r,
            List<Map<String, Object>> modelMessages,
            List<ChatMessage> plainTexts,
            List<Map<String, Object>> structured,
            Set<String> seenDecisionIds,
            boolean fromFinal // true = 历史 FINAL, false = 当前 step
    ) {
        String roleStr = String.valueOf(r.getOrDefault("role", "user"));
        Object content = r.get("content");
        String text = (content instanceof String s) ? s : (content == null ? "" : String.valueOf(content));
        String payloadJson = toJsonString(r.get("payload"));

        // ---------- assistant ----------
        if ("assistant".equalsIgnoreCase(roleStr)) {
            boolean isDecision = false;
            if (payloadJson != null && !payloadJson.isBlank()) {
                try {
                    var root = objectMapper.readTree(payloadJson);
                    if ("assistant_decision".equals(root.path("type").asText("")) && root.has("tool_calls")) {
                        List<Map<String, Object>> tcs = new ArrayList<>();
                        for (var n : root.path("tool_calls")) {
                            String id = n.path("id").asText("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
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
                        // ✅ 只进 structured，用于审计/哈希
                        structured.add(assistantWithToolCalls);

                        // ❌ 历史轮次不再发给 LLM
                        if (!fromFinal) {
                            // 当前 step（用于 continueAfterTools）仍然要发
                            modelMessages.add(assistantWithToolCalls);
                        }

                        isDecision = true;
                    }
                } catch (Exception ignore) {
                }
            }

            if (isDecision) {
                // assistant_decision 不再作为普通文本塞进去
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

                    // args 兜底逻辑（和你原来的一样）
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
                    if ((toolContent == null || toolContent.isBlank())) {
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
                                toolContent = dp.isTextual() ? dp.asText() : objectMapper.writeValueAsString(dp);
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            if (toolCallId == null)
                toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (toolName == null) toolName = "unknown_tool";

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

            // 先维护 structured（审计用）
            if (!seenDecisionIds.contains(toolCallId)) {
                structured.add(assistantWithToolCall);
                seenDecisionIds.add(toolCallId);
            }
            structured.add(toolMsg);

            if (fromFinal) {
                // ✅ 历史轮次：不再把 tool 作为 role=tool 发给 LLM，只给一个“指针”
                Object rowIdObj = r.get("id");          // conversation_messages.id
                String messageId = (rowIdObj == null) ? null : String.valueOf(rowIdObj);

                String summary = summarizeToolInteraction(toolName, messageId, argsStr);
                modelMessages.add(Map.of("role", "assistant", "content", summary));
                plainTexts.add(new ChatMessage("assistant", summary));
            } else {
                // 当前 step 仍然按 OpenAI 协议发 role=tool 的消息
                modelMessages.add(toolMsg);
            }
            return;
        }

        // ---------- 其它角色（user / system / etc.） ----------
        modelMessages.add(Map.of("role", roleStr, "content", text));
        plainTexts.add(new ChatMessage(roleStr, text));
    }

    // 每次需要系统提示词时调用这个方法
    public static String buildSystemPrompt() {
        // 这里你可以改时区，比如 Asia/Shanghai
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String dateStr = today.format(DATE_FMT);
        return SYSTEM_PROMPT_TEMPLATE.formatted(dateStr);
    }

    /**
     * 为“历史工具调用”生成一个简短的提示，告诉 LLM：
     * - 调用了哪个工具；
     * - 这条记录在 conversation_messages 表里的 id 是多少；
     * - 大致用了哪些关键参数（摘要，而不是完整 JSON）；
     * - 如果需要详细输出，请调用 inspect_tool_output。
     *
     * 注意：这里避免使用类似 "[工具 xxx 的执行结果]" 这种“协议样”文案，
     * 只做自然语言说明 + 少量参数摘要，防止模型学坏格式。
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

        // 可选：针对 python_exec，给一个极短的 code 预览（只取第一行，防止撑爆上下文）
        if ("python_exec".equals(safeToolName) && argsJson != null && !argsJson.isBlank()) {
            try {
                var node = objectMapper.readTree(argsJson);
                String code = node.path("code").asText(null);
                if (code != null && !code.isBlank()) {
                    String preview = code.strip();

                    // 只要第一行
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
                // 解析失败就直接略过，不额外加噪音
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





}
