package com.example.ocr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OCR 识别到的一块文字区域。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrTextBox {

    /** 识别出的文字内容 */
    private String text;

    /** 左上角坐标 (x, y) —— 相对于整张图片，像素 */
    private int x;
    private int y;

    /** 区域宽高（像素） */
    private int width;
    private int height;

    /** 置信度（0~1） */
    private float confidence;
}
