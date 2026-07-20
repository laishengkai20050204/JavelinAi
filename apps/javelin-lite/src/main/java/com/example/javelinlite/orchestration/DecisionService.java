package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

@FunctionalInterface
public interface DecisionService {

    Mono<Decision> decide(
            ChatRequest request,
            List<ToolResult> previousToolResults,
            int round
    );
}
