package com.example.javelinlite.api;

import java.util.Map;
import java.util.Objects;

public record ChatRequest(
        String model,
        String userId,
        String conversationId,
        String q,
        String toolChoice,
        String systemPrompt,
        Map<String, Object> runtimeContext
) {

    public ChatRequest {
        userId = requireText(userId, "userId");
        conversationId = requireText(conversationId, "conversationId");
        q = requireText(q, "q");
        toolChoice = toolChoice == null || toolChoice.isBlank()
                ? "auto"
                : toolChoice.strip();
        systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        runtimeContext = runtimeContext == null ? Map.of() : Map.copyOf(runtimeContext);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
