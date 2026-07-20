package com.example.javelinlite.api;

import java.util.Map;
import java.util.Objects;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {

    public ToolCall {
        id = requireText(id, "id");
        name = requireText(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
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
