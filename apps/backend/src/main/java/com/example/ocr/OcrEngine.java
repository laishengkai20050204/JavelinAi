package com.example.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * OCR 引擎接口：
 * - 输入一张图片；
 * - 输出若干带坐标的文字框。
 */
public interface OcrEngine {

    /**
     * 对整张图片做 OCR 识别。
     *
     * @param image    要识别的图片（整张截图）
     * @param langHint 语言提示，例如 "ch" / "ch+en"，可为 null
     * @return 识别到的文字框列表（可能为空列表，但不应为 null）
     */
    List<OcrTextBox> recognize(BufferedImage image, String langHint) throws Exception;
}
