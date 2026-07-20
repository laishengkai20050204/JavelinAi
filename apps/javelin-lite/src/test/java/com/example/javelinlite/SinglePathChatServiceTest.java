package com.example.javelinlite;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.StepEvent;
import com.example.javelinlite.orchestration.SinglePathChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SinglePathChatServiceTest {

    @Autowired
    private SinglePathChatService chatService;

    @Test
    void executesTimeToolAndReturnsAssistantEvent() {
        ChatRequest request = new ChatRequest(
                null,
                "user-1",
                "person-1:user-1",
                "现在几点？",
                "auto",
                "",
                Map.of("personId", "person-1")
        );

        List<StepEvent> events = chatService.run(request)
                .collectList()
                .block();

        assertTrue(events != null && !events.isEmpty());
        assertEquals("started", events.get(0).type());
        assertTrue(events.stream().anyMatch(event -> "toolCall".equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> "toolResult".equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> "assistant".equals(event.type())));
        assertEquals("finished", events.get(events.size() - 1).type());
    }
}
