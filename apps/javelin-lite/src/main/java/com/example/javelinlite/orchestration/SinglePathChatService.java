package com.example.javelinlite.orchestration;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.StepEvent;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.tools.AiToolRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public final class SinglePathChatService {

    private static final int MAX_ROUNDS = 8;

    private final DecisionService decisionService;
    private final AiToolRegistry toolRegistry;

    public SinglePathChatService(
            DecisionService decisionService,
            AiToolRegistry toolRegistry
    ) {
        this.decisionService = decisionService;
        this.toolRegistry = toolRegistry;
    }

    public Flux<StepEvent> run(ChatRequest request) {
        String stepId = "step-" + UUID.randomUUID();

        return Flux.concat(
                        Flux.just(StepEvent.started(stepId)),
                        loop(request, stepId, List.of(), 0),
                        Flux.just(StepEvent.finished(stepId))
                )
                .onErrorResume(error -> Flux.just(
                        StepEvent.error(stepId, error),
                        StepEvent.finished(stepId)
                ));
    }

    private Flux<StepEvent> loop(
            ChatRequest request,
            String stepId,
            List<ToolExchange> previousToolExchanges,
            int round
    ) {
        if (round >= MAX_ROUNDS) {
            return Flux.error(new IllegalStateException(
                    "Chat exceeded the maximum number of orchestration rounds"
            ));
        }

        return decisionService.decide(request, previousToolExchanges, round)
                .flatMapMany(decision -> {
                    if (!decision.requiresTools()) {
                        if (decision.assistantText().isBlank()) {
                            return Flux.just(StepEvent.noReply(stepId));
                        }
                        return Flux.just(StepEvent.assistant(
                                stepId,
                                decision.assistantText()
                        ));
                    }

                    return executeTools(request, decision.toolCalls())
                            .collectList()
                            .flatMapMany(executions -> {
                                List<ToolExchange> allExchanges = new ArrayList<>(
                                        previousToolExchanges
                                );
                                for (ToolExecution execution : executions) {
                                    allExchanges.add(new ToolExchange(
                                            round,
                                            execution.call(),
                                            execution.result()
                                    ));
                                }

                                Flux<StepEvent> toolEvents = Flux.fromIterable(executions)
                                        .concatMap(execution -> Flux.just(
                                                StepEvent.toolCall(stepId, execution.call()),
                                                StepEvent.toolResult(stepId, execution.result())
                                        ));

                                return Flux.concat(
                                        toolEvents,
                                        loop(
                                                request,
                                                stepId,
                                                List.copyOf(allExchanges),
                                                round + 1
                                        )
                                );
                            });
                });
    }

    private Flux<ToolExecution> executeTools(
            ChatRequest request,
            List<ToolCall> calls
    ) {
        return Flux.fromIterable(calls)
                .concatMap(call -> toolRegistry.execute(call, request)
                        .map(result -> new ToolExecution(call, result)));
    }

    private record ToolExecution(ToolCall call, ToolResult result) {
    }
}
