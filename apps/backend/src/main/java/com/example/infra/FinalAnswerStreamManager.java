package com.example.infra;

import com.example.ai.SpringAiChatGateway;
import com.example.config.EffectiveProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalAnswerStreamManager {

    private final SpringAiChatGateway gateway;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper mapper;

    static final class Holder {
        final Sinks.Many<String> chunkSink = Sinks.many().multicast().directBestEffort();
        final StringBuilder buf = new StringBuilder(4096);
        volatile Disposable upstream;
        final AtomicBoolean started = new AtomicBoolean(false);
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    /** 启动一条 provider 流（幂等） */
    public void start(String stepId, Map<String, Object> payload) {
        Holder h = holders.computeIfAbsent(stepId, k -> new Holder());
        if (!h.started.compareAndSet(false, true)) {        // ← 并发安全防重复
            log.debug("[FASM] step={} already started", stepId);
            return;
        }

        Flux<String> src = gateway.stream(payload, effectiveProps.mode())
                .doOnSubscribe(s -> log.debug("[FASM] stream start step={}", stepId))
                .doOnNext(chunk -> {
                    h.chunkSink.tryEmitNext(chunk);
                    try {
                        JsonNode root = mapper.readTree(chunk);
                        JsonNode delta = root.path("choices").path(0).path("delta");
                        if (delta != null) {
                            String part = delta.path("content").asText(null);
                            if (part != null && !part.isEmpty()) h.buf.append(part);
                        }
                    } catch (Exception ignore) {}
                })
                .doOnError(e -> {
                    log.warn("[FASM] stream error step={} err={}", stepId, e.toString());
                    h.chunkSink.tryEmitError(e);
                })
                .doOnComplete(() -> {
                    log.debug("[FASM] stream complete step={}", stepId);
                    h.chunkSink.tryEmitComplete();
                    holders.remove(stepId);                      // ← 完成后释放，避免复用到“完成的 sink”
                });

        h.upstream = src.subscribe();
    }

    /** 为 StepSseHub 提供的 SSE 源，把 JSON chunk 包成 SSE 事件（event=message） */
    public Flux<ServerSentEvent<String>> sse(String stepId, String modelName) {
        Holder h = holders.computeIfAbsent(stepId, k -> new Holder());
        return h.chunkSink.asFlux()
                .map(json -> ServerSentEvent.<String>builder(json).event("message").build());
        // [DONE] 的追加由 StepSseHub.forward(...) 负责
    }

    /** 等待整段文本（从 delta.content 拼起来） */
    public Mono<String> awaitFinalText(String stepId, Duration timeout) {
        Holder h = holders.computeIfAbsent(stepId, k -> new Holder());
        return h.chunkSink.asFlux()
                .then(Mono.fromCallable(() -> h.buf.toString()))
                .timeout(timeout);
    }

    /** 可选：手动清理（通常由外部 finished 时做） */
    public void clear(String stepId) {
        Holder h = holders.remove(stepId);
        if (h != null) {
            if (h.upstream != null) h.upstream.dispose();
            h.chunkSink.tryEmitComplete();                   // ← 让等待者立即收尾
            h.started.set(false);
        }
    }
}
