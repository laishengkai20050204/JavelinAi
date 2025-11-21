package com.example.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多模型配置：
 *
 * yml 结构：
 * ai:
 *   multi:
 *     primary-model: glm-agent
 *     models:
 *       glm-agent:
 *         provider: glm-openai
 *         base-url: ...
 *         api-key: ...
 *         model-id: glm-4-plus
 *       gpt-reasoner:
 *         provider: openai
 *         base-url: ...
 *         api-key: ...
 *         model-id: gpt-5.1
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.multi")
public class AiMultiModelProperties {

    /**
     * 当前主模型 profile 名，例如：glm-agent / gpt-main / deepseek-reasoner 等。
     * 后面可以配合 HotSwapChatModel 使用：根据这个名字切换底层 ChatModel。
     */
    @NotBlank(message = "ai.multi.primary-model 不能为空")
    private String primaryModel;

    /**
     * 所有可用模型的配置。
     * key = profile 名（如 glm-agent、gpt-reasoner）
     */
    @Valid
    private Map<String, ModelProfile> models = new LinkedHashMap<>();

    /**
     * 获取必存在的 profile，不存在时抛出异常，避免到处判空。
     */
    public ModelProfile requireProfile(String name) {
        ModelProfile profile = models.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown AI model profile: " + name);
        }
        return profile;
    }

    @Data
    public static class ModelProfile {

        /**
         * 提供方/类型标签：
         *   - openai
         *   - openai-compatible
         *   - glm-openai
         *   - qwen
         *   - dashscope
         *   - deepseek
         *   - gemini
         *   - ollama
         * 等等，完全由你在工厂里解释。
         */
        @NotBlank(message = "provider 不能为空")
        private String provider;

        /**
         * 模型请求的 baseUrl。
         * 对 openai 官方可以省略（走默认），对兼容接口/国内厂商通常需要配置。
         */
        private String baseUrl;

        /**
         * API Key（如果走统一代理，也可以为空，后面用别的方式注入）。
         */
        private String apiKey;

        /**
         * 模型 ID，例如：gpt-5.1, glm-4-plus, qwen-coder, deepseek-reasoner 等。
         */
        @NotBlank(message = "model-id 不能为空")
        private String modelId;

        /**
         * 默认温度（可选，不配就用模型层默认）。
         */
        private Double temperature;

        /**
         * 默认最大 tokens（可选）。
         */
        private Integer maxTokens;

        /**
         * 单次请求超时时间（毫秒，可选）。
         */
        private Integer timeoutMs;

        /**
         * （可选）是否允许工具调用，给以后“这个 profile 只用来纯推理/写代码”做准备。
         */
        private Boolean toolsEnabled;

        /**
         * （可选）该模型使用的历史工具上下文渲染模式。
         * 不配置则使用全局 effectiveProps.toolContextRenderMode。
         *
         * 取值示例：
         *  - ALL_TOOL
         *  - ALL_SUMMARY
         *  - CURRENT_TOOL_HISTORY_SUMMARY
         */
        private ToolContextRenderMode toolContextRenderMode;
    }
}
