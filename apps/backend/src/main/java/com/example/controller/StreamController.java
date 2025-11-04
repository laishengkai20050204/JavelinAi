package com.example.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/ai/v2/chat")
@RequiredArgsConstructor
public class StreamController {

    private final com.example.infra.StepSseHub sseHub;

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sse(@RequestParam("stepId") String stepId) {
        log.info("SSE connect, stepId = {}", stepId);
        // 不再依赖 streamMgr.has(...)；Hub 始终可连，事件会按流程推送
        return sseHub.sse(stepId);
    }
}
