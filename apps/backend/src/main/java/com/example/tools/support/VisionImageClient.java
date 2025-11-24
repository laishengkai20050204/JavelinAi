package com.example.tools.support;

import java.util.Map;

/**
 * 视觉模型访问接口：
 * - caption 模式：整图描述；
 * - contains 模式：局部图里有没有目标元素。
 */
public interface VisionImageClient {

    /**
     * caption 模式：
     * - 入参：imageUrl（或 data:base64）、自定义提示词（可空）、userId/conversationId 仅用于日志打点；
     * - 出参：包含 caption_brief / caption_detail / original / tags / raw / vision_error 等字段。
     */
    Map<String, Object> callVision(
            String userId,
            String conversationId,
            String imageUrl,
            String customPrompt
    );

    /**
     * 解析 {"contains": true/false} 这种 JSON 文本。
     */
    Boolean parseContainsFromRaw(String raw);

    /**
     * 构造“局部图片是否包含目标元素”的提示词。
     */
    String buildContainsPrompt(String visionPrompt);
}
