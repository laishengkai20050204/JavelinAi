package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import reactor.core.publisher.Mono;

import java.util.List;

@FunctionalInterface
public interface DecisionService {

    Mono<Decision> decide(
            ChatRequest request,
            List<ToolExchange> previousToolExchanges,
            int round
    );
}
