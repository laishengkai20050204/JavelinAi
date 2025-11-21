package com.example.tools;

import com.example.api.dto.ToolResult;
import com.example.tools.support.ToolDeduplicator;
import com.example.tools.support.JsonCanonicalizer;
import com.example.config.DedupProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 增强点：
 * 1) 执行前按 (toolName + canonical(args - ignore)) 生成 argsHash�?
 * 2) 若未 force 且命中账�?未过�? => 直接复用�?
 * 3) 否则执行并把结果写入账本(�?TTL)�?
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiToolExecutor {

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    // NEW: 注入去重配置与账本服�?
    private final DedupProperties dedupProps;
    private final ToolDeduplicator dedup;

    private static final Set<String> PROTECTED_SCOPE_KEYS = Set.of("userId", "conversationId");

    public record ToolCall(String id, String name, String argumentsJson) {}

    public Map<String, Object> toAssistantToolCallsMessage(List<ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ToolCall call : calls) {
            log.trace("Preparing assistant tool call message id={} name={}", call.id(), call.name());
            arr.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", Objects.requireNonNullElse(call.argumentsJson(), "{}")
                    )
            ));
        }
        return Map.of(
                "role", "assistant",
                "tool_calls", arr,
                "content", ""
        );
    }

    public List<Map<String, Object>> executeAll(List<ToolCall> calls,
                                                Map<String, Object> fallbackArgs) throws Exception {
        log.debug("Executing {} tool call(s)", calls.size());
        List<Map<String, Object>> results = new ArrayList<>();

        for (ToolCall call : calls) {
            log.debug("Executing tool call id={} name={}", call.id(), call.name());
            AiTool tool = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + call.name()));

            // 1) 解析参数
            Map<String, Object> args = mapper.readValue(
                    call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            // 2) 合并上下文作用域参数（确定userId / conversationId 一定存在且不可被覆盖）
            if (fallbackArgs != null) {
                // 先覆盖受保护 key（userId, conversationId）
                fallbackArgs.forEach((key, value) -> {
                    if (value != null && PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.put(key, value);
                    }
                });
                // 再补充其他 key（模型参数优先）
                fallbackArgs.forEach((key, value) -> {
                    if (!PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.putIfAbsent(key, value);
                    }
                });
            }

            // 2.5 ★★★ 在这里统一 userId / user_id, conversationId / conversation_id
            Object userIdVal = args.get("userId");
            if (userIdVal == null) userIdVal = args.get("user_id");
            Object convIdVal = args.get("conversationId");
            if (convIdVal == null) convIdVal = args.get("conversation_id");

            if (userIdVal != null) {
                args.put("userId", userIdVal);
                args.put("user_id", userIdVal);
            }
            if (convIdVal != null) {
                args.put("conversationId", convIdVal);
                args.put("conversation_id", convIdVal);
            }

            // 3) 现在再读 userId / conversationId，保证这俩一定是归一化后的值
            String userId = Objects.toString(args.get("userId"), null);
            String conversationId = Objects.toString(args.get("conversationId"), null);
            boolean force = Boolean.TRUE.equals(args.get("force"));
            int ttlSeconds = dedupProps.getDefaultTtlSeconds();
            if (args.get("ttlSeconds") instanceof Number n && n.intValue() > 0) {
                ttlSeconds = n.intValue();
            }

            log.debug("[DEDUP-CHECK] tool={} enabled={} force={} userId={} convId={} ttl={}",
                    tool.name(), dedupProps.isEnabled(), force, userId, conversationId, ttlSeconds);

            String contentJsonToReturn;

            if (dedupProps.isEnabled() && !force && userId != null && conversationId != null) {

                // 3.1 计算参数指纹（忽�?timestamp/requestId/nonce 等抖动字段）
                Set<String> ignore = new HashSet<>(dedupProps.getIgnoreArgs());
                JsonNode rawArgsNode = mapper.valueToTree(args);
                JsonNode canonicalArgs = JsonCanonicalizer.normalize(mapper, rawArgsNode, ignore);
                String argsHash = dedup.fingerprint(tool.name(), canonicalArgs);

                log.debug("[DEDUP-ARGS] tool={} ignore={} raw={} canon={} hash={}",
                        tool.name(), ignore, rawArgsNode, canonicalArgs, argsHash);

                log.debug("[DEDUP-LOOKUP] tool={} user={} conv={} hash={}",
                        tool.name(), userId, conversationId, argsHash);

                // 3.2 账本命中直接复用
                Optional<String> cached = dedup.tryReuse(userId, conversationId, tool.name(), argsHash);
                if (cached.isPresent()) {
                    contentJsonToReturn = cached.get();

                    log.debug("[DEDUP-HIT] tool={} hash={} reused=true",
                            tool.name(), argsHash);

                    results.add(Map.of(
                            "role", "tool",
                            "tool_call_id", call.id(),
                            "content", contentJsonToReturn
                    ));
                    continue;
                }

                log.debug("[DEDUP-MISS] tool={} hash={} -> execute", tool.name(), argsHash);

                ToolResult result;
                try {
                    result = tool.execute(args);
                } catch (Exception ex) {
                    log.error("[EXEC-ERR] tool={} ex={}: {}", tool.name(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
                    throw ex;
                }
                contentJsonToReturn = mapper.writeValueAsString(result.data());

                log.debug("[EXEC-OK] tool={} branch=dedup payloadLen={} sample={}",
                        tool.name(),
                        (contentJsonToReturn == null ? 0 : contentJsonToReturn.length()),
                        contentJsonToReturn == null ? "null" : contentJsonToReturn.substring(0, Math.min(120, contentJsonToReturn.length())));

                log.debug("[DEDUP-SAVE] tool={} user={} conv={} hash={} ttl={}",
                        tool.name(), userId, conversationId, argsHash, ttlSeconds);

                dedup.saveSuccess(userId, conversationId, tool.name(), argsHash,
                        mapper.valueToTree(args), mapper.readTree(Objects.requireNonNullElse(contentJsonToReturn, "null")),
                        ttlSeconds);

                results.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.id(),
                        "content", contentJsonToReturn
                ));
                log.debug("Tool '{}' call id={} persisted SUCCESS, payloadLength={}",
                        tool.name(), call.id(), contentJsonToReturn != null ? contentJsonToReturn.length() : 0);
                continue;
            }

            ToolResult result;
            try {
                result = tool.execute(args);
            } catch (Exception ex) {
                log.error("[EXEC-ERR] tool={} ex={}: {}", tool.name(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
                throw ex;
            }
            contentJsonToReturn = mapper.writeValueAsString(result.data());

            // ★【哨�?'】执行成功（非去重分支）
            log.debug("[EXEC-OK] tool={} branch=no-dedup payloadLen={} sample={}",
                    tool.name(),
                    (contentJsonToReturn == null ? 0 : contentJsonToReturn.length()),
                    contentJsonToReturn == null ? "null" : contentJsonToReturn.substring(0, Math.min(120, contentJsonToReturn.length())));

            // 这里你已有“审计保存（no-dedup 分支）”，先别改逻辑；只在内部补两条日志更清晰：
            if (userId != null && conversationId != null) {
                try {
                    Set<String> ignore = new HashSet<>(dedupProps.getIgnoreArgs());
                    JsonNode canonicalArgs = JsonCanonicalizer.normalize(mapper, mapper.valueToTree(args), ignore);
                    String argsHash = dedup.fingerprint(tool.name(), canonicalArgs);

                    // ★ 准备保存（非去重分支）
                    log.debug("[AUDIT-SAVE] tool={} user={} conv={} hash={} ttl={}",
                            tool.name(), userId, conversationId, argsHash, dedupProps.getDefaultTtlSeconds());

                    dedup.saveSuccess(userId, conversationId, tool.name(), argsHash,
                            mapper.valueToTree(args), mapper.readTree(Objects.requireNonNullElse(contentJsonToReturn, "null")),
                            /*ttlSeconds*/ dedupProps.getDefaultTtlSeconds());

                    log.debug("Tool '{}' call id={} persisted SUCCESS (no-dedup branch)", tool.name(), call.id());
                } catch (Exception e) {
                    log.error("[AUDIT-ERROR] tool={} err={}", tool.name(), e.toString(), e);
                }
            } else {
                log.debug("[AUDIT-SKIP] tool={} reason=missing ids userId={} convId={}", tool.name(), userId, conversationId);
            }

            results.add(Map.of(
                    "role", "tool",
                    "tool_call_id", call.id(),
                    "content", contentJsonToReturn
            ));
            log.debug("Tool '{}' call id={} produced payloadLength={}",
                    tool.name(), call.id(), contentJsonToReturn != null ? contentJsonToReturn.length() : 0);
        }

        log.debug("Completed execution of {} tool call(s)", results.size());
        return results;
    }

}
