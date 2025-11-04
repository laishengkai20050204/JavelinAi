package com.example.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 每个 step 维护一条多播 SSE 管道：
 *  - sse(stepId):   返回该 step 的 SSE 流（给 Controller 用）
 *  - emit(...):     发送自定义事件（event + JSON 字符串）
 *  - forward(...):  转发上游 SSE（例如 LLM token 流）；上游完成时补 [DONE]
 *  - complete(...): 结束该 step 并清理资源
 *
 * 内置：
 *  - 心跳 ping（保持代理不闲断）
 *  - TTL 清理（清掉久未活动的 step 管道）
 */
@Slf4j
@Component
public class StepSseHub {

    /** 心跳间隔（建议 15~30s） */
    @Value("${sse.heartbeat-every:PT20S}")
    private Duration heartbeatEvery;

    /** 无活动 TTL（建议 5~10 分钟） */
    @Value("${sse.step-ttl:PT10M}")
    private Duration stepTtl;

    /** 清道夫扫描频率 */
    @Value("${sse.janitor-every:PT60S}")
    private Duration janitorEvery;

    /** stepId -> 管道 */
    private final Map<String, StepChannel> channels = new ConcurrentHashMap<>();

    /** 周期清理任务 */
    private Disposable janitor;

    // ---------- 生命周期 ----------
    @PostConstruct
    public void init() {
        this.janitor = Flux.interval(this.janitorEvery, Schedulers.boundedElastic())
                .doOnNext(t -> cleanup())
                .onErrorContinue((e, o) -> log.warn("[SSE] janitor error: {}", e.toString()))
                .subscribe();
        log.info("[SSE] StepSseHub started. heartbeatEvery={}, stepTtl={}, janitorEvery={}",
                heartbeatEvery, stepTtl, janitorEvery);
    }

    @PreDestroy
    public void shutdown() {
        if (janitor != null) {
            janitor.dispose();
            janitor = null;
        }
        // 关闭所有通道
        channels.values().forEach(StepChannel::complete);
        channels.clear();
        log.info("[SSE] StepSseHub stopped.");
    }

    // ---------- 对外 API ----------
    /** 订阅某 step 的 SSE。允许在 step 开始前订阅，事件会到来时再推送。 */
    public Flux<ServerSentEvent<String>> sse(String stepId) {
        StepChannel ch = ensure(stepId);
        ch.touch();
        return ch.flux();
    }

    /** 发一个自定义事件（event 名 + JSON 字符串载荷）。 */
    public void emit(String stepId, String event, String jsonPayload) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(jsonPayload, "jsonPayload");
        StepChannel ch = ensure(stepId);
        ch.touch();
        ch.emit(ServerSentEvent.<String>builder(jsonPayload).event(event).build());
    }

    /**
     * 把上游 SSE（例如 LLM token 流）转发到该 step。
     * 注意：保存一个订阅句柄，complete(stepId) 时统一释放。
     * 上游完成时会补一个 OpenAI 兼容的 [DONE]（event=message），但不强行 complete。
     */
    public void forward(String stepId, Flux<ServerSentEvent<String>> upstream) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(upstream, "upstream");
        StepChannel ch = ensure(stepId);
        ch.touch();
        Disposable d = upstream
                .doOnNext(ch::emit)
                .doOnError(err -> ch.emit(ServerSentEvent.<String>builder(
                                "{\"error\":\"upstream failed\",\"message\":\"" + safe(err.getMessage()) + "\"}")
                        .event("error").build()))
                .doOnComplete(() -> {
                    // 上游结束时补一个 [DONE]，但不关通道；业务完成后会在 finished 里调用 complete(stepId)
                    ch.emit(ServerSentEvent.<String>builder("[DONE]").event("message").build());
                    ch.touch();
                })
                .subscribe();
        ch.addUpstream(d);
    }

    /** 结束该 step 的 SSE，并清理资源。 */
    public void complete(String stepId) {
        StepChannel ch = channels.remove(stepId);
        if (ch != null) {
            ch.complete();
        }
    }

    // ---------- 内部 ----------
    private StepChannel ensure(String stepId) {
        return channels.computeIfAbsent(stepId, id -> {
            StepChannel ch = new StepChannel(id, heartbeatEvery);
            ch.startHeartbeat();
            return ch;
        });
    }

    private void cleanup() {
        Instant now = Instant.now();
        List<String> toClose = channels.entrySet().stream()
                .filter(e -> Duration.between(e.getValue().lastActive, now).compareTo(stepTtl) > 0)
                .map(Map.Entry::getKey)
                .toList();
        for (String id : toClose) {
            StepChannel ch = channels.remove(id);
            if (ch != null) {
                log.debug("[SSE] auto-complete stepId={} (TTL reached)", id);
                ch.complete();
            }
        }
    }

    private static String safe(String s) { return (s == null ? "" : s); }

    // ---------- 每个 step 的管道 ----------
    private static final class StepChannel {
        final String stepId;
        final Sinks.Many<ServerSentEvent<String>> sink;
        final List<Disposable> upstreams = new CopyOnWriteArrayList<>();
        final Duration heartbeatEvery;

        volatile Instant lastActive = Instant.now();
        volatile Disposable heartbeatDisp;

        StepChannel(String stepId, Duration heartbeatEvery) {
            this.stepId = stepId;
            this.heartbeatEvery = heartbeatEvery;
            // 多播，所有订阅者都能收到；有限缓冲避免背压丢失
            this.sink = Sinks.many().multicast().onBackpressureBuffer(1024, false);
        }

        Flux<ServerSentEvent<String>> flux() {
            return sink.asFlux()
                    .doOnSubscribe(s -> touch())
                    .doOnTerminate(this::touch)
                    .doFinally(sig -> touch());
        }

        void emit(ServerSentEvent<String> evt) {
            touch();
            Sinks.EmitResult r = sink.tryEmitNext(evt);
            if (r.isFailure()) {
                // 例如 FAIL_ZERO_SUBSCRIBER：下游都断了，不必报错
            }
        }

        void addUpstream(Disposable d) { upstreams.add(d); }

        void startHeartbeat() {
            stopHeartbeat();
            heartbeatDisp = Flux.interval(heartbeatEvery, Schedulers.boundedElastic())
                    .map(tick -> ServerSentEvent.<String>builder("{\"ts\":\"" + Instant.now() + "\"}")
                            .event("ping").build())
                    .subscribe(this::emit, e -> {
                        // 心跳失败不致命，写一条 error 事件即可
                        emit(ServerSentEvent.<String>builder(
                                        "{\"error\":\"heartbeat failed\",\"message\":\"" + safe(e.getMessage()) + "\"}")
                                .event("error").build());
                    });
        }

        void stopHeartbeat() {
            if (heartbeatDisp != null) {
                heartbeatDisp.dispose();
                heartbeatDisp = null;
            }
        }

        void complete() {
            stopHeartbeat();
            upstreams.forEach(Disposable::dispose);
            upstreams.clear();
            sink.tryEmitComplete();
        }

        void touch() { lastActive = Instant.now(); }
    }
}
