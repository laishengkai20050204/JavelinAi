package com.example.javelinlite.api;

public record ToolResult(
        String callId,
        String name,
        boolean successful,
        Object data,
        String error
) {

    public static ToolResult success(ToolCall call, Object data) {
        return new ToolResult(call.id(), call.name(), true, data, null);
    }

    public static ToolResult failure(ToolCall call, String error) {
        return new ToolResult(call.id(), call.name(), false, null, error);
    }
}
