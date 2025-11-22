package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.AiMultiModelProperties;
import com.example.config.EffectiveProps;
import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.storage.StorageService;
import com.example.tools.AiTool;
import com.example.tools.support.ProxySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String VISION_PROFILE = "gemini-vision";

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
            1) 第一行以“概要：”开头，用一句话非常简要概括图片主要内容；
            2) 第二行以“详细：”开头，用较详细的语言解释图片中的关键信息、人物/物体、动作和场景；
            3) 第三行以“原文：”开头，如果图片中包含文字、公式或屏幕内容，请尽量逐字转写出来（可用 Markdown/LaTeX），如果没有文字就写“无”；
            4) 第四行以“tags: ”开头，给出若干英文标签，用逗号分隔，例如：tags: math, formula, fourier, signal-processing。
            """;

    @Override
    public String name() {
        return "analyze_image";
    }

    @Override
    public String description() {
        return "Analyze a user-uploaded image file and return basic metadata (width/height) and a natural language "
                + "caption using a multimodal vision model (Gemini or other OpenAI-compatible vision model). "
                + "You should call this tool after list_user_files, by passing files[i].id as file_id.";
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

        schema.put("properties", props);
        schema.put("required", List.of("file_id"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        String userId = Objects.toString(args.get("userId"), null);
        String conversationId = Objects.toString(args.get("conversationId"), null);
        String fileId = Objects.toString(args.get("file_id"), null);

        // 读取主 LLM 传过来的视觉提示词（可为空）
        String visionPrompt = null;
        Object vpObj = args.get("vision_prompt");
        if (vpObj != null) {
            String s = vpObj.toString().trim();
            if (!s.isEmpty()) {
                visionPrompt = s;
            }
        }

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
        Optional<AiFile> opt = aiFileService.findById(fileId);
        if (opt.isEmpty()) {
            String msg = "File not found for id=" + fileId;
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }
        AiFile f = opt.get();

        // 2) 安全检查：不允许跨用户
        if (!Objects.equals(userId, f.getUserId())) {
            String msg = String.format(
                    "File id=%s does not belong to current user (owner=%s, current=%s).",
                    fileId, f.getUserId(), userId
            );
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }

        String filename = Optional.ofNullable(f.getFilename()).orElse(f.getObjectKey());

        Map<String, Object> analysis = new LinkedHashMap<>();
        boolean isImage = false;
        int width = 0;
        int height = 0;

        // 3) 基础信息：ImageIO 读宽高
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
                            } else {
                                m.put("isImage", true);
                                m.put("width", img.getWidth());
                                m.put("height", img.getHeight());
                                try {
                                    m.put("colorModel", img.getColorModel().toString());
                                } catch (Exception ignore) {
                                }
                            }
                        } catch (Exception e) {
                            m.put("isImage", false);
                            m.put("message", "Failed to read image: " + e.getMessage());
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
        } catch (Exception e) {
            log.warn("[analyze_image] basic image analysis failed for fileId={}", fileId, e);
            analysis.put("isImage", false);
            analysis.put("message", "Failed to read image from storage: " + e.getMessage());
        }

        // 4) 调用 Gemini 视觉模型（OpenAI 兼容接口）
        if (visionEnable && isImage) {
            // 仍然使用 data:[mime];base64,...，避免对外暴露 MinIO 地址
            String imageUrl = buildDataUrl(f);
            if (StringUtils.hasText(imageUrl)) {
                Map<String, Object> vision = callGeminiVision(
                        userId, conversationId, imageUrl, visionPrompt
                );
                if (vision != null && !vision.isEmpty()) {
                    analysis.putAll(vision);
                }
            } else {
                analysis.put("vision_error", "failed to build data: URL for image, skip vision");
            }
        }

        // 5) 组装 data
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

        String summary = sb.toString();
        data.put("summary", summary);
        data.put("text", summary);

        return ToolResult.success(null, name(), false, data);
    }

    /**
     * 使用 WebClient 调用 Gemini（或其他 OpenAI 兼容视觉模型）。
     *
     * - 优先从 ai.multi.models.gemini-vision 读取 baseUrl / apiKey / modelId；
     * - 如果找不到 profile 或 apiKey 为空，可按需扩展为从环境变量 GEMINI_API_KEY 读取；
     * - 请求体采用 OpenAI Chat Completions + image_url 格式。
     */
    private Map<String, Object> callGeminiVision(
            String userId,
            String conversationId,
            String imageUrl,
            String customPrompt
    ) {
        // 1) 解析多模型配置
        AiMultiModelProperties.ModelProfile profile = null;
        com.example.runtime.RuntimeConfig.ModelProfileDto runtimeProfile =
                effectiveProps != null ? effectiveProps.runtimeProfiles().get(VISION_PROFILE) : null;
        if (multiModelProperties != null) {
            try {
                profile = multiModelProperties.findProfile(VISION_PROFILE);
            } catch (Exception e) {
                log.warn("[analyze_image] failed to read multi-model profile '{}': {}", VISION_PROFILE, e.toString());
            }
        }

        String apiKey = null;
        String baseUrl = null;
        String model = DEFAULT_VISION_MODEL;

        if (runtimeProfile != null) {
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
            String envKey = System.getenv("GEMINI_API_KEY");
            if (StringUtils.hasText(envKey)) {
                apiKey = envKey;
            }
        }

        if (!StringUtils.hasText(apiKey)) {
            String msg = "Gemini vision API key is not configured (ai.multi.models."
                    + VISION_PROFILE + ".api-key or GEMINI_API_KEY).";
            log.warn("[analyze_image] {}", msg);
            return Map.of("vision_error", msg);
        }

        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://api.vveai.com";
        }

        // 规范化 baseUrl，确保以 /v1 结尾，便于直接 POST /chat/completions
        baseUrl = normalizeBaseUrl(baseUrl);

        String prompt = StringUtils.hasText(customPrompt) ? customPrompt : DEFAULT_VISION_PROMPT;

        try {
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

            JsonNode root = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            if (root == null) {
                return Map.of("vision_error", "Empty response from Gemini vision API");
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
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
                return Map.of("vision_error", "Gemini vision returned empty content");
            }

            Map<String, Object> parsed = parseVisionText(text);
            // 兼容字段：caption（默认用详细版）
            Object captionDetail = parsed.get("caption_detail");
            if (captionDetail instanceof String s && StringUtils.hasText(s)) {
                parsed.put("caption", s);
            } else if (parsed.get("caption") == null) {
                parsed.put("caption", text.trim());
            }
            parsed.put("raw", text);
            return parsed;
        } catch (WebClientResponseException wex) {
            String msg = "Gemini HTTP " + wex.getRawStatusCode() + ": " + wex.getResponseBodyAsString();
            log.warn("[analyze_image] Gemini vision HTTP error: {}", msg);
            return Map.of("vision_error", msg);
        } catch (Exception e) {
            log.warn("[analyze_image] Gemini vision call exception", e);
            return Map.of("vision_error", e.getMessage());
        }
    }

    /**
     * 解析模型按照固定格式返回的四行文本。
     */
    private Map<String, Object> parseVisionText(String text) {
        String captionBrief = null;
        String captionDetail = null;
        String original = null;
        List<String> tags = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String ln = raw == null ? "" : raw.trim();
            if (ln.isEmpty()) continue;
            String lower = ln.toLowerCase(Locale.ROOT);

            if (ln.startsWith("概要：") || ln.startsWith("概要:")) {
                captionBrief = extractAfterColon(ln);
            } else if (ln.startsWith("详细：") || ln.startsWith("详细:")) {
                captionDetail = extractAfterColon(ln);
            } else if (ln.startsWith("原文：") || ln.startsWith("原文:")) {
                original = extractAfterColon(ln);
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
        if (size <= 0 || size > MAX_INLINE) {
            log.warn("[analyze_image] file too large for inline data url: {} bytes (max={})",
                    size, MAX_INLINE);
            return null;
        }

        String mimeType = Optional.ofNullable(f.getMimeType()).orElse("image/png");

        try {
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
                                log.error("读取文件失败", e);
                                break;
                            }
                        }
                        return bos.toByteArray();
                    }
            ).block(Duration.ofSeconds(20));

            if (bytes == null || bytes.length == 0) {
                log.warn("[analyze_image] buildDataUrl: empty bytes for fileId={}", f.getId());
                return null;
            }

            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + base64;
        } catch (Exception e) {
            log.warn("[analyze_image] buildDataUrl failed for fileId={}", f.getId(), e);
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
        return trimmed + "/v1";
    }
}
