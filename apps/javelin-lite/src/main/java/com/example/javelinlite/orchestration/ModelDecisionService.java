package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.model.ChatGateway;
import com.example.javelinlite.model.ModelProperties;
import com.example.javelinlite.tools.AiToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Primary
@ConditionalOnProperty(
        name = "javelin-lite.model.enabled",
        havingValue = "true"
)
public final class ModelDecisionService implements DecisionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ChatGateway gateway;
    private final AiToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ModelProperties properties;

    public ModelDecisionService(
            ChatGateway gateway,
            AiToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ModelProperties properties
    ) {
        this.gateway = gateway;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Mono<Decision> decide(
            ChatRequest request,
            List<ToolExchange> previousToolExchanges,
            int round
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolveModel(request));
        payload.put("messages", buildMessages(request, previousToolExchanges));
        payload.put("temperature", properties.temperature());

        List<Map<String, Object>> toolDefinitions = toolRegistry.openAiToolDefinitions();
        if (!toolDefinitions.isEmpty()) {
            payload.put("tools", toolDefinitions);
            payload.put("tool_choice", normalizeToolChoice(request.toolChoice()));
        }

        return gateway.call(payload).map(this::parseDecision);
    }

    private List<Map<String, Object>> buildMessages(
            ChatRequest request,
            List<ToolExchange> exchanges
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();

        String systemMessage = buildSystemMessage(request);
        if (StringUtils.hasText(systemMessage)) {
            messages.add(message("system", systemMessage));
        }

        messages.add(message("user", request.q()));

        int index = 0;
        while (index < exchanges.size()) {
            int exchangeRound = exchanges.get(index).round();
            List<ToolExchange> roundExchanges = new ArrayList<>();
            while (index < exchanges.size()
                    && exchanges.get(index).round() == exchangeRound) {
                roundExchanges.add(exchanges.get(index));
                index++;
            }

            messages.add(assistantToolCallMessage(roundExchanges));
            for (ToolExchange exchange : roundExchanges) {
                messages.add(toolResultMessage(exchange.result()));
            }
        }

        return List.copyOf(messages);
    }

    private String buildSystemMessage(ChatRequest request) {
        StringBuilder system = new StringBuilder();
        if (StringUtils.hasText(request.systemPrompt())) {
            system.append(request.systemPrompt().strip());
        }
        if (!request.runtimeContext().isEmpty()) {
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append("Trusted application runtime context (JSON):\n")
                    .append(writeJson(request.runtimeContext()));
        }
        return system.toString();
    }

    private Map<String, Object> assistantToolCallMessage(
            List<ToolExchange> exchanges
    ) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolExchange exchange : exchanges) {
            ToolCall call = exchange.call();

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", writeJson(call.arguments()));

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", call.id());
            toolCall.put("type", "function");
            toolCall.put("function", function);
            calls.add(toolCall);
        }

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", calls);
        return assistant;
    }

    private Map<String, Object> toolResultMessage(ToolResult result) {
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("successful", result.successful());
        if (result.successful()) {
            resultPayload.put("data", result.data());
        } else {
            resultPayload.put("error", result.error());
        }

        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", result.callId());
        toolMessage.put("name", result.name());
        toolMessage.put("content", writeJson(resultPayload));
        return toolMessage;
    }

    private Decision parseDecision(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        if (message.isMissingNode() || message.isNull()) {
            throw new IllegalStateException(
                    "Model response does not contain choices[0].message"
            );
        }

        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        if (!toolCalls.isEmpty()) {
            return Decision.callTools(toolCalls);
        }

        String assistantText = message.path("content").asText("").strip();
        return assistantText.isEmpty()
                ? Decision.noReply()
                : Decision.reply(assistantText);
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode node : toolCallsNode) {
            String id = node.path("id").asText("").strip();
            if (id.isEmpty()) {
                id = "call-" + UUID.randomUUID();
            }

            JsonNode function = node.path("function");
            String name = function.path("name").asText("").strip();
            if (name.isEmpty()) {
                throw new IllegalStateException(
                        "Model returned a tool call without a function name"
                );
            }

            calls.add(new ToolCall(
                    id,
                    name,
                    parseArguments(function.path("arguments"))
            ));
        }
        return List.copyOf(calls);
    }

    private Map<String, Object> parseArguments(JsonNode argumentsNode) {
        try {
            if (argumentsNode == null
                    || argumentsNode.isMissingNode()
                    || argumentsNode.isNull()) {
                return Map.of();
            }
            if (argumentsNode.isObject()) {
                return objectMapper.convertValue(argumentsNode, MAP_TYPE);
            }
            if (argumentsNode.isTextual()) {
                String json = argumentsNode.asText("").strip();
                return json.isEmpty() ? Map.of() : objectMapper.readValue(json, MAP_TYPE);
            }
            throw new IllegalStateException("Tool arguments must be a JSON object");
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to parse model tool arguments: " + argumentsNode,
                    error
            );
        }
    }

    private String resolveModel(ChatRequest request) {
        return StringUtils.hasText(request.model())
                ? request.model().strip()
                : properties.model();
    }

    private static String normalizeToolChoice(String toolChoice) {
        if (!StringUtils.hasText(toolChoice)) {
            return "auto";
        }
        String normalized = toolChoice.strip().toLowerCase();
        return switch (normalized) {
            case "auto", "none", "required" -> normalized;
            default -> throw new IllegalArgumentException(
                    "Unsupported toolChoice: " + toolChoice
            );
        };
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize model context", error);
        }
    }
}
