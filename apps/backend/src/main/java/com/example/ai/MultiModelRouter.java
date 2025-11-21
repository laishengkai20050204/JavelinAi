package com.example.ai;

import com.example.config.AiMultiModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultiModelRouter {

    private final AiMultiModelProperties multiProps;
    private final MultiModelChatModelFactory chatModelFactory;

    /** profileName → ChatModel（懒加载 + 缓存） */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /** 主控模型（ai.multi.primary-model） */
    public ChatModel getPrimary() {
        String primary = multiProps.getPrimaryModel();
        return get(primary);
    }

    /**
     * 按 profileName 拿 ChatModel。
     * profileName 为空时，自动使用 primary-model。
     */
    public ChatModel get(String profileName) {
        String key = (profileName == null || profileName.isBlank())
                ? multiProps.getPrimaryModel()
                : profileName;

        return cache.computeIfAbsent(key, name -> {
            var profile = multiProps.requireProfile(name);
            ChatModel model = chatModelFactory.create(profile);
            log.info("[MultiModelRouter] created profile={} provider={} modelId={}",
                    name, profile.getProvider(), profile.getModelId());
            return model;
        });
    }
}
