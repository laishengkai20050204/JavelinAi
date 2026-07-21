package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic local fallback. It is active only while real model access is disabled.
 */
@Service
@ConditionalOnProperty(
        name = "javelin-lite.model.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public final class FallbackDecisionService implements DecisionService {

    @Override
    public Mono<Decision> decide(
            ChatRequest request,
            List<ToolExchange> previousToolExchanges,
            int round
    ) {
        if (!previousToolExchanges.isEmpty()) {
            ToolResult latest = previousToolExchanges
                    .get(previousToolExchanges.size() - 1)
                    .result();
            return Mono.just(Decision.reply(
                    "[Javelin Lite fallback] Tool result: " + latest.data()
            ));
        }

        String question = request.q();
        if (question.contains("几点")
                || question.contains("时间")
                || question.contains("日期")) {
            ToolCall call = new ToolCall(
                    "call-" + UUID.randomUUID(),
                    "get_server_time",
                    Map.of("zoneId", "Asia/Shanghai")
            );
            return Mono.just(Decision.callTools(List.of(call)));
        }

        return Mono.just(Decision.reply(
                "[Javelin Lite fallback] " + question
        ));
    }
}
