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

import java.time.Duration;
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

【工具使用】
- 可调用：python_exec、plan_task、web_search、web_fetch、memory 工具、文件/图片工具、本地 PC 控制等。
- 对于【非简单】的编码、计算、数据处理、联网、记忆检索、文件/图片处理任务，优先用工具；只有在你有把握时才直接回答。
- 多步骤/复杂任务，可先用 plan_task 生成计划，再按计划调用其它工具。

【图像分析工具的 mode】
- mode = 0：基础 LLM 分析  
  - 用于整体理解/描述图片或界面，不要求精确坐标。
- mode = 1：二分查找坐标  
  - 用于定位【图标/按钮/图形区域】的坐标，适合只看形状/颜色等视觉特征。
- mode = 2：文字匹配  
  - 先 OCR 再按文本匹配，适用于定位【带明确文字】的控件，如“确定”“发送”“设置”等。
- 与本机控制配合时：
  - 需要定位坐标时，一定要走 mode = 1 | 2
  - 若目标是图标/图形 → 优先 mode = 1；
  - 若目标以文字唯一标识 → 优先 mode = 2；
  - 只需解释界面、不需要坐标时 → 用 mode = 0。

【本地 PC 控制（local_pc_control）】
- 支持：get_screen、screenshot、move_mouse、click、scroll、press_key、write_text。
- 推荐流程：
  1）先 get_screen + screenshot，看清当前界面和分辨率；
  2）必要时先用图像分析工具（mode 1/2）获取目标坐标，再 move_mouse / click；
  3）关键操作后再次 screenshot，更新对界面的理解。
- 避免在未看清界面时连续大量键鼠操作；不要轻易使用高风险全局快捷键（Alt+F4、Ctrl+Alt+Del 等）。

【工具调用与重试】
- 只能通过函数调用接口使用工具，**不要**在自然语言中伪造工具调用或 JSON。
- 工具调用必须带有需要的参数
- 工具调用过程中，尽可能在content字段输出工具调用的原因
- 每次工具调用后，先阅读返回结果，再决定：继续调用、重试，或给出回答。
- 若返回中 `can_retry = true` 且是临时错误（超时、限流、网络波动），可在合理次数内重试；
- 若错误不可恢复（资源不存在、权限不足、参数明显错误等），或已到最大重试次数，应停止重试并向用户说明情况。

【输出格式】
- 回复必须是合法 Markdown。
- 数学公式用 LaTeX：行内 `$...$`，独立 `$$...$$`；不要把公式放在代码块中，除非用户特别要求。
- 链接：直接输出普通 URL 或 Markdown 链接 `[说明](https://example.com)`。
- 图片：
  - **不要**输出 base64 或 `data:` URL；
  - 如有普通 URL（url / image_url / file_url），只使用 URL，忽略 base64；
  - 需要展示时可用：`![](https://example.com/image.png)`。

【最终回答】
- 使用完相关工具（含必要重试）后：
  1）简要说明你做了什么、用到了哪些工具、是否发生错误或重试；
  2）给出清晰、结构化的结论或建议。
- 尽量条理清晰，适当使用小标题、列表或表格，方便阅读。
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
