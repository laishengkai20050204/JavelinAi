package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.EffectiveProps;
import com.example.service.ConversationMemoryService;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AiToolComponent
@RequiredArgsConstructor
@Slf4j
public class FindRelevantMemoryTool implements AiTool {
    private static final int MIN_MESSAGES = 1;
    private static final int MAX_MESSAGES = 50;

    private final ConversationMemoryService memoryService;
    private final ObjectMapper mapper;
    private final EffectiveProps effectiveProps;

    @Override
    public String name() {
        return "find_relevant_memory";
    }

    @Override
    public String description() {
        return "Retrieve historical messages related to the current question on demand.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        int window = normalizeWindow(effectiveProps.memoryMaxMessages());
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "userId", Map.of("type", "string"),
                        "conversationId", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "maxMessages", Map.of(
                                "type", "integer",
                                "minimum", MIN_MESSAGES,
                                "maximum", MAX_MESSAGES,
                                "default", Math.max(MIN_MESSAGES, window)
                        )
                ),
                "required", List.of("userId", "conversationId")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        String userId = (String) args.get("userId");
        String conversationId = (String) args.get("conversationId");
        String query = (String) args.getOrDefault("query", "");
        int maxMessages = normalizeInt(args.get("maxMessages"),
                normalizeWindow(effectiveProps.memoryMaxMessages()), MIN_MESSAGES, MAX_MESSAGES);

        if (userId == null || conversationId == null) {
            throw new IllegalArgumentException("userId and conversationId are required");
        }

        List<Map<String, Object>> relevant = memoryService.findRelevant(userId, conversationId, query, maxMessages);
        log.debug("Tool '{}' returned {} message(s)", name(), relevant.size());

        String normalizedQuery = query == null ? "" : query.trim();
        String summary = relevant.isEmpty()
                ? String.format("No relevant messages found for conversation %s.", conversationId)
                : String.format("Retrieved %d message(s) for conversation %s%s.",
                relevant.size(),
                conversationId,
                normalizedQuery.isEmpty() ? "" : " matching \"" + normalizedQuery + "\"");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload", relevant);
        data.put("text", summary);
        data.put("count", relevant.size());

        return ToolResult.success(null, name(), false, data);
    }

    private int normalizeWindow(int configured) {
        return configured > 0 ? Math.min(Math.max(configured, MIN_MESSAGES), MAX_MESSAGES) : 12;
    }

    private int normalizeInt(Object value, int defaultValue, int min, int max) {
        int candidate = defaultValue;
        if (value instanceof Number number) {
            candidate = number.intValue();
        } else if (value instanceof String str) {
            try {
                candidate = Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return Math.min(Math.max(candidate, min), max);
    }
}
