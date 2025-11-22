package com.example.config;

import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EffectiveProps {
    private final AiProperties statics;
    private final RuntimeConfigService runtime;
    private final Environment env;
    public Boolean streamDecision() { return Boolean.TRUE.equals(this.streamDecisionFlag); }
    private Boolean streamDecisionFlag = true;

    private ToolContextRenderMode toolContextRenderMode = ToolContextRenderMode.ALL_SUMMARY;

    public ToolContextRenderMode toolContextRenderMode() {
        return toolContextRenderMode;
    }

    private RuntimeConfig rc() { return runtime.view(); }

    // === 模式 ===
    public AiProperties.Mode mode() {
        // 仅使用静态配置；不再支持 runtime 覆盖
        return (statics.getCompatibility() != null) ? statics.getMode() : AiProperties.Mode.OPENAI;
    }

    // 备用：给网关内部把“入参 mode”与运行时做合并
    public AiProperties.Mode modeOr(AiProperties.Mode fallback) {
        return (fallback != null) ? fallback : mode();
    }

    /** profile 运行时覆盖（runtime.profile），否则走提供的 fallback */
    public String profileOr(String fallback) {
        var r = rc();
        if (r != null && StringUtils.hasText(r.getProfile())) {
            return r.getProfile().trim();
        }
        return fallback;
    }

    /** runtime profiles map (may be empty). */
    public Map<String, RuntimeConfig.ModelProfileDto> runtimeProfiles() {
        var r = rc();
        return r != null && r.getProfiles() != null ? r.getProfiles() : Map.of();
    }

    // === 供业务层调用的“最终值” ===

    public String model() {
        // 不再支持 runtime.model；仅用静态配置
        return statics.getModel();
    }

    public String baseUrl() {
        if (statics.getBaseUrl() != null && !statics.getBaseUrl().isBlank()) return statics.getBaseUrl();
        // Fallback to Spring AI configured base-url for current mode
        AiProperties.Mode m = mode();
        String key = (m == AiProperties.Mode.OPENAI) ? "spring.ai.openai.base-url" : "spring.ai.ollama.base-url";
        String v = env.getProperty(key);
        return (StringUtils.hasText(v)) ? v : null;
    }

    public String apiKey() {
        String key = env.getProperty("spring.ai.openai.api-key");
        if (!StringUtils.hasText(key) || "dummy".equalsIgnoreCase(key)) {
            key = env.getProperty("OPENAI_API_KEY");
        }
        return StringUtils.hasText(key) ? key : null;
    }

    public int toolsMaxLoops() {
        var r = rc();
        if (r != null && r.getToolsMaxLoops() != null && r.getToolsMaxLoops() > 0) return r.getToolsMaxLoops();
        return (statics.getTools() != null ? statics.getTools().getMaxLoops() : 10);
    }

    public Map<String, Boolean> toolToggles() {
        var r = rc();
        return (r != null && r.getToolToggles() != null) ? r.getToolToggles() : Map.of();
    }

    /**
     * Effective memory window size (message count).
     * Prefers runtime overrides; otherwise falls back to ai.memory.maxMessages (default 100).
     */
    public int memoryMaxMessages() {
        var r = rc();
        if (r != null && r.getMemoryMaxMessages() != null && r.getMemoryMaxMessages() > 0) {
            return r.getMemoryMaxMessages();
        }
        int fallback = (statics.getMemory() != null ? statics.getMemory().getMaxMessages() : 100);
        return Math.max(1, fallback);
    }

    public Long clientTimeoutMs() {
        // runtime 不再覆盖
        return (statics.getClient() != null ? statics.getClient().getTimeoutMs() : null);
    }

    public Long streamTimeoutMs() {
        return (statics.getClient() != null ? statics.getClient().getStreamTimeoutMs() : null);
    }

    public void setStreamDecision(Boolean streamDecisionFlag) {
        this.streamDecisionFlag = streamDecisionFlag;
    }

    public void setToolContextRenderMode(ToolContextRenderMode toolContextRenderMode) {
        this.toolContextRenderMode = toolContextRenderMode;
    }
}
