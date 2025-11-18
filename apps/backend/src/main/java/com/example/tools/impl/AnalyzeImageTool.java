package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.config.EffectiveProps;
import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.storage.StorageService;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class AnalyzeImageTool implements AiTool {

    private final AiFileService aiFileService;
    private final StorageService storageService;
    private final PythonExecTool pythonExecTool;
    private final ObjectMapper objectMapper;
    private final EffectiveProps effectiveProps;  // ✅ 使用运行时配置

    // 视觉模型名可以先写死 qwen-vl-plus，将来需要也可以做成运行时配置
    private final String visionModel = "qwen-vl-plus";

    // 调 Qwen 的超时（毫秒）
    private final int visionTimeoutMs = 60_000;

    // 需要可以关掉就改成运行时开关，这里先始终开启
    private final boolean visionEnable = true;

    @Override
    public String name() {
        return "analyze_image";
    }

    @Override
    public String description() {
        return "Analyze a user-uploaded image file and return basic metadata (width/height) and a natural language "
                + "caption using a multimodal vision model (e.g., Qwen-VL). "
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

        // ✅ 新增：vision_prompt，可选
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

        // ✅ 读取主 LLM 传过来的提示词（可为空）
        String visionPrompt = null;
        Object vpObj = args.get("vision_prompt");
        if (vpObj != null) {
            String s = vpObj.toString().trim();
            if (!s.isEmpty()) {
                visionPrompt = s;
            }
        }

        if (fileId == null || fileId.isBlank()) {
            String msg = "Missing required parameter 'file_id'.";
            log.warn("[analyze_image] {}", msg);
            return ToolResult.error(null, name(), msg);
        }

        if (userId == null || conversationId == null) {
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
        if (!userId.equals(f.getUserId())) {
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
                if (basic.get("width") instanceof Number n) {
                    width = n.intValue();
                }
                if (basic.get("height") instanceof Number n) {
                    height = n.intValue();
                }
            }
        } catch (Exception e) {
            log.warn("[analyze_image] basic image analysis failed for fileId={}", fileId, e);
            analysis.put("isImage", false);
            analysis.put("message", "Failed to read image from storage: " + e.getMessage());
        }

        // 4) Qwen-VL 视觉描述（使用运行时配置的 baseUrl + apiKey）
        if (visionEnable && isImage) {
            String imageUrl = buildDataUrl(f);   // ✅ 改成 Data URL

            if (imageUrl != null && !imageUrl.isBlank()) {
                String apiKey = effectiveProps.apiKey();
                String baseUrl = effectiveProps.baseUrl();
                Map<String, Object> vision = callQwenVision(
                        userId, conversationId, imageUrl, apiKey, baseUrl, visionPrompt
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
     * 用 python_exec 在容器里调用 Qwen-VL (DashScope OpenAI 兼容接口)。
     * 使用 EffectiveProps 提供的 apiKey/baseUrl，而不是环境变量。
     */
    private Map<String, Object> callQwenVision(
            String userId,
            String conversationId,
            String imageUrl,
            String apiKey,
            String baseUrl,
            String customPrompt
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            String msg = "API key is not configured in EffectiveProps.apiKey().";
            log.warn("[analyze_image] {}", msg);
            return Map.of("vision_error", msg);
        }

        // ✅ 统一规范 baseUrl（补 /v1）
        baseUrl = normalizeBaseUrl(baseUrl);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("image_url", imageUrl);
        cfg.put("model", visionModel);
        cfg.put("api_key", apiKey);
        cfg.put("base_url", baseUrl);

        // ✅ 把主 LLM 想好的提示词传给 Python/Qwen（可以为 null）
        if (customPrompt != null && !customPrompt.isBlank()) {
            cfg.put("prompt", customPrompt);
        }

        String cfgJson;
        try {
            cfgJson = objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            log.warn("[analyze_image] failed to serialize vision cfg", e);
            return Map.of("vision_error", "serialize_cfg_failed: " + e.getMessage());
        }

        String safeCfg = cfgJson
                .replace("\\", "\\\\")
                .replace("'", "\\'");

        String code = """
import json
from openai import OpenAI

cfg = json.loads('%s')

def main():
    image_url = cfg.get("image_url")
    # 让模型按固定格式输出四段内容：概要 / 详细 / 原文 / tags
    prompt = cfg.get("prompt") or (
        "请分四部分用中文和英文输出本图片的信息，严格按照下面格式：\\n"
        "1) 第一行以“概要：”开头，用一句话非常简要概括图片主要内容；\\n"
        "2) 第二行以“详细：”开头，用较详细的语言解释图片中的关键信息、人物/物体、动作和场景；\\n"
        "3) 第三行以“原文：”开头，如果图片中包含文字、公式或屏幕内容，请尽量逐字转写出来（可用 Markdown/LaTeX），如果没有文字就写“无”；\\n"
        "4) 第四行以“tags: ”开头，给出若干英文标签，用逗号分隔，例如：tags: math, formula, fourier, signal-processing。"
    )
    model = cfg.get("model") or "qwen-vl-plus"

    api_key = cfg.get("api_key")
    base_url = cfg.get("base_url") or "https://dashscope.aliyuncs.com/compatible-mode/v1"

    if not api_key:
        raise RuntimeError("API key (cfg.api_key) is empty")

    client = OpenAI(
        api_key=api_key,
        base_url=base_url,
    )

    completion = client.chat.completions.create(
        model=model,
        messages=[{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": image_url}},
                {"type": "text", "text": prompt}
            ]
        }]
    )

    msg = completion.choices[0].message
    content = msg.content
    # openai v1: content 可以是 str 或 list[part]
    if isinstance(content, list):
        text = "".join(
            (part.get("text", "") if isinstance(part, dict) else str(part))
            for part in content
        )
    else:
        text = str(content)

    # 解析四段：概要 / 详细 / 原文 / tags
    caption_brief = None
    caption_detail = None
    original = None
    tags = []

    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    for ln in lines:
        lower = ln.lower()
        if ln.startswith("概要：") or ln.startswith("概要:"):
            caption_brief = ln.split("：", 1)[-1] if "：" in ln else ln.split(":", 1)[-1]
            caption_brief = caption_brief.strip()
        elif ln.startswith("详细：") or ln.startswith("详细:"):
            caption_detail = ln.split("：", 1)[-1] if "：" in ln else ln.split(":", 1)[-1]
            caption_detail = caption_detail.strip()
        elif ln.startswith("原文：") or ln.startswith("原文:"):
            original = ln.split("：", 1)[-1] if "：" in ln else ln.split(":", 1)[-1]
            original = original.strip()
        elif lower.startswith("tags:"):
            tags_part = ln[len("tags:"):].strip()
            raw_tags = [t.strip() for t in tags_part.replace("，", ",").split(",")]
            tags = [t for t in raw_tags if t]

    # 回退策略：如果模型没完全遵守格式，就用整段 text 顶上
    if not caption_detail and caption_brief:
        caption_detail = caption_brief
    if not caption_brief and caption_detail:
        caption_brief = caption_detail
    if not caption_brief and not caption_detail:
        caption_brief = text.strip()
        caption_detail = text.strip()

    result = {
        "caption_brief": caption_brief,
        "caption_detail": caption_detail,
        "original": original,
        "tags": tags,
        "raw": text,
    }
    # 为了兼容旧代码，caption 用详细版
    result["caption"] = caption_detail

    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
""" .formatted(safeCfg);


        Map<String, Object> pyArgs = new LinkedHashMap<>();
        pyArgs.put("user_id", userId);
        pyArgs.put("conversation_id", conversationId);
        pyArgs.put("code", code);
        pyArgs.put("pip", List.of("openai>=1.0.0,<2.0.0"));
        pyArgs.put("timeout_ms", visionTimeoutMs);

        try {
            ToolResult r = pythonExecTool.execute(pyArgs);
            if (!"SUCCESS".equals(r.status())) {
                Object dataObj = r.data();
                String msg;
                if (dataObj instanceof Map<?,?> m && m.get("message") != null) {
                    msg = String.valueOf(m.get("message"));
                } else {
                    msg = "python_exec status=" + r.status();
                }
                log.warn("[analyze_image] python_exec vision failed: {}", msg);
                return Map.of("vision_error", msg);
            }

            Object dataObj = r.data();
            if (!(dataObj instanceof Map<?,?> dataMap)) {
                return Map.of("vision_error", "python_exec data is not a map");
            }

            Object stdoutObj = dataMap.get("stdout");
            if (stdoutObj == null) {
                return Map.of("vision_error", "python_exec stdout is null");
            }
            String stdout = stdoutObj.toString().trim();
            if (stdout.isEmpty()) {
                return Map.of("vision_error", "python_exec stdout is empty");
            }

            JsonNode node = objectMapper.readTree(stdout);
            Map<String, Object> result = new LinkedHashMap<>();

// 新增字段：caption_brief / caption_detail / original
            if (node.hasNonNull("caption_brief")) {
                result.put("caption_brief", node.get("caption_brief").asText());
            }
            if (node.hasNonNull("caption_detail")) {
                result.put("caption_detail", node.get("caption_detail").asText());
            }
            if (node.hasNonNull("original")) {
                result.put("original", node.get("original").asText());
            }

// 兼容字段：caption（默认用详细版）
            String caption = null;
            if (node.hasNonNull("caption")) {
                caption = node.get("caption").asText();
            } else if (node.hasNonNull("caption_detail")) {
                caption = node.get("caption_detail").asText();
            }
            if (caption != null) {
                result.put("caption", caption);
            }

            if (node.has("tags") && node.get("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                for (JsonNode t : node.get("tags")) {
                    tags.add(t.asText());
                }
                result.put("tags", tags);
            }
            if (node.hasNonNull("raw")) {
                result.put("raw", node.get("raw").asText());
            }
            return result;

        } catch (Exception e) {
            log.warn("[analyze_image] vision call exception", e);
            return Map.of("vision_error", e.getMessage());
        }
    }

    /**
     * 把 MinIO 里的图片读出来，转成 data:[mime];base64,xxxx 形式。
     * 这里为了安全，限制最大 1MB（可按需调大），避免超大图直接塞进请求。
     */
    private String buildDataUrl(AiFile f) {
        long size = Optional.ofNullable(f.getSizeBytes()).orElse(0L);
        long MAX_INLINE = 4L * 1024 * 1024; // 1MB，你可以改成 2MB/4MB
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
                                if ((len = in.read(buf)) == -1) break;
                                bos.write(buf, 0, len);
                            } catch (IOException e) {
                                log.error("读取文件失败 ", e);
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
     * - 如果是 dashscope.aliyuncs.com 且路径是 /compatible-mode 或 /compatible-mode/，自动补上 /v1
     * - 其他情况原样返回（比如已经是 .../v1，或者是 OpenAI / GLM 的地址）
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String trimmed = baseUrl.trim();
        // 去掉末尾多余的 '/'
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // 只对 dashscope 的地址做智能补全
        if (trimmed.contains("dashscope.aliyuncs.com")) {
            // 已经有 /v1 就不动
            if (trimmed.endsWith("/v1")) {
                return trimmed;
            }
            // 如果刚好到 /compatible-mode，就补 /v1
            if (trimmed.endsWith("/compatible-mode")) {
                return trimmed + "/v1";
            }
        }

        // 其他 provider 保持原样（比如已经是 /v1 或者本身就是别家的）
        return trimmed;
    }

}
