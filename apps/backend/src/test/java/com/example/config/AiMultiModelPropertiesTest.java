package com.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简单测试：确认 ai.multi.* 能正确绑到 AiMultiModelProperties。
 */
@SpringBootTest
class AiMultiModelPropertiesTest {

    @Autowired
    private AiMultiModelProperties multiProps;

    @Test
    void shouldBindMultiModelConfig() {
        // 检查主模型
        assertThat(multiProps).isNotNull();
        assertThat(multiProps.getPrimaryModel()).isEqualTo("glm-agent");

        // 检查 glm-agent profile
        var glm = multiProps.requireProfile("glm-agent");
        assertThat(glm.getProvider()).isEqualTo("glm-openai");
        assertThat(glm.getModelId()).isEqualTo("glm-4.6");
        assertThat(glm.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");

        // 检查 gpt-reasoner
        var gpt = multiProps.requireProfile("gpt-reasoner");
        assertThat(gpt.getProvider()).isEqualTo("openai");
        assertThat(gpt.getModelId()).isEqualTo("gpt-5-chat-latest");

        // 检查 gemini-vision
        var gemini = multiProps.requireProfile("gemini-vision");
        assertThat(gemini.getProvider()).isEqualTo("gemini");
        assertThat(gemini.getModelId()).isEqualTo("gemini-1.5-pro");
    }

    @Test
    void printAllProfiles() {
        System.out.println("Primary model = " + multiProps.getPrimaryModel());
        multiProps.getModels().forEach((name, profile) -> {
            System.out.printf(
                    "profile=%s provider=%s modelId=%s baseUrl=%s%n",
                    name,
                    profile.getProvider(),
                    profile.getModelId(),
                    profile.getBaseUrl()
            );
        });
    }
}
