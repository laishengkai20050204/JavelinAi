package com.example.tools.impl;

import com.example.ai.ChatGateway;
import com.example.ai.impl.SpringAiChatGateway;
import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolCall;
import com.example.api.dto.ToolResult;
import com.example.config.AiMultiModelProperties;
import com.example.config.AiProperties;
import com.example.service.ToolExecutionPipeline;
import com.example.tools.AiTool;
import com.example.util.ToolPayloads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * gpt_agent_tool：把一个子任务交给 GPT 子智能体执行（内部可自行调用 SERVER 工具）。
 *
 * 设计要点：
 *  - 所有「子对话」逻辑都在本工具内部完成，不使用 SinglePathChatService.run()。
 *  - 子对话的 messages 全部只存在于内存，不写 ConversationMemory / 不产生子 step 的 DRAFT/FINAL。
 *  - 真正的工具调用仍然通过 ToolExecutionPipeline.execute(...) 走审计链，但不写对话表。
 *  - 工具最终只返回一个 ToolResult，其 data 中至少包含 data.text（给主编排和记忆使用）。
 */
@Slf4j
@Component
@AiToolComponent
public class GptAgentTool implements AiTool {

    private final ChatGateway gateway;
    private final ToolExecutionPipeline toolPipeline;
    private final ObjectMapper mapper;
    private final AiMultiModelProperties multiModelProperties;

    /**
     * 用 @Lazy 打断和 SpringAiChatGateway / ToolExecutionPipeline 之间的循环依赖。
     */
    public GptAgentTool(
            @Lazy SpringAiChatGateway gateway,
            @Lazy ToolExecutionPipeline toolPipeline,
            ObjectMapper mapper,
            AiMultiModelProperties multiModelProperties
    ) {
        this.gateway = gateway;
        this.toolPipeline = toolPipeline;
        this.mapper = mapper;
        this.multiModelProperties = multiModelProperties;
    }

    /** AiMultiModelProperties 里 GPT 子模型的 profile 名称（和 application.yaml 保持一致）。 */
    private static final String GPT_PROFILE_NAME = "gpt-reasoner";

    private String resolveModelId() {
        return multiModelProperties.requireProfile(GPT_PROFILE_NAME).getModelId();
    }

    /** 子智能体内部最多循环几轮「决策 -> 工具 -> 再决策」。 */
    private static final int MAX_INNER_LOOPS = 10;

    /** 单次 GPT 调用超时时间。 */
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

    /** 单个工具执行超时时间。 */
    private static final Duration TOOL_TIMEOUT = Duration.ofMinutes(2);

    private static final String SYSTEM_PROMPT = """
你是一个助手，你可以调用其他函数但不要调用gpt_agent_tool 自身
""";

    @Override
    public String name() {
        return "gpt_agent_tool";
    }

