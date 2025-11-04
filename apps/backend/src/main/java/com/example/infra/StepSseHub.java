package com.example.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StepSseHub {

    static final class Hub {
        final Sinks.Many<ServerSentEvent<String>> sink =
                Sinks.many().multicast().directBestEffort(); // 不回放历史；更像“实时终端”
        volatile Disposable upstream; // 来自 FinalAnswerStreamManager.sse(...) 的订阅
    }

    private final Map<String, Hub> hubs = new ConcurrentHashMap<>();

    private Hub hub(String stepId) {
        return hubs.computeIfAbsent(stepId, k -> new Hub());
    }

    /** 控制事件（decision/tools/clientCalls/finished/...）镜像 */
    public void emit(String stepId, String event, String json) {
        var h = hub(stepId);
        h.sink.tryEmitNext(ServerSentEvent.<String>builder(json).event(event).build());
    }

    /** 把 token 流（event 固定 "message"）转发到同一个 step 的 SSE 管道 */
    public void forward(String stepId, Flux<ServerSentEvent<String>> tokenFlux) {
        var h = hub(stepId);
        // 避免重复挂多个 upstream
        if (h.upstream != null) return;

        h.upstream = tokenFlux
                .doOnNext(ev -> h.sink.tryEmitNext(ev)) // 逐 token 转发
                .doOnComplete(() -> {
                    // 附加 [DONE]，与 OpenAI 习惯一致
                    h.sink.tryEmitNext(ServerSentEvent.<String>builder("[DONE]").event("message").build());
                })
                .doOnError(e -> {
                    String msg = "{\"error\":\"" + e.getMessage().replace("\"","\\\"") + "\"}";
                    h.sink.tryEmitNext(ServerSentEvent.<String>builder(msg).event("message").build());
                })
                .subscribe();
    }

    /** SSE Controller 用这个返回给客户端 */
    public Flux<ServerSentEvent<String>> sse(String stepId) {
        return hub(stepId).sink.asFlux();
    }

    /** 在真正结束 step 时调用（通常在 SinglePathChatService 的 finished 分支） */
    public void complete(String stepId) {
        var h = hubs.remove(stepId);
        if (h != null) {
            if (h.upstream != null) h.upstream.dispose();
            h.sink.tryEmitComplete();
        }
    }
}
