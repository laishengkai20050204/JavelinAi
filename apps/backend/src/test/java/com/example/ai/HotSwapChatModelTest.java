package com.example.ai;

import com.example.config.AiMultiModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class HotSwapChatModelMultiTest {

    @Autowired
    private ChatModel chatModel; // @Primary → HotSwapChatModel

    @Autowired
    private AiMultiModelProperties multiProps;

    @Test
    void shouldUsePrimaryProfileFromMultiProps() {
        assertThat(multiProps.getPrimaryModel()).isEqualTo("glm-agent");
        assertThat(chatModel).isNotNull();

        // 真测一把可以打开（记得 key 要配置好）
//        var resp = chatModel.call(new Prompt(new UserMessage("只回答 OK")));
//        System.out.println("LLM reply = " + resp.getResult().getOutput().getContent());
    }
}