    @Override
    public String description() {
        return "将一个子任务交给 GPT 子智能体执行，它在内部可以调用服务器工具并给出最终结论。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("goal", Map.of(
                "type", "string",
                "description", "要完成的子任务目标（必填）。"
        ));
        props.put("context", Map.of(
                "type", "string",
                "description", "补充上下文（可选）。"
        ));

        root.put("properties", props);
        root.put("required", List.of("goal"));
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        String goal    = asString(args.get("goal"));
        String context = asString(args.get("context"));

        // 这两个由编排层统一注入，不出现在 schema 中
        String userId         = firstNonNull(asString(args.get("userId")), asString(args.get("user_id")));
        String conversationId = firstNonNull(asString(args.get("conversationId")), asString(args.get("conversation_id")));

        if (!StringUtils.hasText(goal)) {
            return ToolResult.error(null, name(), "missing_required_field: goal");
        }
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            // 正常通过主编排调用时一定会有；手动调用没有的话就直接报错
            return ToolResult.error(null, name(), "missing_user_or_conversation");
        }

        log.debug("[gpt_agent_tool] start, userId={}, convId={}, goal={}",
                userId, conversationId, truncate(goal, 120));

        String finalText = runInnerOrchestration(userId, conversationId, goal, context);

        if (!StringUtils.hasText(finalText)) {
            finalText = "[gpt_agent_tool] 子智能体执行完成，但未能产生明确的最终回答。";
        }

        // 按 AiTool 约定构造 data：至少包含 data.text，方便后续记忆/展示
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", finalText);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", "gpt_agent");
        payload.put("goal", goal);
        if (StringUtils.hasText(context)) {
            payload.put("context", context);
        }
        payload.put("text", finalText);

        data.put("payload", payload);

        log.debug("[gpt_agent_tool] done, finalText.len={}", finalText.length());

        // id / reused 字段在外层 execOneToolWithIdempotency 中会被重建，这里占位即可
        return ToolResult.success(null, name(), false, data);
    }

    // ====================== 内部小编排器 ======================

    private String runInnerOrchestration(String userId,
                                         String conversationId,
                                         String goal,
                                         String context) {

        // 1) 初始化 messages（仅存在于内存，不走 ConversationMemory）
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", SYSTEM_PROMPT
        ));

        StringBuilder userContent = new StringBuilder();
        userContent.append("【子任务目标】\n").append(goal).append("\n\n");
        if (StringUtils.hasText(context)) {
            userContent.append("【补充上下文】\n").append(context).append("\n\n");
        }
        userContent.append("请你作为 GPT 子智能体，在需要时调用工具完成任务，并在完成后给出清晰的最终结论。");

        messages.add(Map.of(
                "role", "user",
                "content", userContent.toString()
        ));

        // 子智能体永远走 OPENAI 兼容协议
        AiProperties.Mode mode = AiProperties.Mode.OPENAI;

        for (int loop = 0; loop < MAX_INNER_LOOPS; loop++) {
            log.debug("[gpt_agent_tool] inner loop {}: messages={}", loop, messages.size());

            Map<String, Object> payload = new LinkedHashMap<>();

            // 告诉 SpringAiChatGateway：本次走 gpt-reasoner 这个 profile
            payload.put("_profile", GPT_PROFILE_NAME);

            // 也可以不传 model，完全交给 profile 决定；你现在有的话就保持一致
            payload.put("model", resolveModelId());

            payload.put("messages", messages);
            // 明确告诉网关：messages 已经是“扁平后的最终列表”
            payload.put("_flattened", true);

            // 让 GPT 自己决定是否调工具
            payload.put("toolChoice", "auto");

            // 透传上下文，方便 ToolExecutionPipeline 使用
            payload.put("userId", userId);
            payload.put("conversationId", conversationId);

            String json;
            try {
                json = gateway.call(payload, mode)
                        .block(MODEL_TIMEOUT);
            } catch (Exception e) {
                log.warn("[gpt_agent_tool] gateway call error on loop {}: {}", loop, e.toString());
                return "[gpt_agent_tool] inner LLM call error: " + e.getMessage();
            }

            if (!StringUtils.hasText(json)) {
                return "[gpt_agent_tool] empty response from gateway.";
            }

            JsonNode root;
            try {
                root = mapper.readTree(json);
            } catch (Exception e) {
                log.warn("[gpt_agent_tool] parse JSON failed: {}", e.toString());
                return "[gpt_agent_tool] invalid JSON from gateway.";
            }

            // ====== 关键：从 choices[0].message 里拿“完整的 assistant 消息” ======
            JsonNode msgNode = root.path("choices").path(0).path("message");
            String assistantContent = msgNode.path("content").asText("");
            JsonNode toolCallsNode  = msgNode.path("tool_calls");

            // 解析 tool_calls → ToolCall 对象（给你自己的 ToolExecutionPipeline 用）
            List<ToolCall> toolCalls = nodeToToolCalls(toolCallsNode);

            // 构造一条完整的 assistant 消息（包含 tool_calls），压回 messages
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantContent == null ? "" : assistantContent);

            if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                // 把原始的 tool_calls JSON 也塞回去，供 Spring AI 转成 AssistantMessage.ToolCall
                assistantMsg.put("tool_calls", mapper.convertValue(toolCallsNode, List.class));
            }

            // === 情况 A：没有工具调用，说明 GPT 已经打算直接给出最终回答 ===
            if (toolCalls.isEmpty()) {
                messages.add(assistantMsg);

                if (StringUtils.hasText(assistantContent)) {
                    // 返回最终回答
                    return assistantContent;
                }

                // GPT 也没说话，只能兜底
                log.warn("[gpt_agent_tool] loop {}: no tool_calls and empty content, json={}",
                        loop, truncate(json, 512));
                return "[gpt_agent_tool] 子智能体未能给出明确的最终回答。";
            }

            // === 情况 B：有工具调用 ===
            // 先把 assistant(tool_calls) 放进历史
            messages.add(assistantMsg);

            // 再执行每一个 tool_call，并把结果以 tool 消息回灌
            for (ToolCall call : toolCalls) {
                if (name().equals(call.name())) {
                    // 防止 GPT 递归调用 gpt_agent_tool 自己
                    log.warn("[gpt_agent_tool] GPT attempted recursive call to gpt_agent_tool; skipping.");
                    continue;
                }

                String summary = executeOneToolForInnerAgent(call, userId, conversationId);

                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", call.id());
                toolMsg.put("name", call.name());
                toolMsg.put("content", summary);

                messages.add(toolMsg);
            }

            // 工具执行完，进入下一轮 loop，让 GPT 看完工具结果后继续推理
        }

        return "[gpt_agent_tool] reached max inner loops without a clear final answer.";
    }

    /** 在子智能体上下文中执行一次 SERVER 工具，返回给 GPT 使用的简要 summary 文本。 */
    private String executeOneToolForInnerAgent(ToolCall call,
                                               String userId,
                                               String conversationId) {
        try {
            // execute 返回的是 ToolExecResult，而不是 ToolResult
            ToolExecutionPipeline.ToolExecResult exec =
                    toolPipeline.execute(call, userId, conversationId)
                            .block(TOOL_TIMEOUT);

            if (exec == null) {
                return "[tool:" + call.name() + "] no result (timeout or null).";
            }

            // 这里沿用你在 SinglePathChatService 里的用法：exec.data()
            Object raw = exec.data();

            // 尝试从 data 里提取人类可读的 text/message
            return summarizeToolResultForInnerAgent(call, raw);
        } catch (Exception e) {
            log.warn("[gpt_agent_tool] tool {} execution error: {}", call.name(), e.toString());
            return "[tool:" + call.name() + "] error: " + e.getMessage();
        }
    }

    /** 将 ToolResult 压成一段给 GPT 看的 summary 文本。 */
    @SuppressWarnings("unchecked")
    private String summarizeToolResultForInnerAgent(ToolCall call, Object raw) {
        // 1) 统一把 data 转成 Map，保留 stdout / generated_files 等字段
        Map<String, Object> data = ToolPayloads.toMap(raw, mapper);

        // 2) 尝试解包出 payload（通常里面有 text/message）
        Object inner = ToolPayloads.unwrap(raw, mapper);

        // 优先看 inner（通常是 payload）
        if (inner instanceof Map<?, ?> im) {
            Object t = im.get("text");
            if (t instanceof String s && StringUtils.hasText(s)) {
                return s;
            }
            Object m = im.get("message");
            if (m instanceof String s && StringUtils.hasText(s)) {
                return s;
            }
        }

        // 再看顶层 data 的 text/message
        Object t2 = data.get("text");
        if (t2 instanceof String s2 && StringUtils.hasText(s2)) {
            return s2;
        }
        Object m2 = data.get("message");
        if (m2 instanceof String s2 && StringUtils.hasText(s2)) {
            return s2;
        }

        // 都没有就给一段裁剪后的 JSON 预览
        try {
            String json = mapper.writeValueAsString(data);
            return "[tool:" + call.name() + "] " + truncate(json, 400);
        } catch (Exception e) {
            return "[tool:" + call.name() + "] (unserializable result)";
        }
    }

    // ====================== JSON 解析工具 ======================

    /** 提取 GPT 返回中的 assistant 文本（兼容 choices[0].message.content 和 _provider_assistant.content）。 */
    private String extractAssistantDraft(JsonNode root) {
        String c1 = root.path("choices").path(0).path("message").path("content").asText("");
        if (StringUtils.hasText(c1)) return c1;
        String c2 = root.path("_provider_assistant").path("content").asText("");
        return StringUtils.hasText(c2) ? c2 : null;
    }

    /** 提取 GPT 返回中的 tool_calls 列表（兼容 choices[0].message.tool_calls 和 _provider_assistant.tool_calls）。 */
    private List<ToolCall> parseToolCalls(JsonNode root) {
        // primary: choices[0].message.tool_calls
        JsonNode primary = root.path("choices").path(0).path("message").path("tool_calls");
        List<ToolCall> out = nodeToToolCalls(primary);
        if (!out.isEmpty()) return out;

        // fallback: _provider_assistant.tool_calls
        JsonNode fallback = root.path("_provider_assistant").path("tool_calls");
        return nodeToToolCalls(fallback);
    }

    private List<ToolCall> nodeToToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.size() == 0) return List.of();
        List<ToolCall> out = new ArrayList<>();
        for (JsonNode node : toolCalls) {
            String id = node.path("id").asText("call-" + UUID.randomUUID());
            String name = node.path("function").path("name").asText("");
            String argsJson = node.path("function").path("arguments").asText("{}");
            Map<String, Object> args = safeParseMap(argsJson);
            // 内部子智能体只执行 SERVER 端工具，不区分 CLIENT
            out.add(ToolCall.of(id, name, args, "SERVER"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseMap(String json) {
        if (json == null) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            log.warn("[gpt_agent_tool] invalid tool arguments JSON, preview={}", truncate(json, 256));
            return Map.of();
        }
    }

    // ====================== 小工具方法 ======================

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String firstNonNull(String a, String b) {
        return (a != null) ? a : b;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }
}
