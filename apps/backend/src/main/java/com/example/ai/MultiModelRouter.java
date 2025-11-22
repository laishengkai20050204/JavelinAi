package com.example.ai;

import com.example.config.AiMultiModelProperties;
import com.example.config.EffectiveProps;
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
    private final EffectiveProps effectiveProps;
    private final MultiModelChatModelFactory chatModelFactory;

    /** profileName -> ChatModel（包含静态+运行时） */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /** 主模型：ai.multi.primary-model / runtime.profile */
    public ChatModel getPrimary() {
        String primary = effectiveProps.profileOr(multiProps.getPrimaryModel());
        return get(primary);
    }

    /**
     * ��ȡ profileName ��Ӧ�� ChatModel��
     * profileName Ϊ��ʱ���Զ�ʹ�� primary-model���� runtime.profile����
     */
    public ChatModel get(String profileName) {
        String key = (profileName == null || profileName.isBlank())
                ? effectiveProps.profileOr(multiProps.getPrimaryModel())
                : profileName;

        return cache.computeIfAbsent(key, name -> {
            var runtimeProfile = effectiveProps.runtimeProfiles().get(name);
            if (runtimeProfile != null) {
                ChatModel model = chatModelFactory.create(name, runtimeProfile);
                log.info("[MultiModelRouter] created runtime profile={} provider={} modelId={}",
                        name, runtimeProfile.getProvider(), runtimeProfile.getModelId());
                return model;
            }

            var profile = multiProps.requireProfile(name);
            ChatModel model = chatModelFactory.create(profile);
            log.info("[MultiModelRouter] created static profile={} provider={} modelId={}",
                    name, profile.getProvider(), profile.getModelId());
            return model;
        });
    }
}
