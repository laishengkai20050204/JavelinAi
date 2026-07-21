package com.example.javelinlite.model;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Provider-neutral model gateway used by the lite orchestration layer.
 */
@FunctionalInterface
public interface ChatGateway {

    Mono<JsonNode> call(Map<String, Object> payload);
}
