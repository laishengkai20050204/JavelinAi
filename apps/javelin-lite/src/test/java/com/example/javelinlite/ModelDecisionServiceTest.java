package com.example.javelinlite;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.model.ChatGateway;
import com.example.javelinlite.model.ModelProperties;
import com.example.javelinlite.orchestration.Decision;
import com.example.javelinlite.orchestration.ModelDecisionService;
import com.example.javelinlite.orchestration.ToolExchange;
import com.example.javelinlite.tools.AiToolRegistry;
import com.example.javelinlite.tools.GetServerTimeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDecisionServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesToolCallsAndReplaysTheOpenAiToolMessageSequence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode toolCallResponse = mapper.readTree("""
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-time-1",
                        "type": "function",
                        "function": {
                          "name": "get_server_time",
                          "arguments": "{\"zoneId\":\"Asia/Shanghai\"}"
                        }
                      }]
                    }
                  }]
                }
                """);
        JsonNode finalResponse = mapper.readTree("""
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "现在是测试时间。"
                    }
                  }]
                }
                """);

        List<Map<String, Object>> capturedPayloads = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatGateway gateway = payload -> {
            capturedPayloads.add(new LinkedHashMap<>(payload));
            return Mono.just(calls.getAndIncrement() == 0
                    ? toolCallResponse
                    : finalResponse);
        };

        AiToolRegistry registry = new AiToolRegistry(List.of(new GetServerTimeTool()));
        ModelProperties properties = new ModelProperties(
                true,
                "https://example.invalid/v1",
                "",
                "test-model",
                0.8d,
                Duration.ofSeconds(10)
        );
        ModelDecisionService service = new ModelDecisionService(
                gateway,
                registry,
                mapper,
                properties
        );

        ChatRequest request = new ChatRequest(
                null,
                "user-1",
                "person-1:user-1",
                "现在几点？",
                "auto",
                "你是一个数字人物。",
                Map.of("personId", "person-1")
        );

        Decision firstDecision = service.decide(request, List.of(), 0).block();
        assertTrue(firstDecision != null && firstDecision.requiresTools());
        ToolCall toolCall = firstDecision.toolCalls().get(0);
        assertEquals("get_server_time", toolCall.name());

        ToolResult result = ToolResult.success(
                toolCall,
                Map.of("formatted", "2026-07-21 10:00:00 Asia/Shanghai")
        );
        Decision secondDecision = service.decide(
                request,
                List.of(new ToolExchange(0, toolCall, result)),
                1
        ).block();

        assertTrue(secondDecision != null);
        assertFalse(secondDecision.requiresTools());
        assertEquals("现在是测试时间。", secondDecision.assistantText());

        Map<String, Object> secondPayload = capturedPayloads.get(1);
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) secondPayload.get("messages");
        assertEquals(List.of("system", "user", "assistant", "tool"),
                messages.stream().map(message -> String.valueOf(message.get("role"))).toList());

        Map<String, Object> assistantMessage = messages.get(2);
        List<Map<String, Object>> replayedToolCalls =
                (List<Map<String, Object>>) assistantMessage.get("tool_calls");
        assertEquals("call-time-1", replayedToolCalls.get(0).get("id"));
        assertEquals("call-time-1", messages.get(3).get("tool_call_id"));

        List<Map<String, Object>> toolDefinitions =
                (List<Map<String, Object>>) secondPayload.get("tools");
        assertEquals(1, toolDefinitions.size());
    }
}
