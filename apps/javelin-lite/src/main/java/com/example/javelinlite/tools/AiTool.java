package com.example.javelinlite.tools;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface AiTool {

    String name();

    String description();

    Map<String, Object> parametersSchema();

    Mono<ToolResult> execute(ToolCall call, ChatRequest request);
}
