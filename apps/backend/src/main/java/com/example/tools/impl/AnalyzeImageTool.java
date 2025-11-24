package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.storage.StorageService;
import com.example.storage.impl.MinioStorageService;
import com.example.tools.AiTool;
import com.example.tools.support.CoordinateLocator;
import com.example.tools.support.VisionImageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/**
 * analyze_image 工具：
 *
 * - 从 MinIO 读取用户上传的图片，做基础信息解析（宽/高等）；
 * - 使用视觉模型（VisionImageService）生成概要/详细/原文/tags 等（caption 模式）；
 * - 在 coordinate_mode=true 时，委托 PixelBinaryCoordinateLocator 使用像素二分搜索定位点击点。
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class AnalyzeImageTool implements AiTool {

    private final AiFileService aiFileService;
    private final StorageService storageService;
    private final VisionImageClient visionImageService;
    private final CoordinateLocator coordinateLocator;
    private final ObjectMapper objectMapper;

    /** 开关：目前始终开启，如有需要可以做成运行时配置。 */
    private final boolean visionEnable = true;

    @PostConstruct
    public void init() {
        log.info("[AnalyzeImageTool] Initializing. coordinate_mode will use PixelBinaryCoordinateLocator (Y then X binary search).");
    }

    @PreDestroy
    public void destroy() {
        log.info("[AnalyzeImageTool] AnalyzeImageTool destroy called");
    }

    @Override
    public String name() {
        return "analyze_image";
    }

    @Override
    public String description() {
        return "Analyze a user-uploaded image: return basic metadata, captions, and (optionally) a pixel-level click coordinate for UI automation.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_id", Map.of(
                "type", "string",
                "description", "ID of the file to analyze. Use list_user_files.files[].id."
        ));

        // 可选：主 LLM 可以根据用户问题生成更精准的视觉提示词
        props.put("vision_prompt", Map.of(
                "type", "string",
                "description", "Optional custom prompt for the vision model. "
                        + "The main LLM should summarize the user question and specify what to focus on in this image."
        ));

        // 分析模式：0=正常分析；1=像素二分查找坐标；2=文字查找坐标
        props.put("mode", Map.of(
                "type", "integer",
                "description", """
                Analyze mode:
                - 0: normal analysis (caption only)
                - 1: coordinate localization via pixel-level binary search
                - 2: coordinate localization via text-based search (e.g. OCR / text matching)
                """,
                "default", 0
        ));

        schema.put("properties", props);
        schema.put("required", List.of("file_id"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        long startTime = System.currentTimeMillis();

        String userId = Objects.toString(args.get("userId"), null);
        String conversationId = Objects.toString(args.get("conversationId"), null);
        String fileId = Objects.toString(args.get("file_id"), null);

        log.info("[analyze_image] ===== Starting execution ===== userId={}, conversationId={}, fileId={}",
                userId, conversationId, fileId);

        // 读取主 LLM 传过来的视觉提示词（可为空）
        String visionPrompt = null;
        Object vpObj = args.get("vision_prompt");
        if (vpObj != null) {
            String s = vpObj.toString().trim();
            if (!s.isEmpty()) {
                visionPrompt = s;
                log.debug("[analyze_image] Custom vision prompt provided: {}",
                        visionPrompt.length() > 100 ? visionPrompt.substring(0, 100) + "..." : visionPrompt);
            }
        }

        // 新：模式参数（0=正常分析, 1=像素二分, 2=文字查找）
        int mode = 0;
        Object modeObj = args.get("mode");
        if (modeObj instanceof Number n) {
            mode = n.intValue();
        } else if (modeObj != null) {
            try {
                mode = Integer.parseInt(modeObj.toString().trim());
            } catch (NumberFormatException ignore) {
            }
        }
        log.info("[analyze_image] Mode : {}", mode);

        boolean isCoordinateMode = (mode == 1 || mode == 2);

        if (!StringUtils.hasText(fileId)) {
            String msg = "Missing required parameter 'file_id'.";
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }

        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            String msg = "userId / conversationId missing from execution context";
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }

        // 1) 查文件记录
        log.debug("[analyze_image] Fetching file record for fileId={}", fileId);
        Optional<AiFile> opt = aiFileService.findById(fileId);
        if (opt.isEmpty()) {
            String msg = "File not found for id=" + fileId;
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }
        AiFile f = opt.get();
        log.info("[analyze_image] File found: bucket={}, objectKey={}, size={}, mimeType={}",
                f.getBucket(), f.getObjectKey(), f.getSizeBytes(), f.getMimeType());

        // 2) 安全检查：不允许跨用户
        if (!Objects.equals(userId, f.getUserId())) {
            String msg = String.format(
                    "File id=%s does not belong to current user (owner=%s, current=%s).",
                    fileId, f.getUserId(), userId
            );
            log.warn("[analyze_image] Security violation: {}", msg);
            return ToolResult.error(null, name(), msg);
        }
        log.debug("[analyze_image] Security check passed: file belongs to user {}", userId);

        String filename = Optional.ofNullable(f.getFilename()).orElse(f.getObjectKey());

        Map<String, Object> analysis = new LinkedHashMap<>();
        boolean isImage = false;
        int width = 0;
        int height = 0;

        // 3) 基础信息：ImageIO 读宽高
        log.debug("[analyze_image] Reading basic image metadata...");
        long metadataStartTime = System.currentTimeMillis();
        try {
            Map<String, Object> basic = storageService.withObject(
                    f.getBucket(),
                    f.getObjectKey(),
                    (InputStream in) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        try {
                            var img = ImageIO.read(in);
                            if (img == null) {
                                m.put("isImage", false);
                                m.put("message", "File is not a readable image.");
                                log.warn("[analyze_image] ImageIO.read returned null for fileId={}", fileId);
                            } else {
                                m.put("isImage", true);
                                m.put("width", img.getWidth());
                                m.put("height", img.getHeight());
                                try {
                                    m.put("colorModel", img.getColorModel().toString());
                                } catch (Exception ignore) {
                                }
                                log.debug("[analyze_image] Image metadata: {}x{}, colorModel={}",
                                        img.getWidth(), img.getHeight(), img.getColorModel());
                            }
                        } catch (Exception e) {
                            m.put("isImage", false);
                            m.put("message", "Failed to read image: " + e.getMessage());
                            log.warn("[analyze_image] Failed to read image with ImageIO", e);
                        }
                        return m;
                    }
            ).block(Duration.ofSeconds(10));

            if (basic != null) {
                analysis.putAll(basic);
                isImage = Boolean.TRUE.equals(basic.get("isImage"));
                Object wObj = basic.get("width");
                Object hObj = basic.get("height");
                if (wObj instanceof Number n) {
                    width = n.intValue();
                }
                if (hObj instanceof Number n) {
                    height = n.intValue();
                }
            }
            long metadataTime = System.currentTimeMillis() - metadataStartTime;
            log.info("[analyze_image] Basic metadata analysis completed in {}ms. isImage={}, dimensions={}x{}",
                    metadataTime, isImage, width, height);
        } catch (Exception e) {
            log.error("[analyze_image] Basic image analysis failed for fileId={}", fileId, e);
            analysis.put("isImage", false);
            analysis.put("message", "Failed to read image from storage: " + e.getMessage());
        }

        // 4) 视觉模型处理
        if (visionEnable && isImage) {

            if (mode == 1) {
                // ===== 像素二分坐标模式 =====
                log.info("[analyze_image] ===== Entering COORDINATE MODE (pixel-level binary search) =====");
                try {
                    long coordStartTime = System.currentTimeMillis();

                    Map<String, Object> coordResult = coordinateLocator.findCoordinatesByBinarySearch(
                            f, userId, conversationId, visionPrompt
                    );

                    analysis.putAll(coordResult);
                    long coordTime = System.currentTimeMillis() - coordStartTime;
                    log.info("[analyze_image] Coordinate mode (binary search) completed in {}ms. click=({}, {})",
                            coordTime,
                            coordResult.get("click_x"),
                            coordResult.get("click_y"));

                } catch (Exception e) {
                    log.error("[analyze_image] Pixel-level coordinate localization failed", e);
                    analysis.put("coordinate_error", "Pixel-level binary search failed: " + e.getMessage());
                }

            } else if (mode  == 2) {
                // ===== 2: 文字查找坐标模式 =====
                log.info("[analyze_image] ===== Entering TEXT COORDINATE MODE (mode=2) =====");
                try {
                    long textCoordStart = System.currentTimeMillis();

                    Map<String, Object> textCoord = coordinateLocator.findCoordinatesByTextSearch(
                            f, userId, conversationId, visionPrompt, width, height
                    );
                    textCoord.put("coordinate_strategy", "text");

                    analysis.putAll(textCoord);
                    long textCoordTime = System.currentTimeMillis() - textCoordStart;
                    log.info("[analyze_image] Text coordinate mode completed in {}ms. click=({}, {})",
                            textCoordTime,
                            textCoord.get("click_x"),
                            textCoord.get("click_y"));
                } catch (Exception e) {
                    log.error("[analyze_image] Text-based coordinate localization failed", e);
                    analysis.put("coordinate_error", "Text-based coordinate search failed: " + e.getMessage());
                }

            } else {
                // ===== 普通 caption 模式 =====
                log.info("[analyze_image] Vision processing enabled. Building image reference...");
                String imageUrl = buildImageRef(f); // 预签名或 data:base64
                if (!StringUtils.hasText(imageUrl)) {
                    log.warn("[analyze_image] Failed to build image reference (url/base64)");
                    analysis.put("vision_error", "failed to build image reference (url/base64), skip vision");
                } else {
                    log.debug("[analyze_image] Image reference built successfully (length={})", imageUrl.length());

                    log.info("[analyze_image] ===== Entering CAPTION MODE =====");
                    long captionStartTime = System.currentTimeMillis();
                    Map<String, Object> vision = visionImageService.callVision(
                            userId, conversationId, imageUrl, visionPrompt
                    );
                    if (vision != null && !vision.isEmpty()) {
                        analysis.putAll(vision);
                        long captionTime = System.currentTimeMillis() - captionStartTime;
                        log.info("[analyze_image] Caption mode completed in {}ms. Keys: {}",
                                captionTime, vision.keySet());
                    } else {
                        log.warn("[analyze_image] Caption mode returned empty result");
                    }
                }
            }
        } else {
            if (!visionEnable) {
                log.info("[analyze_image] Vision processing disabled by configuration");
            }
            if (!isImage) {
                log.info("[analyze_image] File is not a valid image, skipping vision processing");
            }
        }

        // 5) 组装 data
        log.debug("[analyze_image] Assembling final result...");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("conversationId", conversationId);
        data.put("fileId", fileId);
        data.put("bucket", f.getBucket());
        data.put("objectKey", f.getObjectKey());
        data.put("filename", filename);
        data.put("sizeBytes", f.getSizeBytes());
        data.put("mimeType", f.getMimeType());
        data.put("sha256", f.getSha256());
        data.put("analysis", analysis);
        data.put("_source", "analyze_image");

        // 6) summary / text
        StringBuilder sb = new StringBuilder();
        if (!isImage) {
            sb.append("File '").append(filename).append("' is not a readable image.");
        } else {
            sb.append("Image '").append(filename).append("' is ")
                    .append(width).append("×").append(height).append(" pixels.");

            if (isCoordinateMode) {
                // 坐标模式：像素二分搜索结果
                Object clickXObj = analysis.get("click_x");
                Object clickYObj = analysis.get("click_y");
                Object coordErrorObj = analysis.get("coordinate_error"); // ✅

                if (coordErrorObj != null) {
                    // 定位失败
                    sb.append("\n\n⚠️ Coordinate localization (mode=")
                            .append(mode).append(") failed: ").append(coordErrorObj);

                    Object yLow = analysis.get("search_y_low");
                    Object yHigh = analysis.get("search_y_high");
                    Object xLow = analysis.get("search_x_low");
                    Object xHigh = analysis.get("search_x_high");

                    if (yLow != null && yHigh != null) {
                        sb.append("\n- Final Y range: [").append(yLow).append(", ").append(yHigh).append("]");
                    }
                    if (xLow != null && xHigh != null) {
                        sb.append("\n- Final X range: [").append(xLow).append(", ").append(xHigh).append("]");
                    }

                } else if (clickXObj != null && clickYObj != null) {
                    // 定位成功
                    sb.append("\n\n✅ Coordinate localization successful (mode=")
                            .append(mode).append("):");
                    sb.append("\n- Pixel coordinates: (")
                            .append(clickXObj).append(", ").append(clickYObj).append(")");

                    Object ySteps = analysis.get("search_y_steps");
                    Object xSteps = analysis.get("search_x_steps");
                    if (ySteps != null || xSteps != null) {
                        sb.append("\n- Search steps: ");
                        if (ySteps != null) {
                            sb.append("Y=").append(ySteps);
                        }
                        if (xSteps != null) {
                            if (ySteps != null) sb.append(", ");
                            sb.append("X=").append(xSteps);
                        }
                    }

                } else {
                    // 数据不完整
                    sb.append("\n\n⚠️ Coordinate data incomplete (missing click_x / click_y)");
                }

            } else {
                // 原有的 caption 模式逻辑
                String brief = null;
                Object briefObj = analysis.get("caption_brief");
                if (briefObj instanceof String s && !s.isBlank()) {
                    brief = s;
                }

                String detail = null;
                Object detailObj = analysis.get("caption_detail");
                if (detailObj instanceof String s && !s.isBlank()) {
                    detail = s;
                }
                if (detail == null) {
                    Object cObj = analysis.get("caption");
                    if (cObj instanceof String s && !s.isBlank()) {
                        detail = s;
                    }
                }

                if (brief != null) {
                    sb.append(" 模型识别（概要）：").append(brief);
                } else if (detail != null) {
                    sb.append(" 模型识别：").append(detail);
                }

                if (detail != null && brief != null && !Objects.equals(brief, detail)) {
                    sb.append("\n\n详细解析：").append(detail);
                }

                Object origObj = analysis.get("original");
                if (origObj instanceof String orig && !orig.isBlank() && !"无".equals(orig.trim())) {
                    sb.append("\n\n原始内容：").append(orig);
                }
            }
        }

        String summary = sb.toString();
        data.put("summary", summary);
        data.put("text", summary);

        // 坐标模式失败时返回 ERROR（避免缓存错误结果）
        if (isCoordinateMode && analysis.containsKey("coordinate_error")) {
            log.error("[analyze_image] Coordinate mode failed: {}", analysis.get("coordinate_error"));
            return ToolResult.error(null, name(), summary);
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[analyze_image] ===== Execution completed in {}ms ===== fileId={}, summaryLength={}",
                totalTime, fileId, summary.length());

        return ToolResult.success(null, name(), false, data);
    }

    /**
     * 构造图片引用（优先预签名 URL，失败再退回 data:base64）。
     */
    private String buildImageRef(AiFile f) {
        log.debug("[analyze_image] Building image reference for fileId={}", f.getId());

        // 1) 先尝试用对外域名 / 预签名 URL
        String url = buildImageUrl(f);
        if (StringUtils.hasText(url)) {
            log.info("[analyze_image] Using presigned URL (length={})", url.length());
            return url;
        }

        // 2) 再尝试 data:base64 作为兜底
        log.debug("[analyze_image] Presigned URL not available, trying base64 encoding...");
        String dataUrl = buildDataUrl(f);
        if (!StringUtils.hasText(dataUrl)) {
            log.warn("[analyze_image] buildDataUrl also failed for fileId={}", f.getId());
            return null;
        }
        log.info("[analyze_image] Using base64 data URL (length={})", dataUrl.length());
        return dataUrl;
    }

    /**
     * 为图片生成一个 MinIO 预签名 URL，给上游视觉模型使用。
     * 注意：要求 MinIO 对外网可达，否则上游服务下载会失败。
     */
    private String buildImageUrl(AiFile f) {
        try {
            if (storageService instanceof MinioStorageService minio) {
                log.debug("[analyze_image] Using MinioStorageService to build public URL");
                String url = minio.buildPublicReadUrl(f.getBucket(), f.getObjectKey());
                log.debug("[analyze_image] MinIO public URL generated: {}", url);
                return url;
            }

            log.debug("[analyze_image] Using generic StorageService presignGet");
            return storageService.presignGet(
                    f.getBucket(),
                    f.getObjectKey(),
                    Duration.ofMinutes(10)
            ).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("[analyze_image] buildImageUrl failed for fileId={}", f.getId(), e);
            return null;
        }
    }

    /**
     * 把 MinIO 里的图片读出来，转成 data:[mime];base64,xxxx 形式。
     * 为安全起见限制最大 4MB。
     */
    private String buildDataUrl(AiFile f) {
        long size = Optional.ofNullable(f.getSizeBytes()).orElse(0L);
        long MAX_INLINE = 4L * 1024 * 1024; // 4MB

        log.debug("[analyze_image] buildDataUrl: fileId={}, size={} bytes", f.getId(), size);

        if (size <= 0 || size > MAX_INLINE) {
            log.warn("[analyze_image] File too large for inline data url: {} bytes (max={}MB)",
                    size, MAX_INLINE / (1024 * 1024));
            return null;
        }

        String mimeType = Optional.ofNullable(f.getMimeType()).orElse("image/png");

        try {
            log.debug("[analyze_image] Reading file content from storage...");
            long readStartTime = System.currentTimeMillis();

            byte[] bytes = storageService.withObject(
                    f.getBucket(),
                    f.getObjectKey(),
                    (InputStream in) -> {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int len;
                        while (true) {
                            try {
                                len = in.read(buf);
                                if (len == -1) break;
                                bos.write(buf, 0, len);
                            } catch (IOException e) {
                                log.error("[analyze_image] Error reading file stream", e);
                                break;
                            }
                        }
                        return bos.toByteArray();
                    }
            ).block(Duration.ofSeconds(20));

            long readTime = System.currentTimeMillis() - readStartTime;

            if (bytes == null || bytes.length == 0) {
                log.warn("[analyze_image] buildDataUrl: empty bytes for fileId={}", f.getId());
                return null;
            }

            log.debug("[analyze_image] File read completed in {}ms, encoding to base64...", readTime);
            long encodeStartTime = System.currentTimeMillis();

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            long encodeTime = System.currentTimeMillis() - encodeStartTime;
            log.info("[analyze_image] Base64 encoding completed in {}ms. DataURL length={}",
                    encodeTime, dataUrl.length());

            return dataUrl;
        } catch (Exception e) {
            log.error("[analyze_image] buildDataUrl failed for fileId={}", f.getId(), e);
            return null;
        }
    }



}
