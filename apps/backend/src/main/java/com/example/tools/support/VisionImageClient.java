package com.example.tools.support;

import java.util.List;
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

    /**
     * 基于用户目标描述和 OCR 文本框，让 LLM 选择最匹配的一个文本框。
     *
     * 约定返回结构：
     * {
     *   "llm_index": 3,          // 选中的 boxList 下标（-1 表示没有合适）
     *   "llm_score": 0.95,       // 可选，匹配置信度
     *   "llm_reason": "...",     // 可选，模型解释
     *   "center_x": 123,         // 可选，选中框中心点 X
     *   "center_y": 456,         // 可选，选中框中心点 Y
     *   "box": { ... }           // 可选，对应的原始 box 对象
     * }
     */
    Map<String, Object> chooseBestTextBox(
            String userId,
            String conversationId,
            String visionPrompt,
            List<Map<String, Object>> ocrBoxes
    );
}
