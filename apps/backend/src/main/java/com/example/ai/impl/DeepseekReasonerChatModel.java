package com.example.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * Custom ChatModel for DeepSeek Reasoner.
 *
 * - 为每条 assistant 消息补充 reasoning_content 字段（请求侧）
 * - 从响应中解析 thinking / reasoning_content，并通过 metadata 暴露给上游
 * - 对最终续写阶段做“伪流式”拆分，让 SSE 前端能够看到逐段输出
 */
@Slf4j
@RequiredArgsConstructor
public class DeepseekReasonerChatModel implements ChatModel {

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String modelId;
    @Nullable
    private final Double temperature;
    @Nullable
    private final Boolean thinkEnabled;
    @Nullable
    private final String thinkLevel;

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            if (temperature != null) {
                body.put("temperature", temperature);
            }
            if (Boolean.TRUE.equals(thinkEnabled)) {
                String level = StringUtils.hasText(thinkLevel) ? thinkLevel : "medium";
                // DeepSeek Reasoner 思考开关；具体含义由上游解释
                body.put("reasoning", level);
            }

            ArrayNode messages = body.putArray("messages");
            for (Message message : prompt.getInstructions()) {
                appendMessage(messages, message);
            }

            applyToolsAndChoices(body, prompt.getOptions());

