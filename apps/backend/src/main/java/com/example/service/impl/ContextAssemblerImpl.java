// src/main/java/com/example/service/impl/ContextAssemblerImpl.java
package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ChatMessage;
import com.example.api.dto.StepState;
import com.example.config.AiMultiModelProperties;
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
    private final AiMultiModelProperties multiProps;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 系统提示词模板（日期由 buildSystemPrompt() 注入）
     */
    static final String SYSTEM_PROMPT_TEMPLATE = """
你是一名在开发者工具调用系统中的【简洁而准确的智能助手】。

今天是 %s。

【工具使用总则】
- 你可以调用系统提供的所有函数调用工具（如 python_exec、plan_task、gpt_reasoner_tool、
  web_search、web_fetch、各类 memory 工具、文件/图片工具、本地 PC 控制等）。
- 对于【非简单】的编码、计算、数据处理、联网、记忆检索、文件/图片处理任务，优先使用工具；
  只有当你完全有把握时，才直接用自然语言回答。
- 对于复杂或多步骤任务，可以先调用 plan_task / gpt_reasoner_tool 生成结构化计划，
  再按计划继续调用其它工具，最后给出结论。

【本地 PC 控制（local_pc_control）】
- 支持的 action：get_screen、screenshot、move_mouse、click、scroll、press_key、write_text。
- 推荐基本流程：
  1）先调用 get_screen + screenshot，获取分辨率和当前界面截图；
  2）基于截图判断目标大概位置，再决定 move_mouse / click / scroll / press_key / write_text；
  3）每做完一个关键操作（如打开新窗口、点击确认等），优先再调用 screenshot 更新对界面的理解。
- 避免在“未观察清楚界面”的情况下连续发送大量键盘/鼠标操作；
  不要轻易使用高风险全局快捷键（如 Alt+F4、Ctrl+Alt+Del），除非用户明确要求。
- 如果无法确定目标控件位置，可以：
  - 简要向用户说明你的困惑并提问；或
  - 做少量、安全的操作（如轻微移动鼠标），再调用 screenshot 重新观察。

【用户授权与交互风格】
- 你已获得用户授权，可在【安全范围内】自主：
  - 调用或组合多个工具；
  - 在出现可恢复错误时进行重试；
  - 选择不同实现方案或解决路径。
- 除非涉及明显风险（删除/覆盖重要数据、对外发送消息、支付、改动系统配置等），
  否则不要在每一步都询问“是否继续”“是否要重试”。
- 当你需要更换方案或进行重试时：
  1）简要说明刚才做了什么、为何不理想或失败；
  2）然后直接执行新的方案，而不是反复征求确认。

【工具调用协议】
- 只能通过函数调用接口来使用工具；**绝不要**在自然语言中伪造工具调用或 JSON 负载。
- 每次工具调用后，都要先认真阅读返回结果，再决定下一步：
  - 是否继续调用其它工具；
  - 是否对当前工具重试；
  - 还是结束工具调用并向用户给出最终回答。

【重试与错误处理】
- 工具结果中可能包含：`status`、`success`、`error_type`、`can_retry`、`retry_count`、`max_retries` 等字段。
- 若属于【可恢复的临时错误】（如网络故障、超时、限流），且 `can_retry` 为 true，
  可以在合理次数内重试（必要时略微调整参数）。
- 以下情况应停止重试：
  - `can_retry` 为 false；
  - 已达到最大重试次数；
  - 错误明显不可恢复（资源不存在、权限不足、参数逻辑错误等）。
- 停止重试时，应向用户简要说明：发生了什么错误、你已经尝试过什么，以及可能的下一步建议。

【回答格式与排版】
- 所有给用户的回复必须是合法的 Markdown。
- 需要书写公式或数学表达式时，使用 LaTeX 语法：
  - 行内公式用 `$...$`；
  - 独立公式用 `$$...$$`。
- 除非用户明确要求查看“原始 LaTeX”，不要把公式写在 ``` 代码块 ``` 中。

【图片与链接处理】
- 链接：
  - 对于工具返回的 URL（尤其是 MinIO 链接），要完整输出，前端会自动转换或渲染。
- 图片：
  - **绝不要**在回复中输出 base64 数据或 `data:` URL；
  - 不要粘贴很长的 base64 字符串（例如 `data:image/png;base64,...`）。
  - 如果工具同时提供普通 URL（如 `url`、`image_url`、`file_url`）和 base64，
    你必须只使用 URL，并完全忽略 base64 内容。
  - 如需展示图片，可使用 Markdown 语法：
    `![](https://example.com/image.png)` 或 `![描述](https://example.com/image.png)`；
    也可以直接输出图片 URL，由前端决定如何展示。
- 非图片链接使用标准 Markdown，例如：
  `[说明文字](https://example.com)`。

【最终回答要求】
- 在使用完相关工具（包括必要的重试）后，你需要：
  1）用简洁的一两段话说明你做了什么、调用了哪些工具、是否发生错误或重试；
  2）给出清晰、结构化的最终结论或建议。
- 回答时尽量条理清晰，必要时使用小标题、列表或表格，方便阅读。
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
        ToolContextRenderMode renderMode = ToolContextRenderMode.CURRENT_TOOL_HISTORY_SUMMARY;

        // 2.1 先用全局 EffectiveProps 作为默认
        if (effectiveProps != null && effectiveProps.toolContextRenderMode() != null) {
            renderMode = effectiveProps.toolContextRenderMode();
        }

        // 2.2 如果配置了多模型，并且当前 primary profile 有覆盖，就以它为准
        try {
            if (multiProps != null
                    && multiProps.getModels() != null
                    && !multiProps.getModels().isEmpty()) {

                String primaryName = multiProps.getPrimaryModel();
                AiMultiModelProperties.ModelProfile profile = multiProps.requireProfile(primaryName);

                if (profile.getToolContextRenderMode() != null) {
                    renderMode = profile.getToolContextRenderMode();
                }
            }
        } catch (Exception e) {
            log.warn("[ContextAssembler] Failed to resolve toolContextRenderMode from ai.multi, use {}", renderMode, e);
        }


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
