package com.example.ai;

import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyChatModelFactory {

    private final ObjectProvider<OllamaChatModel> ollamaProvider;
    private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;
    private final ObjectProvider<OpenAiApi> openAiApiProvider;

    public ChatModel buildFromEffectiveProps(EffectiveProps props) {
        if (props.mode() == AiProperties.Mode.OLLAMA) {
            OllamaChatModel ollama = ollamaProvider.getIfAvailable();
            if (ollama == null) {
                throw new IllegalStateException("ai.mode=OLLAMA but no OllamaChatModel bean");
            }
            return ollama;
        }

        String baseUrl = Objects.requireNonNullElse(props.baseUrl(), "https://api.openai.com/v1");
        String apiKey = Objects.requireNonNullElse(props.apiKey(), "");
        String modelId = Objects.requireNonNullElse(props.model(), "gpt-4o-mini");

        OpenAiApi baseApi = openAiApiProvider.getIfAvailable();
        if (baseApi == null) {
            throw new IllegalStateException("OpenAiApi bean is required for OpenAI mode");
        }
        OpenAiApi api = baseApi.mutate()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatModel template = openAiChatModelProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("OpenAiChatModel bean is required for OpenAI mode");
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelId)
                .build();

        OpenAiChatModel model = template.mutate()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        log.info("[LegacyChatModelFactory] built model from EffectiveProps: mode={} baseUrl={} modelId={}",
                props.mode(), baseUrl, modelId);

        return model;
    }
}
