package com.example.tools.support;

import com.example.ai.ChatGateway;
import com.example.config.EffectiveProps;
import com.example.file.domain.AiFile;
import com.example.ocr.OcrEngine;
import com.example.ocr.OcrTextBox;
import com.example.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/**
 * 使用像素坐标做二分搜索（先按 Y，再按 X）来定位点击点。
 *
 * - Phase1: Y 方向：在 [0, height) 区间二分，直到区间长度 <= MIN_COORDINATE_SPAN_PX；
 * - Phase2: X 方向：在 [0, width) 区间二分，限制在上一步得到的纵向带状区域内；
 * - 每一步用局部裁剪图 + {"contains": true/false} 判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCoordinateLocator implements CoordinateLocator{

    private final StorageService storageService;
    private final ImageCacheManager imageCacheManager;
    private final VisionImageClient visionImageService;
    private final OcrEngine ocrEngine;
    private final ObjectMapper mapper;   // 还要用来 parse JSON



    /**
     * 像素坐标二分搜索：最小区间大小（像素），当区间长度 <= 该值时停止二分。
     */
    private static final int MIN_COORDINATE_SPAN_PX = 50;

    /**
     * 二分搜索最大迭代次数（Y 方向 / X 方向）。
     */
    private static final int MAX_ROW_ATTEMPTS = 15;
    private static final int MAX_COL_ATTEMPTS = 20;

    /**
     * 使用像素坐标做二分搜索（先按 Y，再按 X）来定位点击点。
     */
    @Override
    public Map<String, Object> findCoordinatesByBinarySearch(
            AiFile f,
            String userId,
            String conversationId,
            String visionPrompt
    ) throws Exception {

        log.info("[PixelBinaryCoordinateLocator] ===== Starting pixel-level binary search localization =====");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate_mode", true);

        // 1) 载入完整图片（带缓存）
        BufferedImage img;
        try {
            img = loadImageWithCache(f);
        } catch (Exception e) {
            String err = "Failed to load image for binary search: " + e.getMessage();
            log.error("[PixelBinaryCoordinateLocator] {}", err, e);
            result.put("coordinate_error", err);
            return result;
        }

        if (img == null) {
            String err = "Image is null after loadImageWithCache";
            log.error("[PixelBinaryCoordinateLocator] {}", err);
            result.put("coordinate_error", err);
            return result;
        }

        int width = img.getWidth();
        int height = img.getHeight();
        log.info("[PixelBinaryCoordinateLocator] Binary search on image size {}x{}", width, height);

        if (width <= 0 || height <= 0) {
            String err = "Invalid image size " + width + "x" + height;
            log.error("[PixelBinaryCoordinateLocator] {}", err);
            result.put("grid_error", err);
            return result;
        }

        String containsPrompt = visionImageService.buildContainsPrompt(visionPrompt);

        // ========== Phase 1: Y 方向二分 ==========
        int yLow = 0;
        int yHigh = height;
        int ySteps = 0;
        String lastYRaw = null;

        if (height > MIN_COORDINATE_SPAN_PX) {
            while ((yHigh - yLow) > MIN_COORDINATE_SPAN_PX && ySteps < MAX_ROW_ATTEMPTS) {
                int mid = (yLow + yHigh) / 2;
                int bandHeight = mid - yLow;
                if (bandHeight <= 0) {
                    log.warn("[PixelBinaryCoordinateLocator] Y binary search: bandHeight <= 0, break");
                    break;
                }

                String patchUrl = buildRectDataUrl(img, 0, yLow, width, bandHeight);
                if (!StringUtils.hasText(patchUrl)) {
                    log.warn("[PixelBinaryCoordinateLocator] Y binary search: patchUrl is empty, break");
                    break;
                }

                Map<String, Object> vision = visionImageService.callVision(userId, conversationId, patchUrl, containsPrompt);
                String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                lastYRaw = raw;
                Boolean contains = visionImageService.parseContainsFromRaw(raw);

                log.info("[PixelBinaryCoordinateLocator] Y binary step={}, range=[{}, {}), mid={}, contains={}",
                        ySteps, yLow, yHigh, mid, contains);

                if (contains == null) {
                    // 解析失败就停下，用当前区间
                    log.warn("[PixelBinaryCoordinateLocator] Y binary search: parseContainsFromRaw returned null, stop");
                    break;
                }

                if (Boolean.TRUE.equals(contains)) {
                    // 目标在上半段 [yLow, mid)
                    yHigh = mid;
                } else {
                    // 目标在下半段 [mid, yHigh)
                    yLow = mid;
                }

                ySteps++;
            }
        }

        int yCenter = (yLow + yHigh) / 2;
        result.put("search_y_low", yLow);
        result.put("search_y_high", yHigh);
        result.put("search_y_steps", ySteps);
        result.put("y_last_raw", lastYRaw);

        log.info("[PixelBinaryCoordinateLocator] Y binary done: low={}, high={}, center={}, steps={}",
                yLow, yHigh, yCenter, ySteps);

        // 如果图片本身高度就很小，不需要 Y 二分
        if (height <= MIN_COORDINATE_SPAN_PX) {
            yLow = 0;
            yHigh = height;
            yCenter = height / 2;
        }

        // ========== Phase 2: X 方向二分  ==========
        int xLow = 0;
        int xHigh = width;
        int xSteps = 0;
        String lastXRaw = null;

        // 用 Y 二分得到的中心点，但不要直接用窄带
        int yBandCenter = yCenter;

        // 为 X 二分单独定义一个“安全高度”的带
        // 比如：至少 100px，高度的 1/3 二者取大，再截到 [0, height] 里
        int minBandHeight = 100;                 // 可以按需要调参
        int suggestedBand = height / 3;
        int bandHeight = Math.min(height, Math.max(minBandHeight, suggestedBand));

        // 让这个带以 yCenter 为中心
        int bandTop = yBandCenter - bandHeight / 2;
        int bandBottom = bandTop + bandHeight;

        // 边界裁剪
        if (bandTop < 0) {
            bandTop = 0;
            bandBottom = Math.min(height, bandHeight);
        } else if (bandBottom > height) {
            bandBottom = height;
            bandTop = Math.max(0, height - bandHeight);
        }

        log.info("[PixelBinaryCoordinateLocator] X-band for binary search: top={}, bottom={}, height={}",
                bandTop, bandBottom, bandBottom - bandTop);

        if (width > MIN_COORDINATE_SPAN_PX) {
            while ((xHigh - xLow) > MIN_COORDINATE_SPAN_PX && xSteps < MAX_COL_ATTEMPTS) {
                int mid = (xLow + xHigh) / 2;
                int currentBandWidth = mid - xLow;
                if (currentBandWidth <= 0) {
                    log.warn("[PixelBinaryCoordinateLocator] X binary search: bandWidth <= 0, break");
                    break;
                }

                String patchUrl = buildRectDataUrl(img, xLow, bandTop, currentBandWidth, bandBottom - bandTop);
                if (!StringUtils.hasText(patchUrl)) {
                    log.warn("[PixelBinaryCoordinateLocator] X binary search: patchUrl is empty, break");
                    break;
                }

                Map<String, Object> vision = visionImageService.callVision(userId, conversationId, patchUrl, containsPrompt);
                String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                lastXRaw = raw;
                Boolean contains = visionImageService.parseContainsFromRaw(raw);

                log.info("[PixelBinaryCoordinateLocator] X binary step={}, range=[{}, {}), mid={}, contains={}",
                        xSteps, xLow, xHigh, mid, contains);

                if (contains == null) {
                    log.warn("[PixelBinaryCoordinateLocator] X binary search: parseContainsFromRaw returned null, stop");
                    break;
                }

                if (Boolean.TRUE.equals(contains)) {
                    // 目标在左半段 [xLow, mid)
                    xHigh = mid;
                } else {
                    // 目标在右半段 [mid, xHigh)
                    xLow = mid;
                }

                xSteps++;
            }
        }

        int xCenter = (xLow + xHigh) / 2;
        result.put("search_x_low", xLow);
        result.put("search_x_high", xHigh);
        result.put("search_x_steps", xSteps);
        result.put("x_last_raw", lastXRaw);

        log.info("[PixelBinaryCoordinateLocator] X binary done: low={}, high={}, center={}, steps={}",
                xLow, xHigh, xCenter, xSteps);

        // 宽度很小则直接取中心
        if (width <= MIN_COORDINATE_SPAN_PX) {
            xLow = 0;
            xHigh = width;
            xCenter = width / 2;
        }

        // 最终点击坐标 = 区间中心
        result.put("click_x", xCenter);
        result.put("click_y", yCenter);

        log.info("[PixelBinaryCoordinateLocator] ===== Binary search complete. click=({}, {}), steps=(Y={}, X={}) =====",
                xCenter, yCenter, ySteps, xSteps);

        return result;
    }

    @Override
    public Map<String, Object> findCoordinatesByTextSearch(
            AiFile file,
            String userId,
            String conversationId,
            String visionPrompt,
            Integer width,
            Integer height
    ) throws Exception {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate_mode", true);
        result.put("coordinate_strategy", "text-local-ocr");

        log.info("[CoordinateLocator] === Text OCR locate start: fileId={}, userId={}, convId={} ===",
                file.getId(), userId, conversationId);
        if (StringUtils.hasText(visionPrompt)) {
            // 避免打印太长，可以截断
            String shortPrompt = visionPrompt.length() > 120
                    ? visionPrompt.substring(0, 120) + "..."
                    : visionPrompt;
            log.info("[CoordinateLocator] Text OCR visionPrompt={}", shortPrompt);
        }

        // 1) 读整张图片
        BufferedImage img = loadImageWithCache(file);
        if (img == null) {
            String err = "findCoordinatesByTextSearch: image is null";
            log.warn("[CoordinateLocator] {}", err);
            result.put("coordinate_error", err);
            return result;
        }

        int imgW = img.getWidth();
        int imgH = img.getHeight();
        if (width == null || width <= 0)  width  = imgW;
        if (height == null || height <= 0) height = imgH;

        result.put("image_width", imgW);
        result.put("image_height", imgH);
        result.put("canvas_width", width);
        result.put("canvas_height", height);

        log.info("[CoordinateLocator] Text OCR on image size {}x{}", imgW, imgH);

        // 2) 本地 OCR
        List<OcrTextBox> boxes = ocrEngine.recognize(img, "ch+en");
        if (boxes == null || boxes.isEmpty()) {
            String err = "OCR returned no text boxes.";
            log.warn("[CoordinateLocator] {}", err);
            result.put("coordinate_error", err);
            return result;
        }
        log.info("[CoordinateLocator] Raw OCR boxes count={}", boxes.size());

        // 3) 打包为 ocr_boxes
        List<Map<String, Object>> boxList = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            OcrTextBox box = boxes.get(i);
            if (box == null) continue;
            String text = box.getText();
            if (!StringUtils.hasText(text)) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", box.getX());
            m.put("y", box.getY());
            m.put("width", box.getWidth());
            m.put("height", box.getHeight());
            m.put("text", text);
            m.put("confidence", box.getConfidence());
            boxList.add(m);

            // 只打印前几条，避免日志爆炸
            if (i < 5) {
                log.debug("[CoordinateLocator] OCR box[{}]: ({}, {}, {}, {}), text='{}', conf={}",
                        i, box.getX(), box.getY(), box.getWidth(), box.getHeight(), text, box.getConfidence());
            }
        }

        if (boxList.isEmpty()) {
            String err = "OCR boxes all empty after filtering.";
            log.warn("[CoordinateLocator] {}", err);
            result.put("coordinate_error", err);
            return result;
        }

        result.put("ocr_engine", "local-ocr");
        result.put("ocr_language", "ch+en");
        result.put("ocr_boxes", boxList);
        if (StringUtils.hasText(visionPrompt)) {
            result.put("vision_prompt", visionPrompt);
        }

        // 4) 询问 LLM：基于 visionPrompt 在 OCR 文本框中选择最匹配的一项
        Map<String, Object> llmMatch = visionImageService.chooseBestTextBox(
                userId,
                conversationId,
                visionPrompt,
                boxList
        );
        if (llmMatch != null && !llmMatch.isEmpty()) {
            // 原始 LLM 决策结果放在 llm_match 里
            result.put("llm_match", llmMatch);

            // 如果算出了中心点，就顺手给一个 click_x / click_y，方便直接点击
            Object cx = llmMatch.get("center_x");
            Object cy = llmMatch.get("center_y");
            if (cx instanceof Number && cy instanceof Number) {
                int clickX = ((Number) cx).intValue();
                int clickY = ((Number) cy).intValue();
                result.put("click_x", clickX);
                result.put("click_y", clickY);
            }
        }

        log.info("[CoordinateLocator] Text OCR done: filtered boxes={}, img=({}x{}), llm_index={}",
                boxList.size(), imgW, imgH,
                llmMatch != null ? llmMatch.get("llm_index") : null);
        log.info("[CoordinateLocator] === Text OCR locate end: fileId={} ===", file.getId());

        return result;
    }


    // ====== 辅助方法 ======

    /**
     * 使用缓存加载整张图片。
     */
    private BufferedImage loadImageWithCache(AiFile f) throws Exception {
        log.debug("[PixelBinaryCoordinateLocator] Loading image with cache for fileId={}", f.getId());
        long startTime = System.currentTimeMillis();

        try {
            BufferedImage img = imageCacheManager.getOrLoad(
                    f.getBucket(),
                    f.getObjectKey(),
                    () -> {
                        try {
                            log.debug("[PixelBinaryCoordinateLocator] Cache miss, loading from storage: {}/{}",
                                    f.getBucket(), f.getObjectKey());

                            BufferedImage result = storageService.withObject(
                                    f.getBucket(),
                                    f.getObjectKey(),
                                    (InputStream in) -> {
                                        try {
                                            BufferedImage bufferedImage = ImageIO.read(in);
                                            if (bufferedImage == null) {
                                                throw new IOException("ImageIO.read returned null - file is not a valid image");
                                            }
                                            return bufferedImage;
                                        } catch (IOException e) {
                                            throw new RuntimeException("Failed to read image: " + e.getMessage(), e);
                                        }
                                    }
                            ).block(Duration.ofSeconds(20));

                            if (result == null) {
                                throw new RuntimeException("Failed to load image from storage (result is null)");
                            }

                            log.debug("[PixelBinaryCoordinateLocator] Image loaded from storage: {}x{}",
                                    result.getWidth(), result.getHeight());
                            return result;

                        } catch (Exception e) {
                            log.error("[PixelBinaryCoordinateLocator] Failed to load image for fileId={}", f.getId(), e);
                            throw new RuntimeException("Failed to load image: " + e.getMessage(), e);
                        }
                    }
            );

            if (img == null) {
                throw new RuntimeException("Cached image is null for fileId=" + f.getId());
            }

            long loadTime = System.currentTimeMillis() - startTime;
            log.info("[PixelBinaryCoordinateLocator] Image loaded (with cache) in {}ms: {}x{}",
                    loadTime, img.getWidth(), img.getHeight());

            return img;

        } catch (Exception e) {
            log.error("[PixelBinaryCoordinateLocator] loadImageWithCache failed for fileId={}", f.getId(), e);
            throw e;
        }
    }

    /**
     * BufferedImage 转 data:image/png;base64,...。
     */
    private String imageToDataUrl(BufferedImage img) throws IOException {
        log.debug("[PixelBinaryCoordinateLocator] Converting BufferedImage to data URL: {}x{}",
                img.getWidth(), img.getHeight());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        byte[] bytes = bos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:image/png;base64," + base64;

        log.debug("[PixelBinaryCoordinateLocator] Image converted to data URL (length={})", dataUrl.length());
        return dataUrl;
    }

    /**
     * 将原图的任意矩形区域裁剪成 data:image/png;base64,... 形式。
     */
    private String buildRectDataUrl(BufferedImage img, int x, int y, int w, int h) {
        if (img == null) {
            log.warn("[PixelBinaryCoordinateLocator] buildRectDataUrl: image is null");
            return null;
        }

        int fullW = img.getWidth();
        int fullH = img.getHeight();

        if (fullW <= 0 || fullH <= 0) {
            log.warn("[PixelBinaryCoordinateLocator] buildRectDataUrl: invalid image size {}x{}", fullW, fullH);
            return null;
        }

        // 边界裁剪
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (x >= fullW || y >= fullH) {
            log.warn("[PixelBinaryCoordinateLocator] buildRectDataUrl: rect origin ({}, {}) outside image {}x{}", x, y, fullW, fullH);
            return null;
        }

        if (w <= 0) {
            w = fullW - x;
        }
        if (h <= 0) {
            h = fullH - y;
        }

        if (x + w > fullW) {
            w = fullW - x;
        }
        if (y + h > fullH) {
            h = fullH - y;
        }

        if (w <= 0 || h <= 0) {
            log.warn("[PixelBinaryCoordinateLocator] buildRectDataUrl: non-positive rect size w={}, h={}", w, h);
            return null;
        }

        try {
            BufferedImage sub = img.getSubimage(x, y, w, h);
            return imageToDataUrl(sub);
        } catch (Exception e) {
            log.error("[PixelBinaryCoordinateLocator] buildRectDataUrl failed: x={}, y={}, w={}, h={}", x, y, w, h, e);
            return null;
        }
    }





    private double similarity(String text, String target) {
        text = text.trim();
        target = target.trim();

        if (text.equals(target)) return 1.0;
        if (text.contains(target) || target.contains(text)) return 0.8;

        // 需要再精细可以接个编辑距离，这里先返回 0
        return 0.0;
    }


}