            String raw = client.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(2));

            if (raw == null) {
                throw new IllegalStateException("DeepSeek response body is null");
            }

            JsonNode root = mapper.readTree(raw);
            JsonNode choice0 = root.path("choices").path(0);
            JsonNode msg = choice0.path("message");

            String content = msg.path("content").asText("");

            // 思考内容：优先 "thinking"，其次 "reasoning_content"
            String reasoning = msg.path("thinking").asText("");
            if (reasoning == null || reasoning.isEmpty()) {
                reasoning = msg.path("reasoning_content").asText("");
            }

            List<AssistantMessage.ToolCall> toolCalls = parseToolCalls(msg.path("tool_calls"));

            Map<String, Object> metadata = new HashMap<>();
            if (reasoning != null && !reasoning.isEmpty()) {
                metadata.put("reasoning", reasoning);
                metadata.put("reasoning_content", reasoning);
            }

            AssistantMessage assistant = AssistantMessage.builder()
                    .content(content)
                    .properties(metadata) // Spring AI 要求 metadata 非 null
                    .toolCalls(toolCalls)
                    .build();

            Generation generation = new Generation(assistant);
            List<Generation> generations = Collections.singletonList(generation);

            return new ChatResponse(generations);
        } catch (WebClientResponseException ex) {
            // 让上游统一日志逻辑打印 HTTP 错误与响应体
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("DeepseekReasonerChatModel call failed", ex);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        // 决策阶段 / 最终续写阶段：统一走 DeepSeek 的原生流式接口。
        return Flux.defer(() -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            if (temperature != null) {
                body.put("temperature", temperature);
            }
            // 开启上游流式
            body.put("stream", true);
            if (Boolean.TRUE.equals(thinkEnabled)) {
                String level = StringUtils.hasText(thinkLevel) ? thinkLevel : "medium";
                body.put("reasoning", level);
            }

            ArrayNode messages = body.putArray("messages");
            for (Message message : prompt.getInstructions()) {
                appendMessage(messages, message);
            }

            applyToolsAndChoices(body, prompt.getOptions());

            return client.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(Duration.ofMinutes(2))
                    .flatMap(raw -> {
                        if (raw == null) {
                            return Flux.empty();
                        }
                        String trimmed = raw.trim();
                        if (trimmed.isEmpty() || "[DONE]".equalsIgnoreCase(trimmed)) {
                            return Flux.empty();
                        }

                        try {
                            JsonNode root = mapper.readTree(trimmed);
                            JsonNode choice0 = root.path("choices").path(0);
                            JsonNode delta = choice0.path("delta");
                            if (delta == null || delta.isMissingNode()) {
                                return Flux.empty();
                            }

                            String contentDelta = delta.path("content").asText("");

                            // 思考内容增量：优先 "thinking"，其次 "reasoning_content"
                            String reasoningDelta = extractTextOrJson(delta.path("thinking"));
                            if (reasoningDelta == null || reasoningDelta.isEmpty()) {
                                reasoningDelta = extractTextOrJson(delta.path("reasoning_content"));
                            }
                            // 兼容部分提供方把 thinking 挂在 choice/message 上的情况
                            if (reasoningDelta == null || reasoningDelta.isEmpty()) {
                                reasoningDelta = extractTextOrJson(choice0.path("thinking"));
                            }
                            if (reasoningDelta == null || reasoningDelta.isEmpty()) {
                                reasoningDelta = extractTextOrJson(choice0.path("message").path("thinking"));
                            }

                            List<AssistantMessage.ToolCall> toolCalls =
                                    parseToolCalls(delta.path("tool_calls"));

                            if ((contentDelta == null || contentDelta.isEmpty())
                                    && (reasoningDelta == null || reasoningDelta.isEmpty())
                                    && toolCalls.isEmpty()) {
                                // 纯控制帧（仅有 role 等），不向上游发空包
                                return Flux.empty();
                            }

                            Map<String, Object> metadata = Collections.emptyMap();
                            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                                Map<String, Object> meta = new HashMap<>();
                                meta.put("reasoning", reasoningDelta);
                                meta.put("reasoning_content", reasoningDelta);
                                metadata = meta;
                            }

                            AssistantMessage assistant = AssistantMessage.builder()
                                    .content(contentDelta)
                                    .properties(metadata)
                                    .toolCalls(toolCalls)
                                    .build();
                            Generation generation = new Generation(assistant);
                            return Flux.just(new ChatResponse(List.of(generation)));
                        } catch (Exception ex) {
                            log.warn("[DeepSeek-STREAM] failed to parse chunk: {}", ex.toString());
                            return Flux.empty();
                        }
                    });
        });
    }

    private void appendMessage(ArrayNode msgs, Message message) {
        if (message instanceof SystemMessage systemMessage) {
            ObjectNode n = msgs.addObject();
            n.put("role", "system");
            n.put("content", Objects.toString(systemMessage.getText(), ""));
        } else if (message instanceof UserMessage userMessage) {
            ObjectNode n = msgs.addObject();
            n.put("role", "user");
            n.put("content", Objects.toString(userMessage.getText(), ""));
        } else if (message instanceof AssistantMessage assistantMessage) {
            ObjectNode n = msgs.addObject();
            n.put("role", "assistant");
            n.put("content", Objects.toString(assistantMessage.getText(), ""));
            // Reasoner 要求历史 assistant 消息带 reasoning_content 字段，哪怕为空也要有。
            n.put("reasoning_content", "");

            if (assistantMessage.hasToolCalls()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (AssistantMessage.ToolCall tc : assistantMessage.getToolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", tc.type());
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments());
                }
            }
        } else if (message instanceof ToolResponseMessage toolMessage) {
            for (ToolResponseMessage.ToolResponse tr : toolMessage.getResponses()) {
                ObjectNode n = msgs.addObject();
                n.put("role", "tool");
                n.put("tool_call_id", tr.id());
                n.put("name", Objects.toString(tr.name(), ""));
                n.put("content", Objects.toString(tr.responseData(), ""));
            }
        }
    }

    private void applyToolsAndChoices(ObjectNode body, @Nullable ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOpts)) {
            return;
        }

        List<ToolCallback> callbacks =
                Optional.ofNullable(toolOpts.getToolCallbacks()).orElse(List.of());
        Set<String> allowed =
                Optional.ofNullable(toolOpts.getToolNames()).orElse(Set.of());

        if (!callbacks.isEmpty() && !allowed.isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolCallback cb : callbacks) {
                ToolDefinition def = cb.getToolDefinition();
                if (def == null) {
                    continue;
                }
                String name = def.name();
                if (!allowed.contains(name)) {
                    continue;
                }
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", name);
                if (StringUtils.hasText(def.description())) {
                    fn.put("description", def.description());
                }

                JsonNode schemaNode = mapper.createObjectNode();
                String schema = def.inputSchema();
                if (StringUtils.hasText(schema)) {
                    try {
                        schemaNode = mapper.readTree(schema);
                    } catch (Exception ignore) {
                    }
                }
                fn.set("parameters", schemaNode);
            }
        }

        if (options instanceof OpenAiChatOptions openAiOpts) {
            Object toolChoice = openAiOpts.getToolChoice();
            if (toolChoice != null) {
                body.set("tool_choice", mapper.valueToTree(toolChoice));
            }
            Boolean parallel = openAiOpts.getParallelToolCalls();
            if (parallel != null) {
                body.put("parallel_tool_calls", parallel);
            }
        }
    }

    private List<AssistantMessage.ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : node) {
            String id = tc.path("id").asText("call-" + System.nanoTime());
            String type = tc.path("type").asText("function");
            JsonNode fn = tc.path("function");
            String name = fn.path("name").asText("");
            JsonNode argsNode = fn.path("arguments");
            String arguments = argsNode.isTextual() ? argsNode.asText() : argsNode.toString();
            calls.add(new AssistantMessage.ToolCall(id, type, name, arguments));
        }
        return calls;
    }

    @Nullable
    private String extractTextOrJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String s = node.asText();
            return (s == null || s.isEmpty()) ? null : s;
        }
        String raw = node.toString();
        return raw.isEmpty() ? null : raw;
    }

}
