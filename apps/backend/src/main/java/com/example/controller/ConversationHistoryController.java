package com.example.controller;

import com.example.service.ConversationMemoryService;
import com.example.service.dto.ConversationSummary;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 针对前端查询历史记录的只读控制器。
 */
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class ConversationHistoryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final ConversationMemoryService memoryService;

    @Operation(summary = "列出所有用户的会话索引")
    @GetMapping(value = "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<UserConversationIndex> listAll(@RequestParam(value = "userId", required = false) String userId) {
        List<ConversationSummary> summaries = StringUtils.hasText(userId)
                ? memoryService.listConversationSummaries(userId.trim())
                : memoryService.listConversationSummaries();
        return groupByUser(summaries);
    }

    @Operation(summary = "列出指定用户包含的会话")
    @GetMapping(value = "/conversations/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserConversationIndex listUser(@PathVariable("userId") String userId) {
        List<UserConversationIndex> grouped = groupByUser(memoryService.listConversationSummaries(userId));
        if (grouped.isEmpty()) {
            return new UserConversationIndex(userId, List.of());
        }
        return grouped.get(0);
    }

    @Operation(summary = "按 userId/convId 查询历史消息，支持 limit")
    @GetMapping(value = "/conversations/{userId}/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> getConversationMessages(@PathVariable("userId") String userId,
                                                             @PathVariable("conversationId") String conversationId,
                                                             @RequestParam(value = "limit", required = false) Integer limit) {
        int sanitized = limit == null ? DEFAULT_LIMIT : limit;
        if (sanitized <= 0) {
            return memoryService.getHistory(userId, conversationId);
        }
        int bounded = Math.min(sanitized, MAX_LIMIT);
        return memoryService.getContext(userId, conversationId, bounded);
    }

    private List<UserConversationIndex> groupByUser(List<ConversationSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        Map<String, List<ConversationMeta>> grouped = new LinkedHashMap<>();
        for (ConversationSummary summary : summaries) {
            grouped.computeIfAbsent(summary.userId(), key -> new ArrayList<>())
                    .add(new ConversationMeta(summary.conversationId(), summary.messageCount(), summary.lastMessageAt()));
        }
        List<UserConversationIndex> response = new ArrayList<>(grouped.size());
        grouped.forEach((userId, conversations) -> response.add(new UserConversationIndex(userId, conversations)));
        return response;
    }

    public record UserConversationIndex(String userId, List<ConversationMeta> conversations) { }

    public record ConversationMeta(String conversationId, long messageCount, LocalDateTime lastMessageAt) { }
}

