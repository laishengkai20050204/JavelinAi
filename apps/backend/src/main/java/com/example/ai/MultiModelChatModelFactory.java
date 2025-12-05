package com.example.ai;

import com.example.ai.impl.DeepseekReasonerChatModel;
import com.example.config.AiMultiModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * 根据 AiMultiModelProperties.ModelProfile 创建对应的 ChatModel。
 *
 * provider 约定：
 *  - openai / openai-compatible / glm-openai / gemini → OpenAI 兼容协议（OpenAiApi + OpenAiChatModel）
 *  - ollama → 复用现有的 OllamaChatModel bean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiModelChatModelFactory {

    private final ObjectProvider<OllamaChatModel> ollamaChatModelProvider;
    private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;
    private final ObjectProvider<OpenAiApi> openAiApiProvider;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ChatModel create(String name, com.example.runtime.RuntimeConfig.ModelProfileDto dto) {
        if (dto == null || dto.getProvider() == null) {
            throw new IllegalArgumentException("Runtime profile is missing provider: " + name);
        }
        AiMultiModelProperties.ModelProfile mp = new AiMultiModelProperties.ModelProfile();
        mp.setProvider(dto.getProvider());
        mp.setBaseUrl(dto.getBaseUrl());
        mp.setApiKey(dto.getApiKey());
        mp.setModelId(dto.getModelId());
        mp.setTemperature(dto.getTemperature());
        mp.setMaxTokens(dto.getMaxTokens());
        mp.setTimeoutMs(dto.getTimeoutMs());
        mp.setToolsEnabled(dto.getToolsEnabled());
        if (dto.getToolContextRenderMode() != null) {
            mp.setToolContextRenderMode(com.example.config.ToolContextRenderMode.valueOf(dto.getToolContextRenderMode()));
        }
        return create(mp);
    }

    public ChatModel create(AiMultiModelProperties.ModelProfile profile) {
        String provider = profile.getProvider();

        return switch (provider) {
            case "openai", "openai-compatible", "glm-openai", "gemini" ->
                    buildOpenAiCompatibleModel(profile);
            case "deepseek" ->
                    buildDeepseekModel(profile);
            case "ollama" ->
                    buildOllamaModel(profile);
            default ->
                    throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    private ChatModel buildOpenAiCompatibleModel(AiMultiModelProperties.ModelProfile profile) {
        String baseUrl = Optional.ofNullable(profile.getBaseUrl())
                .filter(StringUtils::hasText)
                .orElse("https://api.openai.com/v1");

        String apiKey = Optional.ofNullable(profile.getApiKey())
                .orElseThrow(() -> new IllegalStateException(
                        "apiKey must be set for provider=" + profile.getProvider()));

        String modelId = profile.getModelId();
        Double temperature = profile.getTemperature();

        OpenAiApi baseApi = openAiApiProvider.getIfAvailable();
        if (baseApi == null) {
            throw new IllegalStateException("OpenAiApi bean is required to build profile=" + modelId);
        }
        OpenAiApi api = baseApi.mutate()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(modelId);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        OpenAiChatOptions options = builder.build();

        OpenAiChatModel template = openAiChatModelProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("OpenAiChatModel bean is required to build profile=" + modelId);
        }

        OpenAiChatModel model = template.mutate()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        log.info("[MultiModelChatModelFactory] built OpenAI-compatible model provider={} baseUrl={} modelId={}",
                profile.getProvider(), baseUrl, modelId);
        return model;
    }

    private ChatModel buildDeepseekModel(AiMultiModelProperties.ModelProfile profile) {
        String modelId = profile.getModelId();
        if ("deepseek-reasoner".equalsIgnoreCase(modelId)) {
            return buildDeepseekReasonerModel(profile);
        }
        // Other DeepSeek models can still use the OpenAI-compatible path.
        return buildOpenAiCompatibleModel(profile);
    }

    private ChatModel buildDeepseekReasonerModel(AiMultiModelProperties.ModelProfile profile) {
        String baseUrl = Optional.ofNullable(profile.getBaseUrl())
                .filter(StringUtils::hasText)
                .orElse("https://api.deepseek.com");

        String apiKey = Optional.ofNullable(profile.getApiKey())
                .orElseThrow(() -> new IllegalStateException(
                        "apiKey must be set for provider=deepseek"));

        String modelId = Optional.ofNullable(profile.getModelId())
                .filter(StringUtils::hasText)
                .orElse("deepseek-reasoner");

        Double temperature = profile.getTemperature();
        Boolean thinkEnabled = profile.getThinkEnabled();
        String thinkLevel = profile.getThinkLevel();

        WebClient client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        log.info("[MultiModelChatModelFactory] built DeepSeek Reasoner model baseUrl={} modelId={}",
                baseUrl, modelId);

        return new DeepseekReasonerChatModel(client, mapper, modelId, temperature, thinkEnabled, thinkLevel);
    }

    private ChatModel buildOllamaModel(AiMultiModelProperties.ModelProfile profile) {
        OllamaChatModel ollama = ollamaChatModelProvider.getIfAvailable();
        if (ollama == null) {
            throw new IllegalStateException("OllamaChatModel not available but provider=ollama is configured");
        }
        log.info("[MultiModelChatModelFactory] using existing OllamaChatModel profile={} modelId={}",
                profile, profile.getModelId());
        return ollama;
    }
}
