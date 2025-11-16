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
        final String SYSTEM_PROMPT = """
        You are a helpful assistant who writes concise, accurate answers in a developer-oriented tool-calling system.
        
        You have access to function-calling tools. In particular, you have a powerful tool called `python_exec` that can run Python code in its own isolated environment with a writable workspace and installed packages.
        
        With `python_exec` you can, for example:
        - run and debug Python scripts;
        - perform complex calculations, simulations, and data analysis;
        - parse and transform text or files;
        - read and write files in the workspace, and generate artifacts such as CSV/JSON, images, or documents.
        
        General tool-use rules:
        - Whenever a task involves non-trivial calculation, coding, data processing, parsing, simulation, or file generation, you SHOULD prefer calling `python_exec` instead of only reasoning in natural language.
        - If tools are available and helpful, you MUST propose function-calling tool calls rather than claiming you "cannot run code" or "do not have an environment", unless it is clearly impossible even with the tools.
        - If a question can be reliably answered from your own knowledge without tools, you may answer directly, but you should still consider whether a tool could make the answer more precise or verifiable.
        - After using tools, summarize the essential results clearly and concisely for the user.
        
        Structured tool-calling protocol:
        - When you decide to call a tool, you MUST use the structured `tool_calls` field in your response.
        - Do NOT describe tool invocations in natural language (for example, do NOT write things like "[工具 xxx 的执行_params] {...}" or similar).
        - Do NOT output JSON that describes a tool invocation inside the `content` field; JSON for tool calls must only appear inside `tool_calls[x].function.arguments`.
        - For assistant messages that contain `tool_calls`, the `content` field should usually be empty.
        """;

        List<Map<String, Object>> modelMessages = new ArrayList<>();
        modelMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

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
        StringBuilder sb = new StringBuilder();

        String safeToolName = (toolName == null ? "unknown_tool" : toolName);

        sb.append("（回顾信息）本次对话的历史中，曾经调用过一个工具步骤：\n");
        sb.append("- 工具名: ").append(safeToolName).append("\n");
        if (messageId != null && !messageId.isBlank()) {
            sb.append("- 消息ID (conversation_messages.id): ").append(messageId).append("\n");
        } else {
            sb.append("- 消息ID: （未记录或不可用）\n");
        }

        // ===== 参数摘要（避免整坨 JSON） =====
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                var node = objectMapper.readTree(argsJson);

                // 针对 python_exec 做一点友好处理：把 code / userId / conversationId 摘要一下
                if ("python_exec".equals(safeToolName)) {
                    String code = node.path("code").asText(null);
                    String userIdArg = node.path("userId").asText(null);
                    String convIdArg = node.path("conversationId").asText(null);

                    sb.append("这一步调用大致是执行了一段 Python 代码");
                    if (userIdArg != null || convIdArg != null) {
                        sb.append("（");
                        if (userIdArg != null) {
                            sb.append("userId=").append(userIdArg);
                            if (convIdArg != null) sb.append(", ");
                        }
                        if (convIdArg != null) {
                            sb.append("conversationId=").append(convIdArg);
                        }
                        sb.append("）");
                    }
                    sb.append("。\n");

                    if (code != null && !code.isBlank()) {
                        String preview = code.trim();
                        int maxLen = 120;
                        if (preview.length() > maxLen) {
                            preview = preview.substring(0, maxLen) + "...(代码已截断)";
                        }
                        sb.append("当时传入的 code 片段示例：\n");
                        sb.append(preview).append("\n");
                    }
                } else {
                    // 通用工具：列几个顶层字段名 + 简短 value 摘要
                    sb.append("当时调用时使用了一些参数，主要字段包括：");

                    List<String> fields = new ArrayList<>();
                    node.fieldNames().forEachRemaining(fields::add);

                    if (fields.isEmpty()) {
                        sb.append("（无显式字段）\n");
                    } else {
                        // 只展示前几个 key，避免太长
                        int maxFields = Math.min(5, fields.size());
                        sb.append("\n");
                        for (int i = 0; i < maxFields; i++) {
                            String f = fields.get(i);
                            var v = node.get(f);
                            String vStr;
                            if (v.isTextual()) {
                                vStr = v.asText();
                                if (vStr.length() > 60) {
                                    vStr = vStr.substring(0, 60) + "...";
                                }
                            } else if (v.isNumber() || v.isBoolean()) {
                                vStr = v.toString();
                            } else {
                                vStr = v.toString();
                                if (vStr.length() > 60) {
                                    vStr = vStr.substring(0, 60) + "...";
                                }
                            }
                            sb.append("- ").append(f).append(" = ").append(vStr).append("\n");
                        }
                        if (fields.size() > maxFields) {
                            sb.append("…（其余字段已省略，以节省上下文长度）\n");
                        }
                    }
                }
            } catch (Exception e) {
                // JSON 解析失败，就退回到一句粗略描述
                sb.append("（当时调用时使用了一些 JSON 参数，这里因解析失败未展开。）\n");
            }
        } else {
            sb.append("（这一步调用没有显式的参数 JSON，或记录为空。）\n");
        }

        // 重点：告诉它如何用 inspect_tool_output 拿完整信息
        sb.append("如果你在后续回答中需要查看这一步工具调用的完整输出，");
        sb.append("请调用工具 `inspect_tool_output`，并将参数 `message_id` 设置为上面提到的消息ID。");

        return sb.toString();
    }





}
