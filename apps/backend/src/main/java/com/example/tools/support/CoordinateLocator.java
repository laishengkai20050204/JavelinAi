package com.example.tools.support;

import com.example.file.domain.AiFile;

import java.util.Map;

/**
 * 坐标定位接口：给一个文件 + 目标描述，返回点击坐标和搜索过程的信息。
 */
public interface CoordinateLocator {

    /**
     * 使用像素二分搜索 定位点击点。
     *
     * @return Map 中至少包含：
     *   - "click_x", "click_y"（可能为 null）
     *   - "coordinate_mode" = true
     *   - 以及调试信息：search_y_low/high/steps, search_x_low/high/steps 等
     */
    Map<String, Object> findCoordinatesByBinarySearch(
            AiFile file,
            String userId,
            String conversationId,
            String visionPrompt
    ) throws Exception;

    /**
     * 文本匹配模式的坐标定位：
     * - 通过 OCR / 视觉模型，根据 visionPrompt 中的目标描述找到点击位置；
     * - width / height 如果为 null，则实现类内部可以自行从图片读取。
     *
     * 返回字段约定：
     *   - "click_x", "click_y"：像素坐标（可为 null）
     *   - "coordinate_mode" = true
     *   - "coordinate_strategy" = "text"
     *   - 出错时设置 "coordinate_error"
     */
    Map<String, Object> findCoordinatesByTextSearch(
            AiFile file,
            String userId,
            String conversationId,
            String visionPrompt,
            Integer width,
            Integer height
    ) throws Exception;
}
