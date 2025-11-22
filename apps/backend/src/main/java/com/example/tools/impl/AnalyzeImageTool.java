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
import com.example.tools.support.GridLocalizationPipeline;
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
 * - 使用预设的 Gemini 多模态模型（ai.multi.models.gemini-vision）直接调用 OpenAI 兼容接口，
 *   生成概要/详细/原文/tags 等描述信息；
 * - 不再通过 PythonExecTool + Qwen-VL，而是直接在 Java 里用 WebClient 调 Gemini。
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
    private static final int DEFAULT_GRID_ROWS = 15;
    private static final int DEFAULT_GRID_COLS = 20;
    // 坐标模式内部最大尝试次数（行/列各自）
    private static final int MAX_ROW_ATTEMPTS = 15;
    private static final int MAX_COL_ATTEMPTS = 20;

    private final ImageCacheManager imageCacheManager;
    private GridLocalizationPipeline pipeline;

    @PostConstruct
    public void init() {
        log.info("[AnalyzeImageTool] Initializing with {} threads for grid localization pipeline", 4);
        // 初始化并发处理器（4个线程）
        this.pipeline = new GridLocalizationPipeline(4);
        log.info("[AnalyzeImageTool] Initialization completed. Vision profile: {}, Grid size: {}x{}",
                VISION_PROFILE, DEFAULT_GRID_ROWS, DEFAULT_GRID_COLS);
    }

    @PreDestroy
    public void destroy() {
        log.info("[AnalyzeImageTool] Shutting down grid localization pipeline...");
        if (pipeline != null) {
            pipeline.shutdown();
        }
        log.info("[AnalyzeImageTool] Shutdown completed");
    }


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
     * 给视觉模型的默认提示词。
     * 要求模型按照 四行 固定格式输出，以便后续解析。
     */
    private static final String DEFAULT_VISION_PROMPT = """
            请分四部分用中文和英文输出本图片的信息，严格按照下面格式：
            1) 第一行以"概要："开头，用一句话非常简要概括图片主要内容；
            2) 第二行以"详细："开头，用较详细的语言解释图片中的关键信息、人物/物体、动作和场景；
            3) 第三行以"原文："开头，如果图片中包含文字、公式或屏幕内容，请尽量逐字转写出来（可用 Markdown/LaTeX），如果没有文字就写"无"；
            4) 第四行以"tags: "开头，给出若干英文标签，用逗号分隔，例如：tags: math, formula, fourier, signal-processing。
            """;

    @Override
    public String name() {
        return "analyze_image";
    }

    @Override
    public String description() {
        return "Analyze a user-uploaded image: return basic metadata, captions, and (optionally) a grid-based click coordinate for UI automation.";
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

        // ✅ 新增：是否开启"网格坐标模式"
        props.put("coordinate_mode", Map.of(
                "type", "boolean",
                "description", "If true, use grid-based coordinate mode: the vision model will be asked to locate a target UI element "
                        + "in a grid (grid_rows × grid_cols) and output row/col, which will be converted to pixel coordinates.",
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

        // ✅ 新增：是否开启坐标模式
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

        // 4) 调用 Gemini 视觉模型（OpenAI 兼容接口）
        if (visionEnable && isImage) {
            log.info("[analyze_image] Vision processing enabled. Building image reference...");
            String imageUrl = buildImageRef(f); // 预签名
            if (!StringUtils.hasText(imageUrl)) {
                log.warn("[analyze_image] Failed to build image reference (url/base64)");
                analysis.put("vision_error", "failed to build image reference (url/base64), skip vision");
            } else {
                log.debug("[analyze_image] Image reference built successfully (length={})", imageUrl.length());

                if (coordinateMode) {
                    log.info("[analyze_image] ===== Entering COORDINATE MODE ===== grid={}x{}",
                            DEFAULT_GRID_ROWS, DEFAULT_GRID_COLS);
                    try {
                        long coordStartTime = System.currentTimeMillis();
                        // 🔥 使用并发流水线处理
                        Map<String, Object> gridResult = findCoordinatesConcurrently(
                                f, userId, conversationId, imageUrl, visionPrompt, width, height
                        );
                        analysis.putAll(gridResult);
                        long coordTime = System.currentTimeMillis() - coordStartTime;
                        log.info("[analyze_image] Coordinate mode completed in {}ms. Result: row={}, col={}, x={}, y={}",
                                coordTime,
                                gridResult.get("grid_row"),
                                gridResult.get("grid_col"),
                                gridResult.get("click_x"),
                                gridResult.get("click_y"));

                    } catch (Exception e) {
                        log.error("[analyze_image] Concurrent grid localization failed", e);
                        analysis.put("grid_error", "Concurrent processing failed: " + e.getMessage());
                    }
                } else {
                    log.info("[analyze_image] ===== Entering CAPTION MODE =====");
                    long captionStartTime = System.currentTimeMillis();
                    // ✅ 非坐标模式：保持原来的 caption / 原文 / tags 行为
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

            // ✅ 新增：坐标模式结果
            if (coordinateMode) {
                Object gridRowObj = analysis.get("grid_row");
                Object gridColObj = analysis.get("grid_col");
                Object clickXObj = analysis.get("click_x");
                Object clickYObj = analysis.get("click_y");
                Object gridErrorObj = analysis.get("grid_error");

                if (gridErrorObj != null) {
                    // 定位失败
                    sb.append("\n\n⚠️ Coordinate localization failed: ").append(gridErrorObj);

                    // 添加部分成功的信息
                    if (gridRowObj != null) {
                        sb.append("\n- Row found: ").append(gridRowObj);
                        sb.append(" (out of ").append(analysis.get("grid_rows")).append(" rows)");
                    }

                    Object bannedColsObj = analysis.get("banned_cols");
                    if (bannedColsObj instanceof List bannedCols && !bannedCols.isEmpty()) {
                        sb.append("\n- Attempted columns: ").append(bannedCols);
                    }

                } else if (gridRowObj != null && gridColObj != null && clickXObj != null && clickYObj != null) {
                    // 定位成功
                    sb.append("\n\n✅ Coordinate localization successful:");
                    sb.append("\n- Grid position: Row ").append(gridRowObj)
                            .append(", Column ").append(gridColObj);
                    sb.append(" (Grid size: ")
                            .append(analysis.get("grid_rows")).append("×")
                            .append(analysis.get("grid_cols")).append(")");
                    sb.append("\n- Pixel coordinates: (")
                            .append(clickXObj).append(", ").append(clickYObj).append(")");

                    // 可选：添加确认信息
                    Boolean rowContains = (Boolean) analysis.get("row_contains_target");
                    Boolean colContains = (Boolean) analysis.get("col_contains_target");
                    if (Boolean.TRUE.equals(rowContains) && Boolean.TRUE.equals(colContains)) {
                        sb.append("\n- Verification: Target confirmed in grid cell");
                    }

                    // 可选：添加搜索统计
                    Object bannedRowsObj = analysis.get("banned_rows");
                    Object bannedColsObj = analysis.get("banned_cols");
                    int rowAttempts = bannedRowsObj instanceof List ? ((List<?>) bannedRowsObj).size() + 1 : 1;
                    int colAttempts = bannedColsObj instanceof List ? ((List<?>) bannedColsObj).size() + 1 : 1;
                    if (rowAttempts > 1 || colAttempts > 1) {
                        sb.append("\n- Search attempts: ")
                                .append(rowAttempts).append(" row(s), ")
                                .append(colAttempts).append(" column(s)");
                    }
                } else {
                    // 数据不完整
                    sb.append("\n\n⚠️ Coordinate data incomplete");
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

        // ✅ 新增：坐标模式失败时返回 ERROR
        if (coordinateMode && analysis.containsKey("grid_error")) {
            log.error("[analyze_image] Coordinate mode failed: {}", analysis.get("grid_error"));
            return ToolResult.error(null, name(), summary);  // 返回 ERROR，不会被缓存
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[analyze_image] ===== Execution completed in {}ms ===== fileId={}, summaryLength={}",
                totalTime, fileId, summary.length());

        return ToolResult.success(null, name(), false, data);
    }

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
     * 为图片生成一个 MinIO 预签名 URL，给上游 Gemini 使用。
     * 注意：要求 MinIO 对外网可达，否则上游服务下载会失败。
     */
    private String buildImageUrl(AiFile f) {
        try {
            if (storageService instanceof MinioStorageService minio) {
                log.debug("[analyze_image] Using MinioStorageService to build public URL");
                // 优先使用对外暴露域名 + /minio 规则
                String url = minio.buildPublicReadUrl(f.getBucket(), f.getObjectKey());
                log.debug("[analyze_image] MinIO public URL generated: {}", url);
                return url;
            }

            log.debug("[analyze_image] Using generic StorageService presignGet");
            // 如果以后 StorageService 换了实现，就简单 fallback 到 presignGet
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
     * 使用 WebClient 调用 Gemini（或其他 OpenAI 兼容视觉模型）。
     *
     * - 优先从 ai.multi.models.gemini-vision 读取 baseUrl / apiKey / modelId；
     * - 如果找不到 profile 或 apiKey 为空，可按需扩展为从环境变量 GEMINI_API_KEY 读取；
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
            String msg = "Gemini vision API key is not configured (ai.multi.models."
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
                return Map.of("vision_error", "Empty response from Gemini vision API");
            }

            log.debug("[analyze_image] Parsing vision API response...");
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[analyze_image] No choices in vision API response");
                return Map.of("vision_error", "No choices in Gemini vision response");
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
                return Map.of("vision_error", "Gemini vision returned empty content");
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
            String msg = "Gemini HTTP " + wex.getRawStatusCode() + ": " + wex.getResponseBodyAsString();
            log.error("[analyze_image] Vision API HTTP error: status={}, body={}",
                    wex.getRawStatusCode(), wex.getResponseBodyAsString());
            return Map.of("vision_error", msg);
        } catch (Exception e) {
            log.error("[analyze_image] Vision API call exception", e);
            return Map.of("vision_error", e.getMessage());
        }
    }

    /**
     * 解析模型按照固定格式返回的四行文本。
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
     * 把 MinIO 里的图片读出来，转成 data:[mime];base64,xxxx 形式。
     * 这里为了安全，限制最大 4MB（可按需调大），避免超大图直接塞进请求。
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
     * 规范化 baseUrl：
     * - 去掉末尾多余的 /
     * - 如果是类似 https://api.xxx.com，则自动补成 https://api.xxx.com/v1
     * - 如果已经以 /v1 结尾，则不再追加
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

    /** 行选择 prompt：支持禁选若干行 */
    private String buildRowPrompt(String visionPrompt, int gridRows, Set<Integer> bannedRows) {
        String targetDesc = StringUtils.hasText(visionPrompt)
                ? visionPrompt
                : "你要点击或定位的目标界面元素（例如某个按钮、输入框或图标）";

        String bannedText = "";
        if (bannedRows != null && !bannedRows.isEmpty()) {
            bannedText = "注意：下面这些行已经被确认不包含目标元素，请不要再选择它们："
                    + bannedRows + "。\n";
        }

        return """
            你将看到一张电脑屏幕的截屏图片。

            请在心里把整张图片从上到下平均分成 %d 行：
            - 行索引从 0 到 %d，0 在最上方，%d 在最下方。

            %s目标界面元素是：
            %s

            你的任务是：在所有可能的行中，选择【最有可能】包含该目标元素"中心位置"的那一行。

            ⚠️ 输出要求非常严格：
            - 必须只输出一个 JSON 对象，不能有任何多余文字、解释或注释；
            - JSON 格式必须严格为：
              {"row": <行索引整数>}

            示例：
            {"row": 7}
            """.formatted(
                gridRows, gridRows - 1, gridRows - 1,
                bannedText,
                targetDesc
        );
    }

    // 兼容旧调用
    private String buildColPrompt(String visionPrompt, int gridCols, Integer rowIndex) {
        return buildColPrompt(visionPrompt, gridCols, rowIndex, Collections.emptySet());
    }

    /** 列选择 prompt：支持禁选若干列 */
    private String buildColPrompt(String visionPrompt, int gridCols, Integer rowIndex, Set<Integer> bannedCols) {
        String targetDesc = StringUtils.hasText(visionPrompt)
                ? visionPrompt
                : "你要点击或定位的目标界面元素（例如某个按钮、输入框或图标）";

        String rowHint = "";
        if (rowIndex != null && rowIndex >= 0) {
            rowHint = "你可以假设目标元素大致位于第 row = " + rowIndex
                    + " 行所在的水平带状区域内。\n";
        }

        String bannedText = "";
        if (bannedCols != null && !bannedCols.isEmpty()) {
            bannedText = "注意：下面这些列已经被确认不包含目标元素，请不要再选择它们："
                    + bannedCols + "。\n";
        }

        return """
            你将看到一张电脑屏幕的截屏图片。

            请在心里把整张图片从左到右平均分成 %d 列：
            - 列索引从 0 到 %d，0 在最左侧，%d 在最右侧。

            %s%s目标界面元素是：
            %s

            你的任务是：在所有可能的列中，选择【最有可能】包含该目标元素"中心位置"的那一列。

            ⚠️ 输出要求非常严格：
            - 必须只输出一个 JSON 对象，不能有任何多余文字、解释或注释；
            - JSON 格式必须严格为：
              {"col": <列索引整数>}

            示例：
            {"col": 12}
            """.formatted(
                gridCols, gridCols - 1, gridCols - 1,
                rowHint,
                bannedText,
                targetDesc
        );
    }


    /**
     * 从模型 raw 文本中解析某个字段（"row" 或 "col"）的整数值。
     * 期望 raw 形如：{"row": 7} 或 {"col": 12}
     */
    private Integer parseIndexFromRaw(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            log.debug("[analyze_image] parseIndexFromRaw: raw text is empty for field '{}'", fieldName);
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.has(fieldName)) {
                int v = node.get(fieldName).asInt(-1);
                if (v >= 0) {
                    log.debug("[analyze_image] Parsed {}={} from raw", fieldName, v);
                    return v;
                }
            }
            log.warn("[analyze_image] {} field missing or negative in vision raw: {}", fieldName, raw);
            return null;
        } catch (Exception e) {
            log.warn("[analyze_image] Failed to parse {} JSON from vision raw: {}", fieldName, raw, e);
            return null;
        }
    }

    /**
     * 组装网格结果：
     * - 保存原始 rowRaw / colRaw
     * - 保存解析后的 grid_row / grid_col
     * - 计算 click_x / click_y（格子中心）
     */
    private Map<String, Object> buildGridResult(
            String rowRaw,
            String colRaw,
            Integer rowIndex,
            Integer colIndex,
            int width,
            int height,
            int gridRows,
            int gridCols
    ) {
        log.debug("[analyze_image] Building grid result: row={}, col={}, dimensions={}x{}",
                rowIndex, colIndex, width, height);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grid_mode", true);
        result.put("grid_rows", gridRows);
        result.put("grid_cols", gridCols);
        result.put("grid_row_raw", rowRaw);
        result.put("grid_col_raw", colRaw);

        if (rowIndex == null || colIndex == null) {
            String error = "row or col index is null (rowIndex=" + rowIndex + ", colIndex=" + colIndex + ")";
            log.warn("[analyze_image] {}", error);
            result.put("grid_error", error);
            return result;
        }

        int row = rowIndex;
        int col = colIndex;

        // clamp 防御
        if (row < 0) {
            log.warn("[analyze_image] Row index {} is negative, clamping to 0", row);
            row = 0;
        }
        if (row >= gridRows) {
            log.warn("[analyze_image] Row index {} >= gridRows {}, clamping to {}", row, gridRows, gridRows - 1);
            row = gridRows - 1;
        }
        if (col < 0) {
            log.warn("[analyze_image] Col index {} is negative, clamping to 0", col);
            col = 0;
        }
        if (col >= gridCols) {
            log.warn("[analyze_image] Col index {} >= gridCols {}, clamping to {}", col, gridCols, gridCols - 1);
            col = gridCols - 1;
        }

        result.put("grid_row", row);
        result.put("grid_col", col);

        if (width > 0 && height > 0) {
            double cellWidth = (double) width / gridCols;
            double cellHeight = (double) height / gridRows;

            int x = (int) Math.round((col + 0.5) * cellWidth);
            int y = (int) Math.round((row + 0.5) * cellHeight);

            log.info("[analyze_image] Calculated click coordinates: ({}, {}) from grid cell ({}, {})",
                    x, y, row, col);

            result.put("click_x", x);
            result.put("click_y", y);
        } else {
            String error = "image width/height is zero, cannot compute pixel coordinates";
            log.warn("[analyze_image] {}", error);
            result.put("grid_error", error);
        }

        return result;
    }

    /**
     * 行级确认：
     *  - 已经选出了一个候选行 rowIndex（0-based，总共 gridRows 行）；
     *  - 现在只问：这一行对应的【水平带状区域】里，目标界面元素是不是确实出现；
     *  - 只允许输出 {"contains": true} 或 {"contains": false}。
     */
    private String buildRowCheckPrompt(String visionPrompt, int gridRows, int rowIndex) {
        String targetDesc = StringUtils.hasText(visionPrompt)
                ? visionPrompt
                : "你要点击或定位的目标界面元素（例如某个按钮、输入框或图标）";

        return """
            你将看到一张电脑屏幕截屏的【局部图片】。

            这张图片是从一整张截图中裁剪出来的：
            - 原始截图从上到下被平均分成 %d 行；
            - 当前这张图片只对应其中的第 row = %d 行的水平条带区域。

            你的任务是：判断下面描述的目标界面元素，是否出现在这条水平条带中
            （横向位置不限，只要出现在这条横带范围内就算"包含"）。

            目标界面元素是：
            %s

            ⚠️ 输出要求非常严格：
            - 必须只输出一个 JSON 对象，不能有任何多余文字、解释或注释；
            - JSON 格式必须严格为：
              {"contains": true}
            或：
              {"contains": false}
            """.formatted(
                gridRows,
                rowIndex,
                targetDesc
        );
    }

    /**
     * 列级确认（配合格子裁剪）：
     *  - 当前图片已经是原始截图在网格 (gridRows × gridCols) 中，
     *    row = rowIndex, col = colIndex 的那个"格子小块"；
     *  - 现在只问：这个格子里是否出现目标界面元素？
     *  - 只允许输出 {"contains": true} 或 {"contains": false}。
     */
    private String buildColCheckPrompt(
            String visionPrompt,
            int gridRows,
            int gridCols,
            int rowIndex,
            int colIndex
    ) {
        String targetDesc = StringUtils.hasText(visionPrompt)
                ? visionPrompt
                : "你要点击或定位的目标界面元素（例如某个按钮、输入框或图标）";

        return """
            你将看到一张电脑屏幕截屏的【局部小块图片】。

            这张图片是从一整张截图中裁剪出来的：
            - 原始截图被划分为 %d 行 × %d 列的网格；
            - 当前这张图片只对应其中的一个格子：
              行索引 row = %d（0 在最上方，%d 在最下方），
              列索引 col = %d（0 在最左侧，%d 在最右侧）。

            你的任务是：判断下面描述的目标界面元素，是否出现在这个格子区域中。

            目标界面元素是：
            %s

            ⚠️ 输出要求非常严格：
            - 必须只输出一个 JSON 对象，不能有任何多余文字、解释或注释；
            - JSON 格式必须严格为：
              {"contains": true}
            或：
              {"contains": false}
            """.formatted(
                gridRows, gridCols,
                rowIndex, gridRows - 1,
                colIndex, gridCols - 1,
                targetDesc
        );
    }


    /**
     * 从 raw 文本中解析 {"contains": true/false} 结构。
     *
     * 期望模型严格输出：
     *   {"contains": true}
     * 或：
     *   {"contains": false}
     *
     * 解析失败返回 null（上层再决定要不要重试）。
     */
    private Boolean parseContainsFromRaw(String raw) {
        if (!StringUtils.hasText(raw)) {
            log.debug("[analyze_image] parseContainsFromRaw: raw text is empty");
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);

            // ✅ 标准字段：contains
            if (node.has("contains")) {
                boolean result = node.get("contains").asBoolean();
                log.debug("[analyze_image] Parsed contains={} from raw", result);
                return result;
            }

            // （可选）兜底：有些模型可能输出 has_target / hasTarget 之类
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
     * 🔥 优化：使用缓存加载图像
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
                                            // ✅ 在 lambda 内部 try-catch
                                            BufferedImage bufferedImage = ImageIO.read(in);
                                            if (bufferedImage == null) {
                                                throw new IOException("ImageIO.read returned null - file is not a valid image");
                                            }
                                            return bufferedImage;
                                        } catch (IOException e) {
                                            // ✅ 包装成 RuntimeException
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
                            // ✅ 重新抛出，外层会捕获
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
     * 🔥 优化：快速裁剪横条（使用缓存的图像）
     */
    private String buildRowStripeDataUrl(AiFile f, int rowIndex, int gridRows) {
        log.debug("[analyze_image] Building row stripe data URL: row={}/{}", rowIndex, gridRows);
        long startTime = System.currentTimeMillis();

        try {
            BufferedImage img = loadImageWithCache(f);

            int fullW = img.getWidth();
            int fullH = img.getHeight();

            double cellH = (double) fullH / gridRows;
            int y = (int) Math.floor(rowIndex * cellH);
            int h = (rowIndex == gridRows - 1) ? (fullH - y) : (int) Math.ceil(cellH);

            // 边界检查
            y = Math.max(0, Math.min(y, fullH - 1));
            h = Math.max(1, Math.min(h, fullH - y));

            log.debug("[analyze_image] Cropping row stripe: y={}, height={} from {}x{}",
                    y, h, fullW, fullH);

            BufferedImage sub = img.getSubimage(0, y, fullW, h);
            String dataUrl = imageToDataUrl(sub);

            long cropTime = System.currentTimeMillis() - startTime;
            log.info("[analyze_image] Row stripe data URL built in {}ms (length={})",
                    cropTime, dataUrl.length());

            return dataUrl;

        } catch (Exception e) {
            log.warn("[analyze_image] buildRowStripeDataUrl failed for rowIndex={}", rowIndex, e);
            return null;  // 返回 null，让调用方降级到全图
        }
    }

    /**
     * 🔥 优化：快速裁剪格子（使用缓存的图像）
     */
    private String buildCellPatchDataUrl(
            AiFile f, int rowIndex, int colIndex, int gridRows, int gridCols
    ) {
        log.debug("[analyze_image] Building cell patch data URL: cell=({}, {}) in {}x{} grid",
                rowIndex, colIndex, gridRows, gridCols);
        long startTime = System.currentTimeMillis();

        try {
            BufferedImage img = loadImageWithCache(f);
            if (img == null) {
                log.warn("[analyze_image] loadImageWithCache returned null");
                return null;
            }

            int fullW = img.getWidth();
            int fullH = img.getHeight();

            double cellH = (double) fullH / gridRows;
            double cellW = (double) fullW / gridCols;

            int y = (int) Math.floor(rowIndex * cellH);
            int x = (int) Math.floor(colIndex * cellW);

            int h = (rowIndex == gridRows - 1) ? (fullH - y) : (int) Math.ceil(cellH);
            int w = (colIndex == gridCols - 1) ? (fullW - x) : (int) Math.ceil(cellW);

            // 边界检查
            y = Math.max(0, Math.min(y, fullH - 1));
            x = Math.max(0, Math.min(x, fullW - 1));
            h = Math.max(1, Math.min(h, fullH - y));
            w = Math.max(1, Math.min(w, fullW - x));

            log.debug("[analyze_image] Cropping cell patch: x={}, y={}, width={}, height={} from {}x{}",
                    x, y, w, h, fullW, fullH);

            BufferedImage sub = img.getSubimage(x, y, w, h);
            String dataUrl = imageToDataUrl(sub);

            long cropTime = System.currentTimeMillis() - startTime;
            log.info("[analyze_image] Cell patch data URL built in {}ms (length={})",
                    cropTime, dataUrl.length());

            return dataUrl;

        } catch (Exception e) {
            log.warn("[analyze_image] buildCellPatchDataUrl failed for cell=({}, {})",
                    rowIndex, colIndex, e);
            return null;
        }
    }

    /**
     * 🔥 工具方法：BufferedImage 转 Data URL
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
     * 🔥 并发坐标定位入口
     */
    private Map<String, Object> findCoordinatesConcurrently(
            AiFile f,
            String userId,
            String conversationId,
            String imageUrl,
            String visionPrompt,
            int width,
            int height
    ) throws Exception {

        log.info("[analyze_image] ===== Starting concurrent coordinate localization =====");
        long totalStartTime = System.currentTimeMillis();

        int gridRows = DEFAULT_GRID_ROWS;
        int gridCols = DEFAULT_GRID_COLS;

        // ========== 阶段1：并发行定位 ==========
        log.info("[analyze_image] Phase 1: Finding row with {} max attempts...", MAX_ROW_ATTEMPTS);
        long rowStartTime = System.currentTimeMillis();

        GridLocalizationPipeline.RowResult rowResult = pipeline.findRowConcurrently(
                gridRows,
                MAX_ROW_ATTEMPTS,
                // 行选择器
                (bannedRows) -> {
                    log.debug("[analyze_image] Row selector called with bannedRows={}", bannedRows);
                    String prompt = buildRowPrompt(visionPrompt, gridRows, bannedRows);
                    Map<String, Object> vision = callVision(userId, conversationId, imageUrl, prompt);
                    String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                    Integer row = parseIndexFromRaw(raw, "row");
                    log.info("[analyze_image] Row selector returned: row={}, bannedRows={}", row, bannedRows);
                    return new GridLocalizationPipeline.SelectResult(row, null, raw);
                },
                // 行确认器
                (rowIndex) -> {
                    log.debug("[analyze_image] Row checker called for rowIndex={}", rowIndex);
                    try {
                        String rowImageUrl = buildRowStripeDataUrl(f, rowIndex, gridRows);
                        String checkImageUrl = rowImageUrl != null ? rowImageUrl : imageUrl;
                        String prompt = buildRowCheckPrompt(visionPrompt, gridRows, rowIndex);
                        Map<String, Object> vision = callVision(userId, conversationId, checkImageUrl, prompt);
                        String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                        Boolean contains = parseContainsFromRaw(raw);
                        log.info("[analyze_image] Row checker returned: rowIndex={}, contains={}", rowIndex, contains);
                        return new GridLocalizationPipeline.CheckResult(contains, raw);
                    } catch (Exception e) {
                        log.error("[analyze_image] Row checker failed for rowIndex={}", rowIndex, e);
                        return new GridLocalizationPipeline.CheckResult(null, "Error: " + e.getMessage());
                    }
                }
        );

        Integer rowIndex = rowResult.getRowIndex();
        long rowTime = System.currentTimeMillis() - rowStartTime;

        log.info("[analyze_image] Phase 1 completed in {}ms. Found row: {}, bannedRows={}",
                rowTime, rowIndex, rowResult.getBannedRows());

        if (rowIndex == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("grid_mode", true);
            result.put("grid_rows", gridRows);
            result.put("grid_cols", gridCols);
            result.put("grid_row_raw", rowResult.getSelectRaw());
            result.put("row_check_raw", rowResult.getCheckRaw());
            result.put("banned_rows", rowResult.getBannedRows());
            result.put("grid_error", "Failed to find row after " + MAX_ROW_ATTEMPTS + " attempts");

            log.error("[analyze_image] Row localization failed after {} attempts", MAX_ROW_ATTEMPTS);
            return result;
        }

        // ========== 阶段2：并发列定位 ==========
        log.info("[analyze_image] Phase 2: Finding column with {} max attempts...", MAX_COL_ATTEMPTS);
        long colStartTime = System.currentTimeMillis();

        GridLocalizationPipeline.ColResult colResult = pipeline.findColConcurrently(
                gridCols,
                MAX_COL_ATTEMPTS,
                // 列选择器
                (bannedCols) -> {
                    log.debug("[analyze_image] Col selector called with bannedCols={}", bannedCols);
                    String prompt = buildColPrompt(visionPrompt, gridCols, rowIndex, bannedCols);
                    Map<String, Object> vision = callVision(userId, conversationId, imageUrl, prompt);
                    String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                    Integer col = parseIndexFromRaw(raw, "col");
                    log.info("[analyze_image] Col selector returned: col={}, bannedCols={}", col, bannedCols);
                    return new GridLocalizationPipeline.SelectResult(null, col, raw);
                },
                // 列确认器
                (colIndex) -> {
                    log.debug("[analyze_image] Col checker called for colIndex={}", colIndex);
                    try {
                        String cellImageUrl = buildCellPatchDataUrl(f, rowIndex, colIndex, gridRows, gridCols);
                        String checkImageUrl = cellImageUrl != null ? cellImageUrl : imageUrl;
                        String prompt = buildColCheckPrompt(visionPrompt, gridRows, gridCols, rowIndex, colIndex);
                        Map<String, Object> vision = callVision(userId, conversationId, checkImageUrl, prompt);
                        String raw = vision != null ? Objects.toString(vision.get("raw"), null) : null;
                        Boolean contains = parseContainsFromRaw(raw);
                        log.info("[analyze_image] Col checker returned: colIndex={}, contains={}", colIndex, contains);
                        return new GridLocalizationPipeline.CheckResult(contains, raw);
                    } catch (Exception e) {
                        log.error("[analyze_image] Col checker failed for colIndex={}", colIndex, e);
                        return new GridLocalizationPipeline.CheckResult(null, "Error: " + e.getMessage());
                    }
                }
        );

        long colTime = System.currentTimeMillis() - colStartTime;
        log.info("[analyze_image] Phase 2 completed in {}ms. Found col: {}, bannedCols={}",
                colTime, colResult.getColIndex(), colResult.getBannedCols());

        // ========== 组装结果 ==========
        Map<String, Object> result = buildGridResult(
                rowResult.getSelectRaw(),
                colResult.getSelectRaw(),
                rowIndex,
                colResult.getColIndex(),
                width, height, gridRows, gridCols
        );

        result.put("row_check_raw", rowResult.getCheckRaw());
        result.put("row_contains_target", rowResult.getContainsTarget());
        result.put("banned_rows", rowResult.getBannedRows());

        result.put("col_check_raw", colResult.getCheckRaw());
        result.put("col_contains_target", colResult.getContainsTarget());
        result.put("banned_cols", colResult.getBannedCols());

        if (colResult.getColIndex() == null) {
            String error = "Failed to find column after " + MAX_COL_ATTEMPTS + " attempts";
            result.put("grid_error", error);
            log.error("[analyze_image] {}", error);
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        log.info("[analyze_image] ===== Concurrent coordinate localization completed in {}ms ===== " +
                        "row={}, col={}, click=({}, {})",
                totalTime, rowIndex, colResult.getColIndex(),
                result.get("click_x"), result.get("click_y"));

        return result;
    }
}