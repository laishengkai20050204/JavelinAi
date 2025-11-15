package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.storage.MinioProps;
import com.example.storage.StorageService;
import com.example.tools.AiTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class ListUserConversationFilesTool implements AiTool {

    private final AiFileService aiFileService;
    private final StorageService storageService;
    private final MinioProps minioProps;

    @Override
    public String name() {
        return "list_user_files";
    }

    @Override
    public String description() {
        // 说明清楚：是“当前用户 + 当前会话”，从上下文注入，不需要模型自己传 userId/convId
        return "List recent files uploaded by the **current user in the current conversation**. "
                + "The userId and conversationId are taken from the execution context automatically; "
                + "you only need to optionally provide `limit`.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("limit", Map.of(
                "type", "integer",
                "description", "Max number of recent files to return (1-50). Default is 10.",
                "minimum", 1,
                "maximum", 50,
                "default", 10
        ));

        schema.put("properties", props);
        // ⭐ 不再要求 userId / conversationId，甚至一个 required 都不需要
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        // ⭐ 这里直接从 args 取（AiToolExecutor 已经帮你塞进来了）
        String userId = Objects.toString(args.get("userId"), null);
        String conversationId = Objects.toString(args.get("conversationId"), null);
        int limit = normalizeInt(args.get("limit"), 10, 1, 50);

        if (userId == null || conversationId == null) {
            // 正常通过 AiToolExecutor 调用不会发生，这里只是兜底
            String msg = "userId / conversationId missing from execution context";
            log.warn("[list_user_files] {}", msg);
            return ToolResult.error(null, name(), msg);
        }

        log.debug("[list_user_files] userId={} conversationId={} limit={}",
                userId, conversationId, limit);

        List<AiFile> all = aiFileService.listUserConversationFiles(userId, conversationId);
        if (all == null || all.isEmpty()) {
            String summary = String.format("No files found for user %s in conversation %s.", userId, conversationId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", userId);
            data.put("conversationId", conversationId);
            data.put("count", 0);
            data.put("files", List.of());
            data.put("_source", "ai_file");
            data.put("text", summary);
            return ToolResult.success(null, name(), false, data);
        }

        List<AiFile> files = all.size() > limit ? all.subList(0, limit) : all;

        Duration expiry = Duration.ofSeconds(minioProps.getPresignExpirySeconds());

        List<Map<String, Object>> fileDtos = files.stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.getId());
                    m.put("userId", f.getUserId());
                    m.put("conversationId", f.getConversationId());
                    m.put("bucket", f.getBucket());
                    m.put("objectKey", f.getObjectKey());
                    m.put("filename", f.getFilename());
                    m.put("sizeBytes", f.getSizeBytes());
                    m.put("mimeType", f.getMimeType());
                    m.put("sha256", f.getSha256());
                    try { m.put("createdAt", f.getCreatedAt()); } catch (Exception ignore) {}

                    try {
                        String url = storageService
                                .presignGet(f.getBucket(), f.getObjectKey(), expiry)
                                .block(expiry);
                        m.put("url", url);
                    } catch (Exception e) {
                        log.warn("[list_user_files] presignGet failed for fileId={} bucket={} key={}",
                                f.getId(), f.getBucket(), f.getObjectKey(), e);
                        m.put("url", null);
                    }
                    return m;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("conversationId", conversationId);
        data.put("count", fileDtos.size());
        data.put("files", fileDtos);
        data.put("_source", "ai_file");

        String summary = String.format("Found %d file(s) (limit %d) for user %s in conversation %s.",
                fileDtos.size(), limit, userId, conversationId);
        data.put("text", summary);

        return ToolResult.success(null, name(), false, data);
    }

    private static int normalizeInt(Object value, int def, int min, int max) {
        int n = def;
        if (value instanceof Number num) {
            n = num.intValue();
        } else if (value instanceof String s) {
            try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }
}
