package com.example.tools.support;

import com.example.ai.ChatGateway;
import com.example.config.AiMultiModelProperties;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 通用视觉服务：
 * - 通过 profile（ai.multi.models.qwen-vision）确定模型；
 * - 使用 ChatGateway 调用多模态（messages: input_text + input_image_url）；
 * - 提供 caption 模式解析（4 行结构化输出）；
 * - 提供 parseContainsFromRaw / buildContainsPrompt 给像素二分定位复用。
 *
 * 注意：不再直接注入 ChatGateway，防止 Spring Bean 循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultVisionImageClient implements VisionImageClient, ApplicationContextAware {

    private final AiMultiModelProperties multiModelProperties;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper objectMapper;

    private ApplicationContext applicationContext;   // 运行时从这里拿 ChatGateway

    /** 文字匹配询问 LLM 的超时时间 */
    private static final Duration TEXT_MATCH_LLM_TIMEOUT = Duration.ofSeconds(10);

    /** 默认视觉 profile 名 */
    private static final String VISION_PROFILE = "qwen-vision";

    private static final String TEXT_PROFILE = "qwen-vision";

    private static final String DEFAULT_VISION_MODEL = "";

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

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /** 运行时获取 ChatGateway，避免在 bean 依赖图里直接依赖它。 */
    private ChatGateway chatGateway() {
        if (this.applicationContext == null) {
            throw new IllegalStateException("ApplicationContext not injected into DefaultVisionImageClient");
        }
        return this.applicationContext.getBean(ChatGateway.class);
    }

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

        // 1) 用视觉 profile 做路由
        ModelRouting routing = resolveRouting(VISION_PROFILE, DEFAULT_VISION_MODEL);
        String profileName = routing.profileName();
        String model = routing.modelId();

        log.info("[VisionImageService] Vision routing: profile={}, model={}", profileName, model);

        String prompt = StringUtils.hasText(customPrompt) ? customPrompt : DEFAULT_VISION_PROMPT;

        try {
            // 2) 构造多模态 messages（input_text + input_image_url）
            List<Object> contentParts = new ArrayList<>();

            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "input_text");
            textPart.put("text", prompt);
            contentParts.add(textPart);

            if (StringUtils.hasText(imageUrl)) {
                Map<String, Object> imagePart = new LinkedHashMap<>();
                imagePart.put("type", "input_image_url");
                imagePart.put("url", imageUrl);
                imagePart.put("mime_type", guessMimeType(imageUrl));
                contentParts.add(imagePart);
            }

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", contentParts);

            List<Map<String, Object>> messages = List.of(userMsg);

            // 3) 组装统一 payload，走 ChatGateway
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("_profile", profileName);  // 走 qwen-vision 这条 profile
            if (StringUtils.hasText(model)) {
                payload.put("model", model);          // 给 ChatOptions.model() / provider 用
            }
            payload.put("messages", messages);
            payload.put("_flattened", true);          // 不用再拼历史
            payload.put("toolChoice", "none");        // **避免再次触发工具调用，防止递归死循环**

            if (StringUtils.hasText(userId)) {
                payload.put("userId", userId);
            }
            if (StringUtils.hasText(conversationId)) {
                payload.put("conversationId", conversationId);
            }

            AiProperties.Mode mode =
                    effectiveProps != null ? effectiveProps.mode() : AiProperties.Mode.OPENAI;
            Duration timeout = Duration.ofMillis(VISION_TIMEOUT_MS);

            log.info("[VisionImageService] Sending request to gateway, mode={}, timeoutMs={}", mode, VISION_TIMEOUT_MS);
            long apiStartTime = System.currentTimeMillis();

            // ⭐ 这里通过 ApplicationContext 拿到 ChatGateway
            String json = chatGateway().call(payload, mode).block(timeout);

            long apiTime = System.currentTimeMillis() - apiStartTime;
            log.info("[VisionImageService] Vision gateway responded in {}ms", apiTime);

            if (!StringUtils.hasText(json)) {
                log.warn("[VisionImageService] Vision gateway returned empty response");
                return Map.of("vision_error", "Empty response from vision gateway");
            }

            // 4) 解析统一 OpenAI 风格 JSON（choices[0].message.content）
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[VisionImageService] No choices in vision gateway response");
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
                log.warn("[VisionImageService] Vision gateway returned empty content");
                return Map.of("vision_error", "Vision model returned empty content");
            }

            log.info("[VisionImageService] Vision gateway returned text (length={})", text.length());
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

        } catch (Exception e) {
            log.error("[VisionImageService] Vision gateway call exception", e);
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
     * 根据 URL 粗略推断图片 MIME 类型。
     */
    private String guessMimeType(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return "image/png";
        }
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }


    @Override
    public Map<String, Object> chooseBestTextBox(
            String userId,
            String conversationId,
            String visionPrompt,
            List<Map<String, Object>> boxList
    ) {
        if (!StringUtils.hasText(visionPrompt)) {
            return null;
        }
        if (boxList == null || boxList.isEmpty()) {
            return null;
        }

        try {
            // 1) 构造 prompt（几乎就是你原来 askLlmForBestTextBox 里的文案）
            String targetText = extractTargetText(visionPrompt);

            StringBuilder sb = new StringBuilder();
            sb.append("你是一个帮助做屏幕文字匹配的小助手。")
                    .append("给定用户的目标描述和一批 OCR 文本框，请选择最可能需要点击的那个索引。")
                    .append("如果没有合适的匹配，请返回 index=-1。")
                    .append("只返回 JSON，不要输出多余文字。\n\n");

            sb.append("用户希望点击的目标（原始描述）：").append(visionPrompt).append("\n");
            if (StringUtils.hasText(targetText) && !Objects.equals(targetText, visionPrompt)) {
                sb.append("抽取出的核心目标文本：").append(targetText).append("\n");
            }
            sb.append("下面是 OCR 识别到的文本框列表，每一项都有一个 index 和 text。\n")
                    .append("请结合含义、上下文和相似度，选择最合适的一个 index。\n")
                    .append("如果没有明显匹配项，请返回 index=-1。\n\n");

            int maxItems = Math.min(boxList.size(), 80);
            for (int i = 0; i < maxItems; i++) {
                Map<String, Object> b = boxList.get(i);
                String text = Objects.toString(b.get("text"), "");
                Object confObj = b.get("confidence");
                String confStr = (confObj instanceof Number)
                        ? String.format(Locale.ROOT, "%.3f", ((Number) confObj).doubleValue())
                        : Objects.toString(confObj, "");
                sb.append("[")
                        .append(i)
                        .append("] text=\"")
                        .append(text.replace("\n", " "))
                        .append("\"");
                if (StringUtils.hasText(confStr)) {
                    sb.append(" (conf=").append(confStr).append(")");
                }
                sb.append("\n");
            }
            if (boxList.size() > maxItems) {
                sb.append("... (共 ").append(boxList.size()).append(" 个文本框，已截断)\n");
            }
            sb.append("\n请严格返回一个 JSON 对象，例如：")
                    .append("{\"index\": 3, \"score\": 0.96, \"reason\": \"与\\\"设置\\\"最接近\"}");

            String prompt = sb.toString();

            // 2) 路由到“文字匹配” profile
            AiProperties.Mode mode =
                    effectiveProps != null ? effectiveProps.mode() : AiProperties.Mode.OPENAI;

            ModelRouting routing = resolveRouting(TEXT_PROFILE, DEFAULT_VISION_MODEL);
            String profileName = routing.profileName();
            String model = routing.modelId();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("_profile", profileName);              // ☆ 使用 TEXT_PROFILE 对应的 profile
            if (StringUtils.hasText(model)) {
                payload.put("model", model);
            }
            payload.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));
            payload.put("_flattened", true);
            payload.put("toolChoice", "none");
            if (StringUtils.hasText(userId))        payload.put("userId", userId);
            if (StringUtils.hasText(conversationId)) payload.put("conversationId", conversationId);

            String json = chatGateway()
                    .call(payload, mode)
                    .block(TEXT_MATCH_LLM_TIMEOUT);

            if (!StringUtils.hasText(json)) {
                log.warn("[VisionImageService] chooseBestTextBox: empty gateway response");
                return null;
            }

            JsonNode root = objectMapper.readTree(json);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(content)) {
                log.warn("[VisionImageService] chooseBestTextBox: empty message.content");
                return null;
            }

            JsonNode obj;
            try {
                obj = objectMapper.readTree(content);
            } catch (Exception e) {
                log.warn("[VisionImageService] chooseBestTextBox: content is not valid JSON: {}", content);
                return null;
            }

            int index = obj.path("index").asInt(-1);
            double score = obj.has("score") ? obj.path("score").asDouble(Double.NaN) : Double.NaN;
            String reason = obj.path("reason").asText(null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("llm_index", index);
            if (!Double.isNaN(score)) {
                out.put("llm_score", score);
            }
            if (StringUtils.hasText(reason)) {
                out.put("llm_reason", reason);
            }

            if (index >= 0 && index < boxList.size()) {
                Map<String, Object> chosen = boxList.get(index);
                out.put("box", chosen);

                int bx = asInt(chosen.get("x"), -1);
                int by = asInt(chosen.get("y"), -1);
                int bw = asInt(chosen.get("width"), 0);
                int bh = asInt(chosen.get("height"), 0);
                if (bx >= 0 && by >= 0 && bw > 0 && bh > 0) {
                    int centerX = bx + bw / 2;
                    int centerY = by + bh / 2;
                    out.put("center_x", centerX);
                    out.put("center_y", centerY);
                }
            }

            log.info("[VisionImageService] chooseBestTextBox result: index={}, score={}, reason={}",
                    out.get("llm_index"), out.get("llm_score"), out.get("llm_reason"));

            return out;

        } catch (Exception e) {
            log.warn("[VisionImageService] chooseBestTextBox error: {}", e.toString());
            return null;
        }
    }


    private String extractTargetText(String visionPrompt) {
        if (visionPrompt == null) return null;
        // 第一版简单一点：整串当目标。后面你可以规范成 JSON，比如 { "target_text": "微信" }
        return visionPrompt.trim();
    }

    /**
     * 简单把 Object 转成 int，出错就给默认值。
     */
    private int asInt(Object v, int defaultValue) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignore) {
            }
        }
        return defaultValue;
    }

    private record ModelRouting(String profileName, String modelId) {}

    private ModelRouting resolveRouting(String profileName, String defaultModel) {
        AiMultiModelProperties.ModelProfile profile = null;
        com.example.runtime.RuntimeConfig.ModelProfileDto runtimeProfile =
                effectiveProps != null && effectiveProps.runtimeProfiles() != null
                        ? effectiveProps.runtimeProfiles().get(profileName)
                        : null;

        if (multiModelProperties != null) {
            try {
                profile = multiModelProperties.findProfile(profileName);
                log.debug("[VisionImageService] Static profile loaded for {}: {}", profileName,
                        profile != null ? "found" : "not found");
            } catch (Exception e) {
                log.warn("[VisionImageService] Failed to read multi-model profile '{}': {}", profileName, e.toString());
            }
        }

        String model = defaultModel;
        if (runtimeProfile != null && StringUtils.hasText(runtimeProfile.getModelId())) {
            model = runtimeProfile.getModelId();
        } else if (profile != null && StringUtils.hasText(profile.getModelId())) {
            model = profile.getModelId();
        }

        return new ModelRouting(profileName, model);
    }



}
