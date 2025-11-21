package com.example.ai;

import com.example.config.AiMultiModelProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 简化版 HotSwapChatModel：
 *  - 只基于 ai.multi.primary-model + models 做切换
 *  - 暂时不监听运行时事件，后面有需要再加
 */
@Slf4j
@Component
@org.springframework.context.annotation.Primary
@RequiredArgsConstructor
public class HotSwapChatModel implements ChatModel {

    private final AiMultiModelProperties multiProps;
    private final MultiModelChatModelFactory chatModelFactory;

    private final AtomicReference<ChatModel> delegate = new AtomicReference<>();

    @PostConstruct
    public void init() {
        reloadFromMultiConfig();
    }

    /**
     * 将当前 delegate 切换为 ai.multi.primary-model 对应的模型。
     * 以后如果你有运行时刷新，可以在别处调用这个方法。
     */
    public synchronized void reloadFromMultiConfig() {
        String primaryName = multiProps.getPrimaryModel();
        var profile = multiProps.requireProfile(primaryName);

        ChatModel model = chatModelFactory.create(profile);
        delegate.set(model);

        log.info("[HotSwapChatModel] loaded from ai.multi: primaryProfile={} provider={} modelId={}",
                primaryName, profile.getProvider(), profile.getModelId());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.get().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.get().stream(prompt);
    }
}
