package com.example.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/** Runtime hot-reloadable config (extend as needed). */
@Data
@Builder(toBuilder = true)
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

    /**
     * Runtime-defined model profiles (overrides static ai.multi.models when present).
     * key = profile name, value = definition (provider, baseUrl, apiKey, modelId...).
     */
    @Builder.Default
    private Map<String, ModelProfileDto> profiles = Map.of();

    @Data
    @Builder(toBuilder = true)
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelProfileDto {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String modelId;
        private Double temperature;
        private Integer maxTokens;
        private Integer timeoutMs;
        private Boolean toolsEnabled;
        private String toolContextRenderMode;
    }
}

