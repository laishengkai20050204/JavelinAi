package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;

import java.util.Objects;

public record ToolExchange(
        int round,
        ToolCall call,
        ToolResult result
) {

    public ToolExchange {
        if (round < 0) {
            throw new IllegalArgumentException("round cannot be negative");
        }
        call = Objects.requireNonNull(call, "call cannot be null");
        result = Objects.requireNonNull(result, "result cannot be null");
        if (!call.id().equals(result.callId())) {
            throw new IllegalArgumentException("tool result callId does not match tool call");
        }
        if (!call.name().equals(result.name())) {
            throw new IllegalArgumentException("tool result name does not match tool call");
        }
    }
}
