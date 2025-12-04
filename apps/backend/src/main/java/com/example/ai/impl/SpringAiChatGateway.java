package com.example.ai.impl;

import com.example.ai.ChatGateway;
import com.example.ai.MultiModelRouter;
import com.example.ai.tools.SpringAiToolAdapter;
import com.example.ai.tools.ToolCallPlan;
import com.example.ai.tools.ToolCallPlanFactory;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.service.impl.StepContextStore;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spring AI 的适配层：
 * - 只依�?ToolCallPlanFactory + SpringAiToolAdapter�?
 * - ToolCallPlan -> ToolCallingChatOptions / ToolCallbacks / 日志预览�?
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringAiChatGateway implements ChatGateway {

    private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private static final int MAX_LOG_CONTENT_CHARS = 2000;

    // 多模型路�?
    private final MultiModelRouter modelRouter;
    private final ObjectMapper mapper;
    private final AiProperties properties;
    private final StepContextStore stepStore;
    private final EffectiveProps effectiveProps;

    // 新增：领域级工具规划 + Spring 工具适配
    private final ToolCallPlanFactory toolCallPlanFactory;
    private final SpringAiToolAdapter toolAdapter;

    // === �?Prompt �?Plan 打一个组合，避免重复�?plan ===
    private record PromptWithPlan(Prompt prompt, ToolCallPlan plan) {}

    @Override
    public Mono<String> call(Map<String, Object> payload, AiProperties.Mode mode) {
        return Mono.fromCallable(() -> executeCall(payload, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> stream(Map<String, Object> payload, AiProperties.Mode mode) {
        return Flux.defer(() -> executeStream(payload, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ===================== 同步调用 =====================

    private String executeCall(Map<String, Object> payload, AiProperties.Mode mode) {
        PromptWithPlan pp = toPrompt(payload, mode);
        Prompt prompt = pp.prompt();
        ToolCallPlan plan = pp.plan();

        // 请求预览（用于日�?/ 错误信息�?
        ObjectNode reqPreview = logOutgoingPayloadJson(prompt, plan, payload, mode);

        try {
            ChatModel model = resolveChatModel(payload);
            ChatResponse response = model.call(prompt);

            if (log.isDebugEnabled()) {
                logIncomingRawJson(response);
                logAssistantDecision(response);
            }

            String out = formatResponse(response, mode);

            if (log.isDebugEnabled()) {
                try {
                    ObjectNode root = (ObjectNode) mapper.readTree(out);
                    root.set("_provider_request", reqPreview);
                    root.set("_provider_raw", mapper.valueToTree(extractProviderRaw(response)));
                    root.set("_provider_assistant", buildAssistantDecisionNode(response));
                    out = mapper.writeValueAsString(root);
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Throwable e) {
            // 打印 4xx/5xx 的响应体
            logHttpError(e, reqPreview);
            throw e;
        }
    }

    /**
     * 根据 payload 中的 profile 信息选择 ChatModel�?
     * 优先级：
     *   1) _profile
     *   2) profile
     *   3) modelProfile
     * 若都为空，则�?ai.multi.primary-model�?
     */
    private ChatModel resolveChatModel(Map<String, Object> payload) {
        String profile = coerceString(payload.get("_profile"));
        if (!StringUtils.hasText(profile)) {
            profile = coerceString(payload.get("profile"));
        }
        if (!StringUtils.hasText(profile)) {
            profile = coerceString(payload.get("modelProfile"));
        }

        ChatModel model = modelRouter.get(profile);
        if (log.isDebugEnabled()) {
            log.debug("[Gateway] using profile={} -> {}",
                    (profile == null || profile.isBlank()) ? "<primary>" : profile,
                    model.getClass().getSimpleName());
        }
        return model;
    }

    // ===================== 流式调用 =====================

    private Flux<String> executeStream(Map<String, Object> payload, AiProperties.Mode mode) {
        PromptWithPlan pp = toPrompt(payload, mode);
        Prompt prompt = pp.prompt();
        ToolCallPlan plan = pp.plan();

        // 请求预览（日�?/ 错误�?
        ObjectNode reqPreview = logOutgoingPayloadJson(prompt, plan, payload, mode);

        StreamDebugAgg agg = new StreamDebugAgg(mapper);

        if (log.isDebugEnabled() && prompt.getOptions() instanceof ToolCallingChatOptions opts) {
            log.debug("Executing chat stream with model={} mode={}", opts.getModel(), mode);
        }

        ChatModel model = resolveChatModel(payload);

        return model.stream(prompt)
                .map(resp -> {
                    String chunk = formatStreamChunk(resp, mode);
                    if (log.isTraceEnabled()) {
                        log.trace("[AI-STREAM:chunk] {}", chunk);
                    }
                    if (log.isDebugEnabled()) {
                        agg.acceptChunk(chunk);
                    }
                    return chunk;
                })
                .doOnError(e -> logHttpError(e, reqPreview))
                .doOnComplete(() -> {
                    if (log.isDebugEnabled()) {
                        try {
                            ObjectNode assistantSnap = agg.assistantNode();
                            log.debug("[AI-RESP:MSG(stream)] {}",
                                    mapper.writerWithDefaultPrettyPrinter()
                                            .writeValueAsString(assistantSnap));
                        } catch (Exception ex) {
                            log.debug("[AI-RESP:MSG(stream)] <failed to serialize>: {}",
                                    ex.toString());
                        }
                    }
                });
    }

    /**
     * 累积流式 delta，完成时输出一个“助手决策快照”节点（role/content/tool_calls）�?
     */
    private static final class StreamDebugAgg {
        private final ObjectMapper mapper;
        private final StringBuilder content = new StringBuilder(4096);
        // id 去重；value 结构：{ "id": "...", "type":"function", "function":{ "name":"...", "arguments":"..." } }
        private final LinkedHashMap<String, ObjectNode> toolCalls = new LinkedHashMap<>();

        StreamDebugAgg(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void acceptChunk(String chunkJson) {
            try {
                JsonNode root = mapper.readTree(chunkJson);
                JsonNode delta = root.path("choices").path(0).path("delta");
                if (!delta.isMissingNode()) {
                    // 拼接文本
                    String part = delta.path("content").asText(null);
                    if (part != null && !part.isEmpty()) {
                        content.append(part);
                    }

                    // 累积 tool_calls
                    JsonNode tcArr = delta.path("tool_calls");
                    if (tcArr.isArray()) {
                        for (JsonNode tc : tcArr) {
                            String id = tc.path("id").asText("call-" + System.nanoTime());
                            ObjectNode normalized = normalizeToolCall(tc);
                            toolCalls.put(id, normalized);
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }

        private ObjectNode normalizeToolCall(JsonNode tc) {
            ObjectNode out = mapper.createObjectNode();
            out.put("id", tc.path("id").asText(""));
            out.put("type", tc.path("type").asText("function"));
            ObjectNode fn = out.putObject("function");
            fn.put("name", tc.path("function").path("name").asText(""));
            String args = tc.path("function").path("arguments").asText("{}");
            fn.put("arguments", args);
            return out;
        }

        ObjectNode assistantNode() {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "assistant");
            n.put("content", content.toString());
            if (!toolCalls.isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                toolCalls.values().forEach(tcs::add);
            }
            return n;
        }
    }

    // ===================== Prompt 构�?=====================

    private PromptWithPlan toPrompt(Map<String, Object> payload, AiProperties.Mode mode) {
        Object messagesObj = payload.get("messages");
        if (messagesObj == null) {
            throw new IllegalArgumentException("messages is required");
        }

        List<Map<String, Object>> messageMaps = convertMessagesObject(messagesObj);

        String seal = String.valueOf(payload.get("_tamperSeal"));
        String m3Digest = MsgTrace.digest(messageMaps);
        log.debug("[TRACE M3] gateway.in  size={} last={} digest={}",
                messageMaps.size(), MsgTrace.lastLine(messageMaps), m3Digest);

        boolean flattened = "true".equalsIgnoreCase(String.valueOf(payload.get("_flattened")));

        // 防篡改校�?
        if (flattened && seal != null && !seal.isBlank() && !seal.equals(m3Digest)) {
            log.error("[TRACE M3] TAMPER between Decision and Gateway: seal={} m3={}",
                    seal, m3Digest);
        }

        List<Message> messages = new ArrayList<>(
                messageMaps.stream()
                        .map(this::mapToMessage)
                        .filter(Objects::nonNull)
                        .toList()
        );

        // 若上游没扁平化，则插�?structuredToolMessages
        if (!flattened) {
            Object structuredObj = payload.get("structuredToolMessages");
            if (structuredObj == null) {
                structuredObj = payload.get("structured");
            }
            if (structuredObj != null) {
                List<Map<String, Object>> structuredMaps = convertMessagesObject(structuredObj);
                List<Message> structuredMsgs = structuredMaps.stream()
                        .map(this::mapToMessage)
                        .filter(Objects::nonNull)
                        .toList();

                int insertAt = indexBeforeLastUser(messages);
                if (insertAt < 0) insertAt = messages.size();
                messages.addAll(insertAt, structuredMsgs);
                log.debug("[AI-REQ:STRUCTURED] inserted={} atIndex={}",
                        structuredMsgs.size(), insertAt);
            }
        }

        log.debug("[TRACE M4] gateway.out size={} last={}",
                messages.size(),
                (messages.isEmpty() ? "<empty>" : messages.get(messages.size() - 1)));

        // 统一调用 ToolCallPlanFactory 做工具规划（�?Spring AI 类型�?
        ToolCallPlan plan = toolCallPlanFactory.buildPlan(payload, mode);
        // 再由本类�?plan 映射�?Spring AI 的工具调用选项
        ChatOptions options = buildSpringAiOptions(plan, mode);

        return new PromptWithPlan(new Prompt(messages, options), plan);
    }

    // plan -> ToolCallingChatOptions��������õ� Spring AI��
    private ChatOptions buildSpringAiOptions(ToolCallPlan plan, AiProperties.Mode mode) {
        AiProperties.Mode effMode = effectiveProps.modeOr(mode);

        OptionsBuilder builder = (effMode == AiProperties.Mode.OPENAI)
                ? new OpenAiOptionsBuilder(OpenAiChatOptions.builder())
                : new GenericOptionsBuilder(DefaultToolCallingChatOptions.builder());

        // ģ��
        if (StringUtils.hasText(plan.model())) {
            builder.model(plan.model());
        }

        // �¶�
        if (plan.temperature() != null) {
            builder.temperature(plan.temperature());
        }

        // ����ĺ������ϣ��Ѿ����� tool_choice + toggles��
        LinkedHashSet<String> allowed = new LinkedHashSet<>(plan.allowedFunctions());

        // ����� ToolCallback
        List<ToolCallback> serverCallbacks = toolAdapter.toolCallbacks().stream()
                .filter(cb -> allowed.contains(callbackName(cb)))
                .toList();

        // ǰ�˶����ռλ�ص�
        Set<String> serverNames = serverCallbacks.stream()
                .map(this::callbackName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        List<ToolCallback> clientCallbacks =
                buildFrontendCallbacks(plan.toolDefs(), allowed, serverNames);

        builder.toolCallbacks(
                Stream.concat(serverCallbacks.stream(), clientCallbacks.stream())
                        .toList()
        );
        builder.toolNames(allowed);
        // 禁用 Spring AI 内置的工具执行，保留工具定义与上下文，由自有逻辑/前端代理执行
        builder.internalToolExecutionEnabled(Boolean.FALSE);
        if (!allowed.isEmpty()) {
            builder.parallelToolCalls(Boolean.FALSE);
        }

        if (plan.rawToolChoice() != null) {
            builder.toolChoice(plan.rawToolChoice());
        }

        if (plan.toolContext() != null && !plan.toolContext().isEmpty()) {
            builder.toolContext(plan.toolContext());
        }

        log.debug("[TOOLS-ALLOWED] {}", allowed);
        log.debug("[CB-REGISTERED] server={}, client-def={}",
                serverCallbacks.stream().map(this::callbackName).toList(),
                clientCallbacks.stream().map(this::callbackName).toList());
        log.debug("[TOOL-CHOICE] {}", plan.rawToolChoice());

        return builder.build();
    }

    private List<ToolCallback> buildFrontendCallbacks(List<ToolCallPlan.ToolDef> defs,
                                                      Set<String> allowed,
                                                      Set<String> serverNames) {
        if (defs == null || defs.isEmpty() || allowed.isEmpty()) return List.of();
        List<ToolCallback> out = new ArrayList<>();
        for (ToolCallPlan.ToolDef def : defs) {
            if (!allowed.contains(def.name())) continue;
            if (!"client".equalsIgnoreCase(def.execTarget())) continue;
            if (serverNames.contains(def.name())) continue; // 有同名服务端实现则不再占�?
            out.add(new FrontendDefinitionCallback(def));
        }
        return out;
    }

    // ===================== 请求预览 & 日志 =====================

    private ObjectNode logOutgoingPayloadJson(Prompt prompt,
                                              ToolCallPlan plan,
                                              Map<String, Object> originalPayload,
                                              AiProperties.Mode mode) {

        ObjectNode fullPreview = buildOutgoingPayloadPreview(prompt, plan, originalPayload, mode);
        ObjectNode truncated = truncatePreviewForLogging(fullPreview);

        if (log.isDebugEnabled()) {
            try {
                log.debug("[AI-REQ] {}",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(truncated));
            } catch (Exception ignore) {
            }
        }
        return truncated;
    }

    private ObjectNode buildOutgoingPayloadPreview(Prompt prompt,
                                                   ToolCallPlan plan,
                                                   Map<String, Object> originalPayload,
                                                   AiProperties.Mode mode) {

        ObjectNode root = mapper.createObjectNode();

        // model（来�?plan�?
        if (StringUtils.hasText(plan.model())) {
            root.put("model", plan.model());
        }

        // profile（仅回显，实际路由逻辑�?resolveChatModel�?
        String profileFromPayload = coerceString(originalPayload.get("_profile"));
        if (StringUtils.hasText(profileFromPayload)) {
            root.put("_profile", profileFromPayload);
        }

        // tool_choice
        Object rawToolChoice = plan.rawToolChoice();
        if (rawToolChoice == null) {
            root.put("tool_choice", "auto");
        } else if (rawToolChoice instanceof String s) {
            root.put("tool_choice", s);
        } else {
            root.set("tool_choice", mapper.valueToTree(rawToolChoice));
        }

        // 显式回显代理/并行设置（与 buildSpringAiOptions 一致）
        root.put("proxy_tool_calls", true);
        root.put("parallel_tool_calls", false);

        // tool_context（来�?plan�?
        ObjectNode toolCtx = root.putObject("tool_context");
        if (plan.toolContext() != null) {
            plan.toolContext().forEach((k, v) ->
                    toolCtx.put(k, v == null ? "null" : String.valueOf(v)));
        }

        // messages（来�?Prompt�?
        ArrayNode msgs = root.putArray("messages");
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage sm) {
                ObjectNode n = msgs.addObject();
                n.put("role", "system");
                n.put("content", sm.getText() == null ? "" : sm.getText());
            } else if (m instanceof UserMessage um) {
                ObjectNode n = msgs.addObject();
                n.put("role", "user");
                n.put("content", um.getText() == null ? "" : um.getText());
            } else if (m instanceof AssistantMessage am) {
                ObjectNode n = msgs.addObject();
                n.put("role", "assistant");
                n.put("content", am.getText() == null ? "" : am.getText());
                if (am.hasToolCalls()) {
                    ArrayNode tcs = n.putArray("tool_calls");
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        ObjectNode t = tcs.addObject();
                        t.put("id", tc.id());
                        t.put("type", tc.type());
                        ObjectNode fn = t.putObject("function");
                        fn.put("name", tc.name());
                        fn.put("arguments", tc.arguments());
                    }
                }
            } else if (m instanceof ToolResponseMessage tm) {
                for (ToolResponseMessage.ToolResponse tr : tm.getResponses()) {
                    ObjectNode n = msgs.addObject();
                    n.put("role", "tool");
                    n.put("tool_call_id", tr.id());
                    n.put("name", tr.name() == null ? "" : tr.name());
                    n.put("content", tr.responseData() == null ? "" : tr.responseData());
                }
            }
        }

        // tools（来�?plan.toolDefs�?
        ArrayNode tools = root.putArray("tools");
        for (ToolCallPlan.ToolDef def : plan.toolDefs()) {
            ObjectNode t = tools.addObject();
            t.put("type", "function");
            ObjectNode fn = t.putObject("function");
            fn.put("name", def.name());
            if (StringUtils.hasText(def.description())) {
                fn.put("description", def.description());
            }
            fn.set("parameters", def.schema() != null ? def.schema() : mapper.createObjectNode());
            t.put("x-execTarget", def.execTarget() != null ? def.execTarget() : "server");
        }

        // temperature
        if (plan.temperature() != null) {
            root.put("temperature", plan.temperature());
        }

        // compatibility（方便观测：OPENAI / OLLAMA ...�?
        AiProperties.Mode effMode = effectiveProps.modeOr(mode);
        root.put("compatibility", effMode.toString());

        return root;
    }

    /**
     * 仅用于日志输出的预览截断�?
     * - messages[*].content �?MAX_LOG_CONTENT_CHARS 截断
     * - tools 仅输出工具名数组，不打印完整 parameters/schema/description
     */
    private ObjectNode truncatePreviewForLogging(ObjectNode full) {
        if (full == null) {
            return mapper.createObjectNode();
        }
        // 深拷贝一份，避免污染原对�?
        ObjectNode root = full.deepCopy();

        // 1) 截断 messages 内容
        JsonNode msgsNode = root.get("messages");
        if (msgsNode instanceof ArrayNode msgs) {
            for (JsonNode msgNode : msgs) {
                if (msgNode instanceof ObjectNode msgObj) {
                    JsonNode contentNode = msgObj.get("content");
                    if (contentNode != null && contentNode.isTextual()) {
                        String raw = contentNode.asText("");
                        String truncated = truncateForLog(raw, MAX_LOG_CONTENT_CHARS);
                        msgObj.put("content", truncated != null ? truncated : "");
                    }
                }
            }
        }

        // 2) tools：只保留工具�?
        JsonNode toolsNode = root.get("tools");
        if (toolsNode instanceof ArrayNode toolsArr) {
            ArrayNode newTools = root.putArray("tools");
            int count = 0;
            for (JsonNode toolNode : toolsArr) {
                if (!(toolNode instanceof ObjectNode tObj)) continue;
                String name = tObj.path("function").path("name").asText("");
                if (!name.isEmpty()) {
                    newTools.add(name);
                    count++;
                }
            }
            root.put("_tools_count", count);
        }

        return root;
    }

    private String truncateForLog(String text, int limit) {
        if (text == null) return null;
        if (text.length() <= limit) return text + " (len=" + text.length() + ")";
        String prefix = text.substring(0, limit);
        return prefix + "... (len=" + text.length() + ")";
    }

    // ===================== messages / payload 辅助 =====================

    private List<Map<String, Object>> convertMessagesObject(Object messagesObj) {
        if (messagesObj instanceof List<?> rawList) {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Object element : rawList) {
                if (element instanceof Map<?, ?> map) {
                    converted.add(castToMap(map));
                } else if (element instanceof JsonNode node) {
                    converted.add(mapper.convertValue(node, MAP_TYPE));
                } else if (element != null) {
                    throw new IllegalArgumentException(
                            "Unsupported message element type: " + element.getClass());
                }
            }
            return converted;
        }
        if (messagesObj instanceof JsonNode node) {
            return mapper.convertValue(node, MESSAGE_LIST_TYPE);
        }
        throw new IllegalArgumentException("messages must be a list");
    }

    private Message mapToMessage(Map<String, Object> source) {
        if (source == null) return null;
        String role = asString(source.get("role"));
        Object content = source.get("content");

        if ("system".equalsIgnoreCase(role)) {
            return new SystemMessage(normalizeContent(content));
        }
        if ("user".equalsIgnoreCase(role)) {
            // ✅ 这里改成支持多模态
            return buildUserMessageWithMedia(source);
        }
        if ("assistant".equalsIgnoreCase(role)) {
            String text = normalizeContent(content);
            List<AssistantMessage.ToolCall> toolCalls =
                    extractToolCalls(source.get("tool_calls"));
            Map<String, Object> metadata = castToMap(source.get("metadata"));
            if (metadata == null) metadata = new HashMap<>();
            return AssistantMessage.builder()
                    .content(text)
                    .properties(metadata)
                    .toolCalls(toolCalls)
                    .build();
        }
        if ("tool".equalsIgnoreCase(role)) {
            String id = asString(source.get("tool_call_id"));
            String name = asString(source.get("name"));
            String data = normalizeContent(source.get("content"));

            if (!StringUtils.hasText(data)) {
                Object payload = source.get("payload");
                if (payload instanceof Map<?, ?> pm) {
                    Object dataObj = pm.get("data");
                    Map<?, ?> dataMap = (dataObj instanceof Map<?, ?> dm) ? dm : pm;
                    Object inner = dataMap.get("payload");
                    if (inner instanceof Map<?, ?> im) {
                        Object v = im.get("value");
                        if (v instanceof String sv && StringUtils.hasText(sv)) {
                            data = sv;
                        } else {
                            try {
                                data = mapper.writeValueAsString(im);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            }

            ToolResponseMessage.ToolResponse resp =
                    new ToolResponseMessage.ToolResponse(
                            (id != null ? id : "tool-" + System.nanoTime()),
                            (name != null ? name : ""),
                            (data != null ? data : "")
                    );
            return ToolResponseMessage.builder()
                    .responses(List.of(resp))
                    .build();
        }

        // 未知角色，兜底当�?user
        return new UserMessage(normalizeContent(content));
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(Object toolCallsObj) {
        if (toolCallsObj == null) return Collections.emptyList();
        if (toolCallsObj instanceof List<?> list) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"));
                    String type = asString(map.get("type"));
                    Map<String, Object> fn = castToMap(map.get("function"));
                    String name = fn != null ? asString(fn.get("name")) : "";
                    String arguments = fn != null ? asString(fn.get("arguments")) : "{}";
                    calls.add(new AssistantMessage.ToolCall(
                            id != null ? id : "call-" + System.nanoTime(),
                            type != null ? type : "function",
                            name,
                            arguments != null ? arguments : "{}"
                    ));
                }
            }
            return calls;
        }
        return Collections.emptyList();
    }

    private String normalizeContent(Object content) {
        if (content == null) return "";
        if (content instanceof String str) return str;
        if (content instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text == null) text = map.get("content");
                    if (text == null) text = map.get("value");
                    if (text != null) builder.append(text);
                } else if (item != null) {
                    builder.append(item);
                }
            }
            return builder.toString();
        }
        if (content instanceof JsonNode node) {
            return node.isTextual() ? node.asText() : node.toString();
        }
        return content.toString();
    }

    private int indexBeforeLastUser(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new HashMap<>();
            raw.forEach((key, val) -> result.put(
                    key == null ? null : key.toString(), val));
            return result;
        }
        if (value instanceof JsonNode node) {
            return mapper.convertValue(node, MAP_TYPE);
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String callbackName(ToolCallback callback) {
        if (callback == null) {
            return "";
        }
        ToolDefinition definition = callback.getToolDefinition();
        String name = definition != null ? definition.name() : null;
        return StringUtils.hasText(name) ? name : "";
    }

    // ===================== FunctionOptions builder ���� =====================

    private interface OptionsBuilder {
        void model(String model);
        void temperature(Double temperature);
        void toolCallbacks(List<ToolCallback> callbacks);
        void toolNames(Set<String> names);
        void internalToolExecutionEnabled(Boolean enabled);
        void toolContext(Map<String, Object> context);
        void toolChoice(Object toolChoice);
        void parallelToolCalls(Boolean parallel);
        ChatOptions build();
    }

    private static final class GenericOptionsBuilder implements OptionsBuilder {
        private final ToolCallingChatOptions.Builder delegate;
        private GenericOptionsBuilder(ToolCallingChatOptions.Builder delegate) {
            this.delegate = delegate;
        }
        @Override public void model(String model) { delegate.model(model); }
        @Override public void temperature(Double temperature) { delegate.temperature(temperature); }
        @Override public void toolCallbacks(List<ToolCallback> callbacks) {
            delegate.toolCallbacks(callbacks != null ? callbacks : Collections.emptyList());
        }
        @Override public void toolNames(Set<String> names) { delegate.toolNames(names); }
        @Override public void internalToolExecutionEnabled(Boolean enabled) {
            delegate.internalToolExecutionEnabled(enabled);
        }
        @Override public void toolContext(Map<String, Object> context) { delegate.toolContext(context); }
        @Override public void toolChoice(Object toolChoice) { /* generic builder ��֧�� */ }
        @Override public void parallelToolCalls(Boolean parallel) { /* generic builder ��֧�� */ }
        @Override public ChatOptions build() { return delegate.build(); }
    }

    private static final class OpenAiOptionsBuilder implements OptionsBuilder {
        private final OpenAiChatOptions.Builder delegate;
        private OpenAiOptionsBuilder(OpenAiChatOptions.Builder delegate) {
            this.delegate = delegate;
        }
        @Override public void model(String model) { delegate.model(model); }
        @Override public void temperature(Double temperature) { delegate.temperature(temperature); }
        @Override public void toolCallbacks(List<ToolCallback> callbacks) {
            delegate.toolCallbacks(callbacks != null ? callbacks : Collections.emptyList());
        }
        @Override public void toolNames(Set<String> names) { delegate.toolNames(names); }
        @Override public void internalToolExecutionEnabled(Boolean enabled) {
            delegate.internalToolExecutionEnabled(enabled);
        }
        @Override public void toolContext(Map<String, Object> context) { delegate.toolContext(context); }
        @Override public void toolChoice(Object toolChoice) {
            if (toolChoice == null) return;
            if (toolChoice instanceof String str) delegate.toolChoice(str);
            else delegate.toolChoice(toolChoice);
        }
        @Override public void parallelToolCalls(Boolean parallel) { delegate.parallelToolCalls(parallel); }
        @Override public ChatOptions build() { return delegate.build(); }
    }

    private class FrontendDefinitionCallback implements ToolCallback {
        private final ToolDefinition definition;

        private FrontendDefinitionCallback(ToolCallPlan.ToolDef toolDef) {
            String schema = toolDef.schema() != null ? toolDef.schema().toString() : "{}";
            String description = toolDef.description() != null ? toolDef.description() : "";
            this.definition = DefaultToolDefinition.builder()
                    .name(toolDef.name())
                    .description(description)
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String argumentsJson) {
            throw new UnsupportedOperationException("frontend tool: " + callbackName(this));
        }

        @Override
        public String call(String argumentsJson, ToolContext context) {
            return call(argumentsJson);
        }
    }


    // ===================== provider metadata / 日志 =====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object meta) {
        if (meta == null) return Collections.emptyMap();
        if (meta instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        try {
            return mapper.convertValue(meta,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            return Map.of("value", String.valueOf(meta));
        }
    }

    private Map<String, Object> extractProviderRaw(ChatResponse response) {
        Map<String, Object> raw = new LinkedHashMap<>();
        try {
            Object respMeta = (response != null) ? response.getMetadata() : null;
            if (respMeta != null) raw.put("response_metadata", asMap(respMeta));
        } catch (Exception ignore) {
        }
        try {
            List<Generation> gens = (response != null) ? response.getResults() : null;
            if (gens != null && !gens.isEmpty()) {
                Object genMeta = gens.get(0).getMetadata();
                if (genMeta != null) raw.put("generation_metadata", asMap(genMeta));
            }
        } catch (Exception ignore) {
        }
        return raw;
    }

    private void logIncomingRawJson(ChatResponse response) {
        try {
            Map<String, Object> raw = extractProviderRaw(response);
            if (!raw.isEmpty()) {
                log.debug("[AI-RESP] {}",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
            } else {
                log.debug("[AI-RESP] <no metadata available>");
            }
        } catch (Exception e) {
            log.debug("[AI-RESP] <failed to serialize>: {}", e.toString());
        }
    }

    private void logAssistantDecision(ChatResponse response) {
        try {
            ObjectNode n = buildAssistantDecisionNode(response);
            if (n.size() > 0) {
                log.debug("[AI-RESP:MSG] {}",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n));
            } else {
                log.debug("[AI-RESP:MSG] <no assistant output>");
            }
        } catch (Exception e) {
            log.debug("[AI-RESP:MSG] <failed to serialize>: {}", e.toString());
        }
    }

    private ObjectNode buildAssistantDecisionNode(ChatResponse response) {
        ObjectNode n = mapper.createObjectNode();
        if (response == null
                || response.getResults() == null
                || response.getResults().isEmpty()) {
            return n;
        }

        Generation gen = response.getResults().get(0);
        if (gen == null || gen.getOutput() == null) return n;

        AssistantMessage am = gen.getOutput();
        n.put("role", "assistant");
        n.put("content", am.getText() == null ? "" : am.getText());

        if (am.hasToolCalls()) {
            ArrayNode tcs = n.putArray("tool_calls");
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                ObjectNode t = tcs.addObject();
                t.put("id", tc.id());
                t.put("type", tc.type());
                ObjectNode fn = t.putObject("function");
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments());
            }
        }
        return n;
    }

    @Nullable
    private AssistantMessage safeGetAssistantMessage(ChatResponse response) {
        if (response == null) return null;

        Generation gen = response.getResult();

        if (gen == null) {
            List<Generation> gens = response.getResults();
            if (gens != null) {
                for (Generation g : gens) {
                    if (g != null && g.getOutput() instanceof AssistantMessage) {
                        gen = g;
                        break;
                    }
                }
            }
        }

        if (gen == null) return null;

        Message output = gen.getOutput();
        if (output instanceof AssistantMessage am) {
            return am;
        }

        if (log.isWarnEnabled()) {
            log.warn("[AI-STREAM] generation output is not AssistantMessage: {}",
                    (output != null ? output.getClass() : "null"));
        }
        return null;
    }

    private String formatResponse(ChatResponse response, AiProperties.Mode mode) {
        AssistantMessage message = safeGetAssistantMessage(response);
        String content = (message != null && message.getText() != null)
                ? message.getText()
                : "";

        ObjectNode root = mapper.createObjectNode();
        ObjectNode messageNode = root.putObject("message");
        messageNode.put("role", MessageType.ASSISTANT.toString().toLowerCase(Locale.ROOT));
        messageNode.put("content", content);
        messageNode.put("thinking", "");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode choiceMessage = choice.putObject("message");
        choiceMessage.put("role", "assistant");
        choiceMessage.put("content", content);

        if (message != null && message.hasToolCalls()) {
            ArrayNode toolCallsNode = choiceMessage.putArray("tool_calls");
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                ObjectNode toolCallNode = toolCallsNode.addObject();
                toolCallNode.put("id", call.id());
                toolCallNode.put("type", call.type());
                ObjectNode fnNode = toolCallNode.putObject("function");
                fnNode.put("name", call.name());
                fnNode.put("arguments", call.arguments());
            }
        }

        choice.put("finish_reason", "stop");

        if (properties.getDebug() != null
                && properties.getDebug().isIncludeRawInGatewayJson()) {
            Map<String, Object> raw = extractProviderRaw(response);
            if (!raw.isEmpty()) {
                try {
                    root.set("_provider_raw", mapper.valueToTree(raw));
                } catch (Exception ignore) {
                }
            }
        }

        return root.toString();
    }

    private String formatStreamChunk(ChatResponse response, AiProperties.Mode mode) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode delta = choice.putObject("delta");

        AssistantMessage message = safeGetAssistantMessage(response);
        if (message == null) {
            if (log.isTraceEnabled()) {
                log.trace("[AI-STREAM] chunk without assistant message: {}", response);
            }
            choice.put("index", 0);
            return root.toString();
        }

        String content = message.getText();
        if (content != null && !content.isEmpty()) {
            delta.put("content", content);
        }

        if (message.hasToolCalls()) {
            ArrayNode toolCalls = delta.putArray("tool_calls");
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                ObjectNode toolCallNode = toolCalls.addObject();
                toolCallNode.put("id", call.id());
                toolCallNode.put("type", call.type());
                ObjectNode fnNode = toolCallNode.putObject("function");
                fnNode.put("name", call.name());
                fnNode.put("arguments", call.arguments());
            }
        }

        choice.put("index", 0);
        return root.toString();
    }

    @Nullable
    private String coerceString(Object v) {
        if (v == null) return null;
        if (v instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) return null;
            return node.asText();
        }
        String s = v.toString();
        return (s == null || s.isBlank()) ? null : s;
    }

    // ===================== HTTP 错误日志 =====================

    private void logHttpError(Throwable e, @Nullable ObjectNode reqPreview) {
        Throwable cause = Exceptions.unwrap(e);
        if (cause instanceof WebClientResponseException ex) {
            String body = null;
            try {
                body = ex.getResponseBodyAsString();
            } catch (Exception ignored) {
            }
            String reqJson = "<unavailable>";
            try {
                if (reqPreview != null && !reqPreview.isEmpty()) {
                    reqJson = mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(reqPreview);
                }
            } catch (Exception ignored) {
            }

            log.warn("""
                    [PROVIDER-HTTP-ERROR]
                      status      : {}
                      url/message : {}
                      respHeaders : {}
                      respBody    : {}
                      reqPreview  : {}
                    """,
                    ex.getStatusCode().value(),
                    ex.getMessage(),
                    ex.getHeaders(),
                    (body == null || body.isBlank() ? "<empty>" : body),
                    reqJson
            );

        } else {
            log.warn("[PROVIDER-ERROR] {}", e.toString());
        }
    }


    @SuppressWarnings("unchecked")
    private UserMessage buildUserMessageWithMedia(Map<String, Object> source) {
        Object content = source.get("content");

        StringBuilder textBuilder = new StringBuilder();
        List<Media> mediaList = new ArrayList<>();

        // 1) 新格式：content 是块数组 [ {type: input_text}, {type: input_image_url, ...} ]
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> block = castToMap(raw);
                String type = asString(block.get("type"));

                if ("input_text".equalsIgnoreCase(type)) {
                    Object t = block.get("text");
                    if (t != null) {
                        textBuilder.append(t);
                    }
                } else if ("input_image_url".equalsIgnoreCase(type)) {
                    String url = asString(block.get("url"));
                    if (!StringUtils.hasText(url)) {
                        continue;
                    }
                    String mime = asString(block.get("mime_type")); // 可选
                    MimeType mimeType = StringUtils.hasText(mime)
                            ? MimeType.valueOf(mime)
                            : MimeTypeUtils.IMAGE_PNG;
                    mediaList.add(new Media(mimeType, URI.create(url)));
                } else {
                    // 其他未知块类型，尽量把里面的文字捞出来拼到 text 里
                    Object t = block.get("text");
                    if (t == null) t = block.get("content");
                    if (t == null) t = block.get("value");
                    if (t != null) {
                        textBuilder.append(t);
                    }
                }
            }
        } else {
            // 2) 老格式：content 是纯字符串，兼容原来的行为
            textBuilder.append(normalizeContent(content));
        }

        String text = textBuilder.toString();

        if (mediaList.isEmpty()) {
            // 没有多模态，就退回原来的构造
            return new UserMessage(text);
        }

        // 有多模态，走 builder
        return UserMessage.builder()
                .text(text)
                .media(mediaList)
                .build();
    }


}