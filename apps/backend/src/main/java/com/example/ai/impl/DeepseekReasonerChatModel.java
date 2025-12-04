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

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            if (temperature != null) {
                body.put("temperature", temperature);
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
        boolean hasTools = false;
        if (options instanceof ToolCallingChatOptions toolOpts) {
            Set<String> names =
                    Optional.ofNullable(toolOpts.getToolNames()).orElse(Set.of());
            hasTools = !names.isEmpty();
        }

        // 决策阶段（允许工具调用）：保持单包响应，避免拆分 tool_calls。
        if (hasTools) {
            return Flux.just(call(prompt));
        }

        // 最终续写阶段（禁用工具）：先获取完整响应，再将可见文本拆分成多段伪流式输出。
        return Flux.defer(() -> {
            ChatResponse full = call(prompt);
            if (full == null || full.getResults() == null || full.getResults().isEmpty()) {
                return Flux.just(full);
            }

            Generation gen = full.getResult();
            if (gen == null || !(gen.getOutput() instanceof AssistantMessage)) {
                return Flux.just(full);
            }

            AssistantMessage am = (AssistantMessage) gen.getOutput();
            String content = am.getText();
            Map<String, Object> metadata = am.getMetadata();

            String reasoning = "";
            if (metadata != null && !metadata.isEmpty()) {
                Object v = metadata.getOrDefault("reasoning",
                        metadata.getOrDefault("reasoning_content", null));
                if (v != null) {
                    reasoning = String.valueOf(v);
                }
            }

            String visibleText = content;
            if ((visibleText == null || visibleText.isEmpty())
                    && reasoning != null && !reasoning.isEmpty()) {
                visibleText = reasoning;
            }

            if (visibleText == null || visibleText.isEmpty()) {
                // 没有可见文本可以拆分时，退回单包
                return Flux.just(full);
            }

            // 拆成较小片段，并加一点点间隔，让前端能明显看到“逐步输出”
            List<String> chunks = splitText(visibleText, 10);
            return Flux.fromIterable(chunks)
                    .delayElements(Duration.ofMillis(40))
                    .map(part -> {
                        AssistantMessage partMsg = AssistantMessage.builder()
                                .content(part)
                                .properties(metadata != null ? metadata : Collections.emptyMap())
                                .toolCalls(am.getToolCalls())
                                .build();
                        Generation g = new Generation(partMsg);
                        return new ChatResponse(List.of(g));
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

    private List<String> splitText(String text, int chunkSize) {
        if (text == null || text.isEmpty() || chunkSize <= 0) {
            return List.of(text == null ? "" : text);
        }
        List<String> out = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i += chunkSize) {
            out.add(text.substring(i, Math.min(len, i + chunkSize)));
        }
        return out;
    }
}
