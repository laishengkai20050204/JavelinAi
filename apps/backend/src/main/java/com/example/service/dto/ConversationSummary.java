package com.example.service.dto;

import java.time.LocalDateTime;

/**
 * Lightweight summary describing a persisted conversation.
 */
public record ConversationSummary(
        String userId,
        String conversationId,
        long messageCount,
        LocalDateTime lastMessageAt
) { }

