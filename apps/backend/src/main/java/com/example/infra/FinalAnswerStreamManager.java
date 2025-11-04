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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 provider 流（OpenAI 风格 delta JSON）：
 *  - 广播为 SSE（event="message"）
 *  - 同步聚合：content（纯文本） + tool_calls（支持 arguments 增量拼接）
 * 提供两种等待：
 *  - awaitFinalText(stepId, timeout)
 *  - awaitAggregated(stepId, timeout) -> {content, toolCalls}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalAnswerStreamManager {

    private final SpringAiChatGateway gateway;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper mapper;

    /** 聚合后的结构 */
    public static final class Aggregated {
        public final String content;
        public final List<Map<String,Object>> toolCalls; // OpenAI 样式：{id,type=function,function:{name,arguments}}
        public Aggregated(String content, List<Map<String,Object>> toolCalls) {
            this.content = content;
            this.toolCalls = toolCalls;
        }
    }

    /** 累计 tool_calls 的内部结构 */
    static final class ToolAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder(256);
    }

    static final class Holder {
        final Sinks.Many<String> chunkSink = Sinks.many().multicast().directBestEffort();
        final StringBuilder buf = new StringBuilder(1024);
        final Map<Integer, ToolAcc> tools = new LinkedHashMap<>(); // index -> acc
        volatile Disposable upstream;
        volatile boolean started = false;
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    /** 启动一条 provider 流（按 stepId 幂等） */
    public void start(String streamId, Map<String, Object> payload) {
        Holder h = holders.computeIfAbsent(streamId, k -> new Holder());
        if (h.started) {
            log.debug("[FASM] stream={} already started", streamId);
            return;
        }
        h.started = true;

        Flux<String> src = gateway.stream(payload, effectiveProps.mode())
                .doOnSubscribe(s -> log.debug("[FASM] stream start id={}", streamId))
                .doOnNext(chunk -> {
                    // 1) 广播
                    h.chunkSink.tryEmitNext(chunk);
                    // 2) 聚合
                    try {
                        JsonNode root = mapper.readTree(chunk);
                        JsonNode delta = root.path("choices").path(0).path("delta");

                        // content 累计
                        String part = delta.path("content").asText(null);
                        if (part != null && !part.isEmpty()) {
                            h.buf.append(part);
                        }

                        // tool_calls 增量
                        JsonNode tcs = delta.path("tool_calls");
                        if (tcs.isArray()) {
                            for (JsonNode tc : tcs) {
                                int idx = tc.path("index").asInt(0);
                                ToolAcc acc = h.tools.computeIfAbsent(idx, i -> new ToolAcc());
                                String id = tc.path("id").asText(null);
                                if (id != null && !id.isBlank()) acc.id = id;

                                JsonNode fn = tc.path("function");
                                if (fn.isObject()) {
                                    String fnName = fn.path("name").asText(null);
                                    if (fnName != null && !fnName.isBlank()) acc.name = fnName;

                                    String argsPart = fn.path("arguments").asText(null);
                                    if (argsPart != null && !argsPart.isEmpty()) acc.args.append(argsPart);
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                })
                .doOnError(e -> {
                    log.warn("[FASM] stream error id={} err={}", streamId, e.toString());
                    h.chunkSink.tryEmitError(e);
                })
                .doOnComplete(() -> {
                    log.debug("[FASM] stream complete id={}", streamId);
                    h.chunkSink.tryEmitComplete();
                });

        h.upstream = src.subscribe();
    }

    /** 把 JSON chunk 包一层 SSE 事件（event="message"） */
    public Flux<ServerSentEvent<String>> sse(String streamId, String modelName) {
        Holder h = holders.computeIfAbsent(streamId, k -> new Holder());
        return h.chunkSink.asFlux()
                .map(json -> ServerSentEvent.<String>builder(json).event("message").build());
    }

    /** 等待纯文本（仅用于“最终续写”场景） */
    public Mono<String> awaitFinalText(String streamId, Duration timeout) {
        Holder h = holders.computeIfAbsent(streamId, k -> new Holder());
        return h.chunkSink.asFlux()
                .then(Mono.fromCallable(() -> h.buf.toString()))
                .timeout(timeout);
    }

    /** 等待聚合结果（决策流：content + tool_calls） */
    public Mono<Aggregated> awaitAggregated(String streamId, Duration timeout) {
        Holder h = holders.computeIfAbsent(streamId, k -> new Holder());
        return h.chunkSink.asFlux()
                .then(Mono.fromCallable(() -> {
                    List<Map<String,Object>> toolCalls = new ArrayList<>();
                    for (var e : h.tools.entrySet()) {
                        ToolAcc a = e.getValue();
                        if (a == null) continue;
                        Map<String,Object> fn = new LinkedHashMap<>();
                        fn.put("name", a.name == null ? "" : a.name);
                        fn.put("arguments", a.args.toString());
                        Map<String,Object> item = new LinkedHashMap<>();
                        if (a.id != null) item.put("id", a.id);
                        item.put("type", "function");
                        item.put("function", fn);
                        toolCalls.add(item);
                    }
                    return new Aggregated(h.buf.toString(), toolCalls);
                }))
                .timeout(timeout);
    }

    /** 手动清理 */
    public void clear(String streamId) {
        Holder h = holders.remove(streamId);
        if (h != null && h.upstream != null) h.upstream.dispose();
    }
}
