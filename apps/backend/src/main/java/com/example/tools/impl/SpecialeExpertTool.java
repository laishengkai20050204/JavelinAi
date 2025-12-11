package com.example.tools.impl;

import com.example.ai.ChatGateway;
import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * call_speciale_expert：将复杂/高难度问题转交给 DeepSeek Speciale 专家模型处理。
 *
 * 设计目标：
 * - 对 orchestrator 来说只是一个普通 AiTool；
 * - 内部通过 ChatGateway 调用多模型 profile（例如 ai.multi.models.deepseek-speciale）；
 * - 返回结构化结果，其中 data.text 提供给 LLM/记忆系统阅读。
 *
 * 使用约定：
 * - 主模型（例如 deepseek-v3.2）在 tool schema 中声明本工具；
 * - 当判断当前问题极难（数学/算法/复杂代码）时，可以调用本工具；
 * - 同一个用户问题避免多次调用，以控制成本。
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class SpecialeExpertTool implements AiTool, ApplicationContextAware {

    /** 默认使用的多模型 profile 名称，需要在 ai.multi.profiles 中配置 */
    private static final String DEFAULT_PROFILE = "deepseek-speciale";

    private final EffectiveProps effectiveProps;
    private final ObjectMapper objectMapper;

    /** 运行时拿 ChatGateway，避免 Bean 循环 */
    private ApplicationContext applicationContext;

    @Override
    public String name() {
        return "call_speciale_expert";
    }

    @Override
    public String description() {
        return "当你遇到特别困难的数学推理、算法证明或复杂代码分析问题时，调用这个工具向更强的 DeepSeek Speciale 模型请教。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();

        // 核心问题
        props.put("question", Map.of(
                "type", "string",
                "description", "要让 Speciale 解决的核心问题，用你已经理解好的方式重述。"
        ));

        // 补充上下文：题目原文、代码片段等
        props.put("context", Map.of(
                "type", "string",
                "description", "可选：题目原文、代码片段或其它关键信息，适当压缩后放在这里。",
                "nullable", true
        ));

        // 是否需要形式化/严格证明
        props.put("need_strict_proof", Map.of(
                "type", "boolean",
                "description", "是否需要形式化/分步严格证明。",
                "default", false
        ));

        // 透传 user / conversation 信息（可选）
        props.put("user_id", Map.of(
                "type", "string",
                "description", "用户 ID（可选）。"
        ));
        props.put("conversation_id", Map.of(
                "type", "string",
                "description", "会话 ID（可选）。"
        ));
        props.put("step_id", Map.of(
                "type", "string",
                "description", "步骤 ID（可选，用于链路记录）。"
        ));

        // 可选：覆盖默认 profile / model
        props.put("profile", Map.of(
                "type", "string",
                "description", "可选：多模型 profile 名称，默认使用 " + DEFAULT_PROFILE + "。",
                "nullable", true
        ));
        props.put("model", Map.of(
                "type", "string",
                "description", "可选：底层模型 ID，如需覆盖 profile 的默认 modelId。",
                "nullable", true
        ));

        schema.put("properties", props);
        schema.put("required", List.of("question"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        if (args == null) {
            args = Map.of();
        }

        String question = asString(args.get("question"), null);
        if (!StringUtils.hasText(question)) {
            log.error("[speciale] invalid args: question is missing");
            return ToolResult.error(null, name(), "Missing required parameter: question");
        }

        String context = asString(args.get("context"), null);
        boolean needStrict = asBoolean(args.get("need_strict_proof"), false);

        String userId = asString(
                args.get("user_id"),
                asString(args.get("userId"), "anonymous")
        );
        String conversationId = asString(
                args.get("conversation_id"),
                asString(args.get("conversationId"), UUID.randomUUID().toString())
        );
        String stepId = asString(args.get("step_id"), null);

        String profileName = asString(args.get("profile"), DEFAULT_PROFILE);
        String modelOverride = asString(args.get("model"), null);

        // 1) 组装发给 Speciale 的 messages
        String systemText = buildSystemPrompt();
        String userText = buildUserPrompt(question, context, needStrict);

        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemText);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userText);

        List<Map<String, Object>> messages = List.of(sysMsg, userMsg);

        // 2) 构造统一 payload，走 ChatGateway（改为 stream）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("_profile", profileName);
        payload.put("messages", messages);
        payload.put("_flattened", true);    // 不需要再拼接历史
        payload.put("toolChoice", "none");  // 禁用工具调用，Speciale 只作为专家回答

        if (StringUtils.hasText(modelOverride)) {
            payload.put("model", modelOverride);
        }
        if (StringUtils.hasText(userId)) {
            payload.put("userId", userId);
        }
        if (StringUtils.hasText(conversationId)) {
            payload.put("conversationId", conversationId);
        }
        if (StringUtils.hasText(stepId)) {
            payload.put("stepId", stepId);
        }

        AiProperties.Mode mode =
                (effectiveProps != null ? effectiveProps.mode() : AiProperties.Mode.OPENAI);
        Long timeoutMs = (effectiveProps != null ? effectiveProps.clientTimeoutMs() : null);

        // 全局基础超时（比如你 application.yaml 里配置的 clientTimeoutMs）
        long baseTimeoutMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 120_000L;

        // 1) 整体上限：Speciale 至少给 3 分钟；如果全局配置更长，就用更长
        long overallTimeoutMs = Math.max(baseTimeoutMs, 180_000L);  // 3 * 60 * 1000

        // 2) token 间隔（空闲）超时：比如 30 秒没 token 就认为挂了
        long idleTimeoutMs = 30_000L;

        log.info("[speciale] calling gateway(stream): profile={}, modelOverride={}, mode={}, overallTimeoutMs={} idleTimeoutMs={}",
                profileName, modelOverride, mode, overallTimeoutMs, idleTimeoutMs);

        long start = System.currentTimeMillis();

        StringBuilder contentBuf = new StringBuilder(4096);
        StringBuilder thinkingBuf = new StringBuilder(4096);
        java.util.List<String> chunks;

        try {
            chunks = chatGateway()
                    .stream(payload, mode)
                    // ★ 首个 token 最长等 overallTimeoutMs，之后每个 token 间隔不能超过 idleTimeoutMs
                    .timeout(
                            Duration.ofMillis(overallTimeoutMs),
                            item -> Mono.delay(Duration.ofMillis(idleTimeoutMs))
                    )
                    .doOnNext(chunk -> {
                        try {
                            JsonNode root = objectMapper.readTree(chunk);
                            JsonNode delta = root
                                    .path("choices")
                                    .path(0)
                                    .path("delta");

                            if (delta == null || delta.isMissingNode() || delta.isNull()) {
                                return;
                            }

                            String part = delta.path("content").asText(null);
                            if (StringUtils.hasText(part)) {
                                contentBuf.append(part);
                            }

                            String thinkingPart = delta.path("thinking").asText(null);
                            if (StringUtils.hasText(thinkingPart)) {
                                thinkingBuf.append(thinkingPart);
                            }
                        } catch (Exception parseEx) {
                            log.warn("[speciale] failed to parse stream chunk: {}", parseEx.toString());
                        }
                    })
                    .collectList()
                    // ★ 这里就不用再传 Duration 了，整体超时已经靠上面的 firstTimeout 控制
                    .block();

        } catch (Exception e) {
            log.error("[speciale] gateway stream exception", e);
            return ToolResult.error(null, name(), "Speciale gateway stream exception: " + e.getMessage());
        }


        long cost = System.currentTimeMillis() - start;
        int chunkCount = (chunks == null ? 0 : chunks.size());
        log.info("[speciale] gateway stream completed in {}ms, chunks={}", cost, chunkCount);

        if (chunks == null || chunks.isEmpty()) {
            log.error("[speciale] empty streaming response from gateway");
            return ToolResult.error(null, name(), "Empty streaming response from Speciale gateway");
        }

        String content = contentBuf.toString();
        String thinking = thinkingBuf.length() > 0 ? thinkingBuf.toString() : null;

        if (!StringUtils.hasText(content)) {
            log.warn("[speciale] response has no content");
            content = "";
        }

        // 3) 构造一个简易的“聚合快照”当 raw（兼容你之前的解析结构）
        Map<String, Object> rawMap;
        try {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", "assistant");
            msgNode.put("content", content);
            if (StringUtils.hasText(thinking)) {
                msgNode.put("thinking", thinking);
            }

            ObjectNode choice0 = objectMapper.createObjectNode();
            choice0.set("message", msgNode);

            ArrayNode choicesNode = objectMapper.createArrayNode();
            choicesNode.add(choice0);

            ObjectNode root = objectMapper.createObjectNode();
            root.set("choices", choicesNode);

            @SuppressWarnings("unchecked")
            Map<String, Object> tmp = objectMapper.convertValue(root, Map.class);
            rawMap = tmp;
        } catch (Exception e) {
            log.warn("[speciale] failed to build raw snapshot from stream, fallback to chunks list", e);
            rawMap = Map.of("chunks", chunks);
        }

        String summary = buildSummary(question, content);

        // 4) 组装 ToolResult
        Map<String, Object> data = new LinkedHashMap<>();

        // ★ text：不再截断，直接给完整回答，方便 LLM / 记忆系统阅读
        data.put("text", content);

        // ★ 额外保留一个简短 summary，方便列表展示或日志
        data.put("summary", summary);

        data.put("question", question);
        if (StringUtils.hasText(context)) {
            data.put("context", context);
        }
        data.put("answer", content);
        if (StringUtils.hasText(thinking)) {
            data.put("thinking", thinking);
        }
        data.put("profile", profileName);
        if (StringUtils.hasText(modelOverride)) {
            data.put("model", modelOverride);
        }
        data.put("user_id", userId);
        data.put("conversation_id", conversationId);
        if (StringUtils.hasText(stepId)) {
            data.put("step_id", stepId);
        }
        data.put("_source", name());
        data.put("raw", rawMap);

        log.debug("[speciale] summary={}, answer.length={}", summary, content.length());
        return ToolResult.success(null, name(), false, data);
    }


    // ========= ApplicationContextAware =========

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /** 运行时获取 ChatGateway，避免在 bean 依赖图里直接依赖它，防止循环。 */
    private ChatGateway chatGateway() {
        if (this.applicationContext == null) {
            throw new IllegalStateException("ApplicationContext not injected into SpecialeExpertTool");
        }
        return this.applicationContext.getBean(ChatGateway.class);
    }

    // ========= helpers =========

    private String asString(Object v, String defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof String s) return s;
        return Objects.toString(v, defaultValue);
    }

    private boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim().toLowerCase();
        if ("true".equals(s) || "yes".equals(s) || "y".equals(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "no".equals(s) || "n".equals(s) || "0".equals(s)) {
            return false;
        }
        return defaultValue;
    }

    private String buildSystemPrompt() {
        return """
                你是一名擅长高难数学推理、算法证明和复杂代码分析/重构的专家模型。
                - 只根据给定的 question 和 context 作答，不要臆造额外需求；
                - 回答时先给出清晰的分步推理过程，再在最后用 1-2 句话总结结论；
                - 如果信息不足以给出确定结论，请明确指出不确定性并说明还需要哪些条件。
                """.strip();
    }

    private String buildUserPrompt(String question, String context, boolean needStrict) {
        StringBuilder sb = new StringBuilder();
        sb.append("【问题】\n").append(question).append("\n\n");
        if (StringUtils.hasText(context)) {
            sb.append("【补充上下文】\n").append(context).append("\n\n");
        }
        if (needStrict) {
            sb.append("【要求】请给出形式化、分步的严格推理/证明过程，并在最后总结结论。\n");
        } else {
            sb.append("【要求】请给出条理清晰的推理步骤，并在最后总结结论。\n");
        }
        return sb.toString();
    }

    /**
     * 将 content 节点转成可读字符串，兼容 string / array-of-parts 两种结构。
     */
    private String extractContentText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                JsonNode t = part.get("text");
                if (t != null && !t.isNull()) {
                    sb.append(t.asText());
                }
            }
            return sb.toString();
        }
        return contentNode.asText();
    }

    private String buildSummary(String question, String answer) {
        String trimmed = answer == null ? "" : answer.trim();
        if (trimmed.length() > 120) {
            trimmed = trimmed.substring(0, 120) + "...";
        }
        return "Speciale 专家已回答问题：「" + truncate(question, 40) + "」 -> " + trimmed;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
