package com.example.tools.support;

import com.example.config.AiMultiModelProperties;
import com.example.config.EffectiveProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

/**
 * 通用视觉服务：
 * - 负责根据 profile（ai.multi.models.qwen-vision）构造 baseUrl / apiKey / model；
 * - 以 OpenAI /v1/chat/completions + image_url 形式调用视觉模型；
 * - 提供 caption 模式解析（4 行结构化输出）；
 * - 提供 parseContainsFromRaw / buildContainsPrompt 给像素二分定位复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultVisionImageClient implements VisionImageClient{

    private final AiMultiModelProperties multiModelProperties;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** 默认视觉 profile 名 */
    private static final String VISION_PROFILE = "qwen-vision";

    /** 如果配置里没写 model-id，则使用这个默认值。 */
    private static final String DEFAULT_VISION_MODEL = "gemini-3-pro-preview";

    /** 调用视觉模型的超时时间（毫秒）。 */
    private static final int VISION_TIMEOUT_MS = 5 * 60 * 1000;

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

    /**
     * caption 模式：返回 caption_brief / caption_detail / original / tags / raw 等字段。
     */
    @Override
    public Map<String, Object> callVision(
            String userId,
            String conversationId,
            String imageUrl,
            String customPrompt
    ) {
        log.debug("[VisionImageService] callVision started. imageUrlLength={}, hasCustomPrompt={}",
                imageUrl != null ? imageUrl.length() : 0, customPrompt != null);

        // 1) 解析多模型配置
        log.debug("[VisionImageService] Loading vision model configuration for profile: {}", VISION_PROFILE);
        AiMultiModelProperties.ModelProfile profile = null;
        com.example.runtime.RuntimeConfig.ModelProfileDto runtimeProfile =
                effectiveProps != null ? effectiveProps.runtimeProfiles().get(VISION_PROFILE) : null;
        if (multiModelProperties != null) {
            try {
                profile = multiModelProperties.findProfile(VISION_PROFILE);
                log.debug("[VisionImageService] Static profile loaded: {}", profile != null ? "found" : "not found");
            } catch (Exception e) {
                log.warn("[VisionImageService] Failed to read multi-model profile '{}': {}", VISION_PROFILE, e.toString());
            }
        }

        String apiKey = null;
        String baseUrl = null;
        String model = DEFAULT_VISION_MODEL;

        if (runtimeProfile != null) {
            log.debug("[VisionImageService] Using runtime profile configuration");
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
            log.debug("[VisionImageService] Using static profile configuration");
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
            log.debug("[VisionImageService] API key not found in profiles, checking environment variable...");
            String envKey = System.getenv("GEMINI_API_KEY");
            if (StringUtils.hasText(envKey)) {
                apiKey = envKey;
                log.debug("[VisionImageService] Using API key from environment variable");
            }
        }

        if (!StringUtils.hasText(apiKey)) {
            String msg = "Vision API key is not configured (ai.multi.models."
                    + VISION_PROFILE + ".api-key or GEMINI_API_KEY).";
            log.error("[VisionImageService] {}", msg);
            return Map.of("vision_error", msg);
        }

        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://api.vveai.com";
            log.debug("[VisionImageService] Using default baseUrl: {}", baseUrl);
        }

        // 规范化 baseUrl，确保以 /v1 结尾，便于直接 POST /chat/completions
        baseUrl = normalizeBaseUrl(baseUrl);
        log.info("[VisionImageService] Vision API config: baseUrl={}, model={}, timeoutMs={}",
                baseUrl, model, VISION_TIMEOUT_MS);

        String prompt = StringUtils.hasText(customPrompt) ? customPrompt : DEFAULT_VISION_PROMPT;
        log.debug("[VisionImageService] Using prompt (length={}): {}",
                prompt.length(),
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

        try {
            log.debug("[VisionImageService] Building WebClient...");
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

            Duration timeout = Duration.ofMillis(VISION_TIMEOUT_MS);

            log.info("[VisionImageService] Sending request to vision API: POST /chat/completions");
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
            log.info("[VisionImageService] Vision API responded in {}ms", apiTime);

            if (root == null) {
                log.warn("[VisionImageService] Vision API returned null response");
                return Map.of("vision_error", "Empty response from vision API");
            }

            log.debug("[VisionImageService] Parsing vision API response...");
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[VisionImageService] No choices in vision API response");
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
                log.warn("[VisionImageService] Vision API returned empty content");
                return Map.of("vision_error", "Vision model returned empty content");
            }

            log.info("[VisionImageService] Vision API returned text (length={})", text.length());
            log.debug("[VisionImageService] Vision response text: {}",
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

            log.debug("[VisionImageService] Parsed vision result keys: {}", parsed.keySet());
            return parsed;

        } catch (WebClientResponseException wex) {
            String msg = "Vision HTTP " + wex.getRawStatusCode() + ": " + wex.getResponseBodyAsString();
            log.error("[VisionImageService] Vision API HTTP error: status={}, body={}",
                    wex.getRawStatusCode(), wex.getResponseBodyAsString());
            return Map.of("vision_error", msg);
        } catch (Exception e) {
            log.error("[VisionImageService] Vision API call exception", e);
            return Map.of("vision_error", e.getMessage());
        }
    }

    /**
     * 解析模型按照固定格式返回的四行文本（caption 模式）。
     */
    private Map<String, Object> parseVisionText(String text) {
        log.debug("[VisionImageService] Parsing vision text (length={})", text.length());

        String captionBrief = null;
        String captionDetail = null;
        String original = null;
        List<String> tags = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        log.debug("[VisionImageService] Parsing {} lines from vision response", lines.length);

        for (String raw : lines) {
            String ln = raw == null ? "" : raw.trim();
            if (ln.isEmpty()) continue;
            String lower = ln.toLowerCase(Locale.ROOT);

            if (ln.startsWith("概要：") || ln.startsWith("概要:")) {
                captionBrief = extractAfterColon(ln);
                log.debug("[VisionImageService] Found caption_brief: {}", captionBrief);
            } else if (ln.startsWith("详细：") || ln.startsWith("详细:")) {
                captionDetail = extractAfterColon(ln);
                log.debug("[VisionImageService] Found caption_detail: {}", captionDetail);
            } else if (ln.startsWith("原文：") || ln.startsWith("原文:")) {
                original = extractAfterColon(ln);
                log.debug("[VisionImageService] Found original: {}", original);
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
                log.debug("[VisionImageService] Found {} tags: {}", tags.size(), tags);
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
                log.debug("[VisionImageService] No structured content found, using full text as caption");
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

        log.debug("[VisionImageService] Vision text parsing completed. Fields: {}", result.keySet());
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
     * 解析 {"contains": true/false} 结构，供像素二分搜索使用。
     */
    @Override
    public Boolean parseContainsFromRaw(String raw) {
        if (!StringUtils.hasText(raw)) {
            log.debug("[VisionImageService] parseContainsFromRaw: raw text is empty");
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);

            // 标准字段：contains
            if (node.has("contains")) {
                boolean result = node.get("contains").asBoolean();
                log.debug("[VisionImageService] Parsed contains={} from raw", result);
                return result;
            }

            // 兜底字段
            if (node.has("has_target")) {
                boolean result = node.get("has_target").asBoolean();
                log.debug("[VisionImageService] Parsed has_target={} from raw (fallback)", result);
                return result;
            }
            if (node.has("hasTarget")) {
                boolean result = node.get("hasTarget").asBoolean();
                log.debug("[VisionImageService] Parsed hasTarget={} from raw (fallback)", result);
                return result;
            }

            log.warn("[VisionImageService] parseContainsFromRaw: no 'contains' field in raw: {}", raw);
            return null;
        } catch (Exception e) {
            log.warn("[VisionImageService] parseContainsFromRaw: failed to parse JSON from raw: {}", raw, e);
            return null;
        }
    }

    /**
     * 构造“局部图片是否包含目标元素”的通用提示词。
     */
    @Override
    public String buildContainsPrompt(String visionPrompt) {
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
        log.debug("[VisionImageService] Normalized baseUrl: {} -> {}", baseUrl, normalized);
        return normalized;
    }
}
