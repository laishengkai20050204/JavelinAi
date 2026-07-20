package com.example.javelinlite.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record StepEvent(
        String type,
        String stepId,
        Instant timestamp,
        Map<String, Object> data
) {

    public StepEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static StepEvent started(String stepId) {
        return of("started", stepId, Map.of());
    }

    public static StepEvent toolCall(String stepId, ToolCall call) {
        return of("toolCall", stepId, Map.of(
                "id", call.id(),
                "name", call.name(),
                "arguments", call.arguments()
        ));
    }

    public static StepEvent toolResult(String stepId, ToolResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", result.callId());
        data.put("name", result.name());
        data.put("successful", result.successful());
        data.put("data", result.data());
        data.put("error", result.error());
        return of("toolResult", stepId, data);
    }

    public static StepEvent assistant(String stepId, String text) {
        return of("assistant", stepId, Map.of("text", text));
    }

    public static StepEvent noReply(String stepId) {
        return of("noReply", stepId, Map.of());
    }

    public static StepEvent finished(String stepId) {
        return of("finished", stepId, Map.of());
    }

    public static StepEvent error(String stepId, Throwable error) {
        return of("error", stepId, Map.of(
                "message", error == null ? "unknown error" : String.valueOf(error.getMessage())
        ));
    }

    private static StepEvent of(String type, String stepId, Map<String, Object> data) {
        return new StepEvent(type, stepId, Instant.now(), data);
    }
}
