package com.example.javelinlite.controller;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.StepEvent;
import com.example.javelinlite.orchestration.SinglePathChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public final class OrchestratedChatController {

    private final SinglePathChatService chatService;
    private final ObjectMapper objectMapper;

    public OrchestratedChatController(
            SinglePathChatService chatService,
            ObjectMapper objectMapper
    ) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    /**
     * Unified structured Java entry point. Digital Person should call this method directly
     * when it runs in the same Spring container.
     */
    public Flux<StepEvent> chat(ChatRequest request) {
        return chatService.run(request);
    }

    /**
     * Optional HTTP compatibility wrapper. It delegates to the same structured entry point.
     */
    @PostMapping(
            value = "/v3/chat/step/ndjson",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson"
    )
    public Flux<String> chatNdjson(@RequestBody ChatRequest request) {
        return chat(request).map(this::toNdjsonLine);
    }

    private String toNdjsonLine(StepEvent event) {
        try {
            return objectMapper.writeValueAsString(event) + "\n";
        } catch (JsonProcessingException error) {
            return "{\"type\":\"error\",\"data\":{\"message\":\"serialize failed\"}}\n";
        }
    }
}
