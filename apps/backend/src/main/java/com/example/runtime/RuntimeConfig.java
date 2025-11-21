package com.example.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/** Runtime hot-reloadable config (extend as needed). */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeConfig {
    /** Preferred model profile (ai.multi.models key); null falls back to ai.multi.primary-model. */
    private String profile;

    /** Tool loop upper bound (Guardrails). */
    @Builder.Default
    private Integer toolsMaxLoops = 10;

    /** Optional tool toggles: key=tool name, value=true enables. */
    @Builder.Default
    private Map<String, Boolean> toolToggles = Map.of();

    /** Max messages to fetch for memory/context; null falls back to static config. */
    private Integer memoryMaxMessages;
}

