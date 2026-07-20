package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary deterministic implementation used only to keep the extracted project runnable.
 * Replace this class with the adapted Spring AI decision service from the full JavelinAI project.
 */
@Service
public final class FallbackDecisionService implements DecisionService {

    @Override
    public Mono<Decision> decide(
            ChatRequest request,
            List<ToolResult> previousToolResults,
            int round
    ) {
        if (!previousToolResults.isEmpty()) {
            ToolResult latest = previousToolResults.get(previousToolResults.size() - 1);
            return Mono.just(Decision.reply(
                    "[Javelin Lite temporary decision service] Tool result: " + latest.data()
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
                "[Javelin Lite temporary decision service] " + question
        ));
    }
}
