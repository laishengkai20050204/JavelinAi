package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.AiMultiModelProperties;
import com.example.config.EffectiveProps;
import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.storage.StorageService;
import com.example.storage.impl.MinioStorageService;
import com.example.tools.AiTool;
import com.example.tools.support.ImageCacheManager;
import com.example.tools.support.ProxySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/**
 * analyze_image 工具：
 *
 * - 从 MinIO 读取用户上传的图片，做基础信息解析（宽/高等）；
 * - 使用预设的多模态模型（ai.multi.models.gemini-vision / qwen-vision 等）调用 OpenAI 兼容接口，
 *   生成概要/详细/原文/tags 等描述信息；
 * - 在 coordinate_mode=true 时，使用整张图片的像素坐标做二分搜索（先按 Y 再按 X），
 *   直接得到点击点 (click_x, click_y)，不再使用网格行列或 GridLocalizationPipeline。
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class AnalyzeImageTool implements AiTool {

    private final AiFileService aiFileService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final AiMultiModelProperties multiModelProperties;
    private final EffectiveProps effectiveProps;
    private final WebClient.Builder webClientBuilder;
    private final ImageCacheManager imageCacheManager;

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
     * 默认使用的视觉 profile 名（在 ai.multi.models.* 中配置）。
     *
     * application.yaml 示例：
     *
     * ai:
     *   multi:
     *     models:
     *       gemini-vision:
     *         provider: gemini   # 或 openai-compatible / geminiwe，随你
     *         base-url: https://api.vveai.com
     *         api-key: ${GEMINI_API_KEY}
     *         model-id: gemini-3-pro-preview
     */
    private static final String VISION_PROFILE = "qwen-vision";

    /** 如果配置里没写 model-id，则使用这个默认值。 */
    private static final String DEFAULT_VISION_MODEL = "gemini-3-pro-preview";

    /** 调用视觉模型的超时时间（毫秒）。 */
    private final int visionTimeoutMs = 5 * 60 * 1000;

    /** 开关：目前始终开启，如有需要可以做成运行时配置。 */
    private final boolean visionEnable = true;

    /**
     * 给视觉模型的默认提示词（caption 模式用）。
     * 要求模型按照四行固定格式输出，以便后续解析。
     */
    private static final String DEFAULT_VISION_PROMPT = """
            请分四部分用中文和英文输出本图片的信息，严格按照下面格式：
            1) 第一行以"概要："开头，用一句话非常简要概括图片主要内容；
            2) 第二行以"详细："开头，用较详细的语言解释图片中的关键信息、人物/物体、动作和场景；
            3) 第三行以"原文："开头，如果图片中包含文字、公式或屏幕内容，请尽量逐字转写出来（可用 Markdown/LaTeX），如果没有文字就写"无"；
            4) 第四行以"tags: "开头，给出若干英文标签，用逗号分隔，例如：tags: math, formula, fourier, signal-processing。
            """;

    @PostConstruct
    public void init() {
        log.info("[AnalyzeImageTool] Initializing. coordinate_mode will use pixel-level binary search (Y then X).");
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

        // 坐标模式：像素二分搜索
        props.put("coordinate_mode", Map.of(
                "type", "boolean",
                "description", "If true, use pixel-level coordinate mode: the vision model will be used in a Y-then-X binary search "
                        + "over the full image to directly produce click_x and click_y.",
                "default", false
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

        // 是否开启坐标模式（像素二分）
        boolean coordinateMode = false;
        Object cmObj = args.get("coordinate_mode");
        if (cmObj instanceof Boolean b) {
            coordinateMode = b;
        } else if (cmObj != null) {
            coordinateMode = Boolean.parseBoolean(cmObj.toString());
        }
        log.info("[analyze_image] Coordinate mode: {}", coordinateMode);

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
                            BufferedImage img = ImageIO.read(in);
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
            log.info("[analyze_image] Vision processing enabled. Building image reference...");
            String imageUrl = buildImageRef(f); // 预签名或 data:base64
            if (!StringUtils.hasText(imageUrl)) {
                log.warn("[analyze_image] Failed to build image reference (url/base64)");
                analysis.put("vision_error", "failed to build image reference (url/base64), skip vision");
            } else {
                log.debug("[analyze_image] Image reference built successfully (length={})", imageUrl.length());

                if (coordinateMode) {
                    // ===== 像素二分坐标模式 =====
                    log.info("[analyze_image] ===== Entering COORDINATE MODE (pixel-level binary search) =====");
                    try {
                        long coordStartTime = System.currentTimeMillis();

                        Map<String, Object> coordResult = findCoordinatesByBinarySearch(
                                f, userId, conversationId, visionPrompt, width, height
                        );

                        analysis.putAll(coordResult);
                        long coordTime = System.currentTimeMillis() - coordStartTime;
                        log.info("[analyze_image] Coordinate mode (binary search) completed in {}ms. click=({}, {})",
                                coordTime,
                                coordResult.get("click_x"),
                                coordResult.get("click_y"));

                    } catch (Exception e) {
                        log.error("[analyze_image] Pixel-level coordinate localization failed", e);
                        analysis.put("grid_error", "Pixel-level binary search failed: " + e.getMessage());
                    }

                } else {
                    // ===== 普通 caption 模式 =====
                    log.info("[analyze_image] ===== Entering CAPTION MODE =====");
                    long captionStartTime = System.currentTimeMillis();
                    Map<String, Object> vision = callVision(
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

            if (coordinateMode) {
                // 坐标模式：像素二分搜索结果
                Object clickXObj = analysis.get("click_x");
                Object clickYObj = analysis.get("click_y");
                Object coordErrorObj = analysis.get("grid_error");

                if (coordErrorObj != null) {
                    // 定位失败
                    sb.append("\n\n⚠️ Coordinate localization (binary search) failed: ").append(coordErrorObj);

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
                    sb.append("\n\n✅ Coordinate localization successful (pixel-level binary search):");
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
        if (coordinateMode && analysis.containsKey("coordinate_error")) {
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

    /**
     * 使用 WebClient 调用视觉模型（OpenAI 兼容接口）。
     *
     * - 优先从 ai.multi.models.VISION_PROFILE 读取 baseUrl / apiKey / modelId；
     * - 如果找不到 profile 或 apiKey 为空，可从环境变量 GEMINI_API_KEY 兜底；
     * - 请求体采用 OpenAI Chat Completions + image_url 格式。
     */
    private Map<String, Object> callVision(
            String userId,
            String conversationId,
            String imageUrl,
            String customPrompt
    ) {
        log.debug("[analyze_image] callVision started. imageUrlLength={}, hasCustomPrompt={}",
                imageUrl.length(), customPrompt != null);

        // 1) 解析多模型配置
        log.debug("[analyze_image] Loading vision model configuration for profile: {}", VISION_PROFILE);
        AiMultiModelProperties.ModelProfile profile = null;
        com.example.runtime.RuntimeConfig.ModelProfileDto runtimeProfile =
                effectiveProps != null ? effectiveProps.runtimeProfiles().get(VISION_PROFILE) : null;
        if (multiModelProperties != null) {
            try {
                profile = multiModelProperties.findProfile(VISION_PROFILE);
                log.debug("[analyze_image] Static profile loaded: {}", profile != null ? "found" : "not found");
            } catch (Exception e) {
                log.warn("[analyze_image] Failed to read multi-model profile '{}': {}", VISION_PROFILE, e.toString());
            }
        }

        String apiKey = null;
        String baseUrl = null;
        String model = DEFAULT_VISION_MODEL;

        if (runtimeProfile != null) {
            log.debug("[analyze_image] Using runtime profile configuration");
            if (StringUtils.hasText(runtimeProfile.getApiKey())) {
                apiKey = runtimeProfile.getApiKey();
            }
            if (StringUtils.hasText(runtimeProfile.getBaseUrl())) {
                baseUrl = runtimeProfile.getBaseUrl();
            }
            if (StringUtils.hasText(runtimeProfile.getModelId())) {
                model = runtimeProfile.getModelId();
            }
        } else if (profile != null) {
            log.debug("[analyze_image] Using static profile configuration");
            if (StringUtils.hasText(profile.getApiKey())) {
                apiKey = profile.getApiKey();
            }
            if (StringUtils.hasText(profile.getBaseUrl())) {
                baseUrl = profile.getBaseUrl();
            }
            if (StringUtils.hasText(profile.getModelId())) {
                model = profile.getModelId();
            }
        }

        // 可选：环境变量兜底
        if (!StringUtils.hasText(apiKey)) {
            log.debug("[analyze_image] API key not found in profiles, checking environment variable...");
            String envKey = System.getenv("GEMINI_API_KEY");
            if (StringUtils.hasText(envKey)) {
                apiKey = envKey;
                log.debug("[analyze_image] Using API key from environment variable");
            }
        }

        if (!StringUtils.hasText(apiKey)) {
            String msg = "Vision API key is not configured (ai.multi.models."
                    + VISION_PROFILE + ".api-key or GEMINI_API_KEY).";
            log.error("[analyze_image] {}", msg);
            return Map.of("vision_error", msg);
        }

        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://api.vveai.com";
            log.debug("[analyze_image] Using default baseUrl: {}", baseUrl);
        }

        // 规范化 baseUrl，确保以 /v1 结尾，便于直接 POST /chat/completions
        baseUrl = normalizeBaseUrl(baseUrl);
        log.info("[analyze_image] Vision API config: baseUrl={}, model={}, timeoutMs={}",
                baseUrl, model, visionTimeoutMs);

        String prompt = StringUtils.hasText(customPrompt) ? customPrompt : DEFAULT_VISION_PROMPT;
        log.debug("[analyze_image] Using prompt (length={}): {}",
                prompt.length(),
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

        try {
            log.debug("[analyze_image] Building WebClient...");
            WebClient.Builder builder = webClientBuilder
                    .clone()
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            builder = ProxySupport.configureWebClientProxyFromEnv(builder, "analyze_image");
            WebClient client = builder.build();

            Map<String, Object> imagePart = Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", imageUrl)
            );
            Map<String, Object> textPart = Map.of(
                    "type", "text",
                    "text", prompt
            );

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            msg.put("content", List.of(imagePart, textPart));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(msg));

            Duration timeout = Duration.ofMillis(visionTimeoutMs);

            log.info("[analyze_image] Sending request to vision API: POST /chat/completions");
            long apiStartTime = System.currentTimeMillis();

            JsonNode root = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            long apiTime = System.currentTimeMillis() - apiStartTime;
            log.info("[analyze_image] Vision API responded in {}ms", apiTime);

            if (root == null) {
                log.warn("[analyze_image] Vision API returned null response");
                return Map.of("vision_error", "Empty response from vision API");
            }

            log.debug("[analyze_image] Parsing vision API response...");
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[analyze_image] No choices in vision API response");
                return Map.of("vision_error", "No choices in vision response");
            }

            JsonNode first = choices.get(0);
            JsonNode messageNode = first.path("message");
            JsonNode contentNode = messageNode.get("content");

            String text;
            if (contentNode == null || contentNode.isNull()) {
                text = "";
            } else if (contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : contentNode) {
                    JsonNode t = part.get("text");
                    if (t != null && !t.isNull()) {
                        sb.append(t.asText());
                    }
                }
                text = sb.toString();
            } else {
                text = contentNode.asText();
            }

            if (!StringUtils.hasText(text)) {
                log.warn("[analyze_image] Vision API returned empty content");
                return Map.of("vision_error", "Vision model returned empty content");
            }

            log.info("[analyze_image] Vision API returned text (length={})", text.length());
            log.debug("[analyze_image] Vision response text: {}",
                    text.length() > 500 ? text.substring(0, 500) + "..." : text);

            Map<String, Object> parsed = parseVisionText(text);
            // 兼容字段：caption（默认用详细版）
            Object captionDetail = parsed.get("caption_detail");
            if (captionDetail instanceof String s && StringUtils.hasText(s)) {
                parsed.put("caption", s);
            } else if (parsed.get("caption") == null) {
                parsed.put("caption", text.trim());
            }
            parsed.put("raw", text);

            log.debug("[analyze_image] Parsed vision result keys: {}", parsed.keySet());
            return parsed;

        } catch (WebClientResponseException wex) {
            String msg = "Vision HTTP " + wex.getRawStatusCode() + ": " + wex.getResponseBodyAsString();
            log.error("[analyze_image] Vision API HTTP error: status={}, body={}",
                    wex.getRawStatusCode(), wex.getResponseBodyAsString());
            return Map.of("vision_error", msg);
        } catch (Exception e) {
            log.error("[analyze_image] Vision API call exception", e);
            return Map.of("vision_error", e.getMessage());
        }
    }

    /**
     * 解析模型按照固定格式返回的四行文本（caption 模式）。
     */
    private Map<String, Object> parseVisionText(String text) {
        log.debug("[analyze_image] Parsing vision text (length={})", text.length());

        String captionBrief = null;
        String captionDetail = null;
        String original = null;
        List<String> tags = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        log.debug("[analyze_image] Parsing {} lines from vision response", lines.length);

        for (String raw : lines) {
            String ln = raw == null ? "" : raw.trim();
            if (ln.isEmpty()) continue;
            String lower = ln.toLowerCase(Locale.ROOT);

            if (ln.startsWith("概要：") || ln.startsWith("概要:")) {
                captionBrief = extractAfterColon(ln);
                log.debug("[analyze_image] Found caption_brief: {}", captionBrief);
            } else if (ln.startsWith("详细：") || ln.startsWith("详细:")) {
                captionDetail = extractAfterColon(ln);
                log.debug("[analyze_image] Found caption_detail: {}", captionDetail);
            } else if (ln.startsWith("原文：") || ln.startsWith("原文:")) {
                original = extractAfterColon(ln);
                log.debug("[analyze_image] Found original: {}", original);
            } else if (lower.startsWith("tags:")) {
                String tagsPart = ln.substring(5).trim();
                tagsPart = tagsPart.replace("，", ",");
                String[] rawTags = tagsPart.split(",");
                for (String t : rawTags) {
                    String tag = t.trim();
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                    }
                }
                log.debug("[analyze_image] Found {} tags: {}", tags.size(), tags);
            }
        }

        if (captionDetail == null && captionBrief != null) {
            captionDetail = captionBrief;
        }
        if (captionBrief == null && captionDetail != null) {
            captionBrief = captionDetail;
        }
        if (captionBrief == null && captionDetail == null) {
            String trimmed = text == null ? "" : text.trim();
            if (!trimmed.isEmpty()) {
                captionBrief = trimmed;
                captionDetail = trimmed;
                log.debug("[analyze_image] No structured content found, using full text as caption");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (captionBrief != null) {
            result.put("caption_brief", captionBrief);
        }
        if (captionDetail != null) {
            result.put("caption_detail", captionDetail);
        }
        if (original != null) {
            result.put("original", original);
        }
        if (!tags.isEmpty()) {
            result.put("tags", tags);
        }

        log.debug("[analyze_image] Vision text parsing completed. Fields: {}", result.keySet());
        return result;
    }

    private String extractAfterColon(String line) {
        if (line == null) return null;
        int idx = line.indexOf('：');
        if (idx < 0) {
            idx = line.indexOf(':');
        }
        if (idx >= 0 && idx + 1 < line.length()) {
            return line.substring(idx + 1).trim();
        }
        return line.trim();
    }

    /**
     * 规范化 baseUrl：
     * - 去掉末尾多余的 /；
     * - 如果是类似 https://api.xxx.com，则自动补成 https://api.xxx.com/v1；
     * - 如果已经以 /v1 结尾，则不再追加。
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.vveai.com/v1";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed;
        }
        String normalized = trimmed + "/v1";
        log.debug("[analyze_image] Normalized baseUrl: {} -> {}", baseUrl, normalized);
        return normalized;
    }

    /**
     * 从 raw 文本中解析 {"contains": true/false} 结构。
     */
    private Boolean parseContainsFromRaw(String raw) {
        if (!StringUtils.hasText(raw)) {
            log.debug("[analyze_image] parseContainsFromRaw: raw text is empty");
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);

            // 标准字段：contains
            if (node.has("contains")) {
                boolean result = node.get("contains").asBoolean();
                log.debug("[analyze_image] Parsed contains={} from raw", result);
                return result;
            }

            // 兜底字段
            if (node.has("has_target")) {
                boolean result = node.get("has_target").asBoolean();
                log.debug("[analyze_image] Parsed has_target={} from raw (fallback)", result);
                return result;
            }
            if (node.has("hasTarget")) {
                boolean result = node.get("hasTarget").asBoolean();
                log.debug("[analyze_image] Parsed hasTarget={} from raw (fallback)", result);
                return result;
            }

            log.warn("[analyze_image] parseContainsFromRaw: no 'contains' field in raw: {}", raw);
            return null;
        } catch (Exception e) {
            log.warn("[analyze_image] parseContainsFromRaw: failed to parse JSON from raw: {}", raw, e);
            return null;
        }
    }

    /**
     * 构造“局部图片是否包含目标元素”的通用提示词。
     */
    private String buildContainsPrompt(String visionPrompt) {
        String targetDesc = StringUtils.hasText(visionPrompt)
                ? visionPrompt
                : "你要点击或定位的目标界面元素（例如某个按钮、输入框或图标）";

        return """
            你将看到一张电脑屏幕截屏的【局部图片】。

            这张图片是从一整张截图中裁剪出来的，可能包含，也可能不包含你要寻找的目标界面元素。

            目标界面元素是：
            %s

            请判断该局部图片中，是否可以看到这个目标界面元素的任意部分
            （例如按钮的一部分、图标的一部分、文字的一部分都算出现）。

            ⚠️ 输出要求非常严格：
            - 必须只输出一个 JSON 对象，不能有任何多余文字、解释或注释；
            - JSON 格式必须严格为：
              {"contains": true}
            或：
              {"contains": false}
            """.formatted(targetDesc);
    }

    /**
     * 使用缓存加载整张图片。
     */
    private BufferedImage loadImageWithCache(AiFile f) throws Exception {
        log.debug("[analyze_image] Loading image with cache for fileId={}", f.getId());
        long startTime = System.currentTimeMillis();

        try {
            BufferedImage img = imageCacheManager.getOrLoad(
                    f.getBucket(),
                    f.getObjectKey(),
                    () -> {
                        try {
                            log.debug("[analyze_image] Cache miss, loading from storage: {}/{}",
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

                            log.debug("[analyze_image] Image loaded from storage: {}x{}",
                                    result.getWidth(), result.getHeight());
                            return result;

                        } catch (Exception e) {
                            log.error("[analyze_image] Failed to load image for fileId={}", f.getId(), e);
                            throw new RuntimeException("Failed to load image: " + e.getMessage(), e);
                        }
                    }
            );

            if (img == null) {
                throw new RuntimeException("Cached image is null for fileId=" + f.getId());
            }

            long loadTime = System.currentTimeMillis() - startTime;
            log.info("[analyze_image] Image loaded (with cache) in {}ms: {}x{}",
                    loadTime, img.getWidth(), img.getHeight());

            return img;

        } catch (Exception e) {
            log.error("[analyze_image] loadImageWithCache failed for fileId={}", f.getId(), e);
            throw e;
        }
    }

    /**
     * BufferedImage 转 data:image/png;base64,...。
     */
    private String imageToDataUrl(BufferedImage img) throws IOException {
        log.debug("[analyze_image] Converting BufferedImage to data URL: {}x{}",
                img.getWidth(), img.getHeight());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        byte[] bytes = bos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:image/png;base64," + base64;

        log.debug("[analyze_image] Image converted to data URL (length={})", dataUrl.length());
        return dataUrl;
    }

    /**
     * 将原图的任意矩形区域裁剪成 data:image/png;base64,... 形式。
     */
    private String buildRectDataUrl(BufferedImage img, int x, int y, int w, int h) {
        if (img == null) {
            log.warn("[analyze_image] buildRectDataUrl: image is null");
            return null;
        }

        int fullW = img.getWidth();
        int fullH = img.getHeight();

        if (fullW <= 0 || fullH <= 0) {
            log.warn("[analyze_image] buildRectDataUrl: invalid image size {}x{}", fullW, fullH);
            return null;
        }

        // 边界裁剪
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (x >= fullW || y >= fullH) {
            log.warn("[analyze_image] buildRectDataUrl: rect origin ({}, {}) outside image {}x{}", x, y, fullW, fullH);
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
            log.warn("[analyze_image] buildRectDataUrl: non-positive rect size w={}, h={}", w, h);
            return null;
        }

        try {
            BufferedImage sub = img.getSubimage(x, y, w, h);
            return imageToDataUrl(sub);
        } catch (Exception e) {
            log.error("[analyze_image] buildRectDataUrl failed: x={}, y={}, w={}, h={}", x, y, w, h, e);
            return null;
        }
    }

    /**
     * 使用像素坐标做二分搜索（先按 Y，再按 X）来定位点击点：
     *  - Y 方向：在 [0, height) 区间二分，直到区间长度 <= MIN_COORDINATE_SPAN_PX；
     *  - X 方向：在 [0, width) 区间二分，限制在上一步得到的纵向带状区域内；
     *  - 每一步用局部裁剪图 + {"contains": true/false} 判断。
     */
    private Map<String, Object> findCoordinatesByBinarySearch(
            AiFile f,
            String userId,
            String conversationId,
            String visionPrompt,
            int width,
            int height
    ) throws Exception {

        log.info("[analyze_image] ===== Starting pixel-level binary search localization =====");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate_mode", true);

        // 1) 载入完整图片（带缓存）
        BufferedImage img;
        try {
            img = loadImageWithCache(f);
        } catch (Exception e) {
            String err = "Failed to load image for binary search: " + e.getMessage();
            log.error("[analyze_image] {}", err, e);
            result.put("coordinate_error", err);
            return result;
        }

        if (img == null) {
            String err = "Image is null after loadImageWithCache";
            log.error("[analyze_image] {}", err);
            result.put("coordinate_error", err);
            return result;
        }

        int imgW = img.getWidth();
        int imgH = img.getHeight();
        log.info("[analyze_image] Binary search on image size {}x{}", imgW, imgH);

        if (imgW <= 0 || imgH <= 0) {
            String err = "Invalid image size " + imgW + "x" + imgH;
            log.error("[analyze_image] {}", err);
            result.put("grid_error", err);
            return result;
        }

        // 以真正图片大小为准，覆盖元数据中的 width/height
        width = imgW;
        height = imgH;

        String containsPrompt = buildContainsPrompt(visionPrompt);

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
                    log.warn("[analyze_image] Y binary search: bandHeight <= 0, break");
                    break;
                }

                String patchUrl = buildRectDataUrl(img, 0, yLow, width, bandHeight);
                if (!StringUtils.hasText(patchUrl)) {
                    log.warn("[analyze_image] Y binary search: patchUrl is empty, break");
                    break;
                }

                Map<String, Object> vision = callVision(userId, conversationId, patchUrl, containsPrompt);
                String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                lastYRaw = raw;
                Boolean contains = parseContainsFromRaw(raw);

                log.info("[analyze_image] Y binary step={}, range=[{}, {}), mid={}, contains={}",
                        ySteps, yLow, yHigh, mid, contains);

                if (contains == null) {
                    // 解析失败就停下，用当前区间
                    log.warn("[analyze_image] Y binary search: parseContainsFromRaw returned null, stop");
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

        log.info("[analyze_image] Y binary done: low={}, high={}, center={}, steps={}",
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
        int minBandHeight = 100;                 // 你可以根据实际调
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

        log.info("[analyze_image] X-band for binary search: top={}, bottom={}, height={}", bandTop, bandBottom, bandBottom - bandTop);


        if (width > MIN_COORDINATE_SPAN_PX) {
            while ((xHigh - xLow) > MIN_COORDINATE_SPAN_PX && xSteps < MAX_COL_ATTEMPTS) {
                int mid = (xLow + xHigh) / 2;
                int bandWidth = mid - xLow;
                if (bandWidth <= 0) {
                    log.warn("[analyze_image] X binary search: bandWidth <= 0, break");
                    break;
                }

                String patchUrl = buildRectDataUrl(img, xLow, bandTop, bandWidth, bandBottom - bandTop);
                if (!StringUtils.hasText(patchUrl)) {
                    log.warn("[analyze_image] X binary search: patchUrl is empty, break");
                    break;
                }

                Map<String, Object> vision = callVision(userId, conversationId, patchUrl, containsPrompt);
                String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                lastXRaw = raw;
                Boolean contains = parseContainsFromRaw(raw);

                log.info("[analyze_image] X binary step={}, range=[{}, {}), mid={}, contains={}",
                        xSteps, xLow, xHigh, mid, contains);

                if (contains == null) {
                    log.warn("[analyze_image] X binary search: parseContainsFromRaw returned null, stop");
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

        log.info("[analyze_image] X binary done: low={}, high={}, center={}, steps={}",
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

        log.info("[analyze_image] ===== Binary search complete. click=({}, {}), steps=(Y={}, X={}) =====",
                xCenter, yCenter, ySteps, xSteps);

        return result;
    }
}
