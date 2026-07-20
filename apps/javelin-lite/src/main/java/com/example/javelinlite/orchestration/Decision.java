package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ToolCall;

import java.util.List;

public record Decision(
        String assistantText,
        List<ToolCall> toolCalls
) {

    public Decision {
        assistantText = assistantText == null ? "" : assistantText.strip();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!assistantText.isEmpty() && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "A decision cannot contain final assistant text and tool calls together"
            );
        }
    }

    public static Decision reply(String text) {
        return new Decision(text, List.of());
    }

    public static Decision noReply() {
        return new Decision("", List.of());
    }

    public static Decision callTools(List<ToolCall> calls) {
        return new Decision("", calls);
    }

    public boolean requiresTools() {
        return !toolCalls.isEmpty();
    }
}
