package com.example.javelinlite.tools;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class AiToolRegistry {

    private final Map<String, AiTool> tools;

    public AiToolRegistry(List<AiTool> tools) {
        Map<String, AiTool> registered = new LinkedHashMap<>();
        for (AiTool tool : tools) {
            AiTool previous = registered.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate tool name: " + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public List<AiTool> availableTools() {
        return List.copyOf(tools.values());
    }

    public Mono<ToolResult> execute(ToolCall call, ChatRequest request) {
        AiTool tool = tools.get(call.name());
        if (tool == null) {
            return Mono.just(ToolResult.failure(
                    call,
                    "Unknown tool: " + call.name()
            ));
        }

        return tool.execute(call, request)
                .onErrorResume(error -> Mono.just(ToolResult.failure(
                        call,
                        String.valueOf(error.getMessage())
                )));
    }
}
