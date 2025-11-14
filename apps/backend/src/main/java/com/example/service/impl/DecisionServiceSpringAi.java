package com.example.service.impl;

import com.example.ai.SpringAiChatGateway;
import com.example.api.dto.*;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.infra.FinalAnswerStreamManager;
import com.example.infra.StepSseHub;
import com.example.service.ConversationMemoryService;
import com.example.service.DecisionService;
import com.example.tools.support.JsonCanonicalizer;
import com.example.util.Fingerprint;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DecisionServiceSpringAi implements DecisionService {

    private final SpringAiChatGateway gateway;
    private final AiProperties props;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper mapper;
    private final ConversationMemoryService memoryService;
    private final Map<String, Set<String>> decisionSeen = new ConcurrentHashMap<>();

    // ★ 新增：用于把“决策”也做成流（T 型分支）
    private final FinalAnswerStreamManager streamMgr;
    private final StepSseHub sseHub;

    @Override
    public Mono<ModelDecision> decide(StepState st, AssembledContext ctx) {
        List<Map<String, Object>> messages =
                (ctx != null && ctx.modelMessages() != null) ? ctx.modelMessages() : List.of();

        String m2Digest = MsgTrace.digest(messages);
        log.trace("[TRACE M2] before gateway size={} last={} digest={}",
                messages.size(), MsgTrace.lastLine(messages), m2Digest);

        // —— 统一的 payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", effectiveProps.model());
        payload.put("messages", messages);
        payload.put("_flattened", true);
        payload.put("_tamperSeal", m2Digest);
        // 工具选择：保持你现有逻辑
        if (st != null && st.req() != null) {
            if (st.req().clientTools() != null && !st.req().clientTools().isEmpty()) {
                // 网关会从 "clientTools" 和 "tools" 两个键读取；都放上更稳妥
                payload.put("clientTools", st.req().clientTools());
                payload.putIfAbsent("tools", st.req().clientTools());
            }
            if (st.req().tool_calls() != null && !st.req().tool_calls().isEmpty()) {
                payload.put("tool_calls", st.req().tool_calls());
            }
            if (org.springframework.util.StringUtils.hasText(st.req().toolChoice())) {
                // 你的网关用的是 "toolChoice"（驼峰），如果后面有需要也可同时放一个 "tool_choice"
                payload.put("toolChoice", st.req().toolChoice());
            }
        }

        // ==== A) 开关打开：用“流式决策” ====
        if (Boolean.TRUE.equals(effectiveProps.streamDecision())) {
            String streamId = st.stepId() + ":decision";

            // 1) 启动 provider 流
            streamMgr.start(streamId, payload);

            // 2) 把“决策流”的 token 直接转给同一个 step 的 SSE（事件名仍是 "message"）
            sseHub.forward(st.stepId(), streamMgr.sse(streamId, effectiveProps.model()));

            // 3) 等聚合结果（content + tool_calls）
            Duration idle = java.util.Optional.ofNullable(effectiveProps.streamTimeoutMs())
                    .map(java.time.Duration::ofMillis)
                    .orElse(java.time.Duration.ofSeconds(90));
            return streamMgr.awaitAggregated(streamId, idle)
                    .map(agg -> {
                        // 3.1 解析 tool_calls -> List<ToolCall>
                        List<ToolCall> calls = new ArrayList<>();
                        Set<String> clientNames = clientToolNames(st);

                        for (Map<String,Object> item : agg.toolCalls) {
                            String id = asString(item.getOrDefault("id", "call-" + UUID.randomUUID()));
                            @SuppressWarnings("unchecked")
                            Map<String,Object> fn = (Map<String,Object>) item.getOrDefault("function", Map.of());
                            String name = asString(fn.get("name"));
                            String argsJson = asString(fn.getOrDefault("arguments", "{}"));
                            Map<String,Object> args = safeParseMap(argsJson);
                            String target = clientNames.contains(name) ? "CLIENT" : "SERVER";
                            calls.add(ToolCall.of(id, name, args, target));
                        }

                        String assistantDraft = StringUtils.hasText(agg.content) ? agg.content : null;

                        // 3.2（可选）持久化一条“决策草稿”，与你原有 call() 分支一致
                        if (!calls.isEmpty()
                                && st != null && st.req() != null
                                && StringUtils.hasText(st.req().userId())
                                && StringUtils.hasText(st.req().conversationId())) {
                            String fp = fingerprintToolCalls(calls);
                            if (markDecisionOnce(st.stepId(), fp)) {
                                persistAssistantDecisionDraft(
                                        memoryService, mapper,
                                        st.req().userId(), st.req().conversationId(), st.stepId(),
                                        calls, assistantDraft
                                );
                            }
                        }

                        return new ModelDecision(calls, assistantDraft);
                    })
                    .doFinally(sig -> streamMgr.clear(streamId));
        }

        // ==== B) 默认：保持老逻辑（非流） ====
        AiProperties.Mode mode = effectiveProps.mode();
        return gateway.call(payload, mode)
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);
                        List<ToolCall> calls = parseToolCallsWithFallback(root, clientToolNames(st));
                        String draft = extractAssistantDraft(root);
                        if (!calls.isEmpty() && st != null && st.req() != null
                                && StringUtils.hasText(st.req().userId())
                                && StringUtils.hasText(st.req().conversationId())) {
                            String fp = fingerprintToolCalls(calls);
                            if (markDecisionOnce(st.stepId(), fp)) {
                                persistAssistantDecisionDraft(
                                        memoryService, mapper,
                                        st.req().userId(), st.req().conversationId(), st.stepId(),
                                        calls, draft
                                );
                            }
                        }
                        return new ModelDecision(calls, (draft != null && !draft.isBlank()) ? draft : null);
                    } catch (Exception e) {
                        return ModelDecision.empty();
                    }
                });
    }

    @Override
    public void clearStep(String stepId) {
        if (stepId == null) return;
        decisionSeen.remove(stepId);
    }

    private String asString(Object o) { return (o == null) ? null : o.toString(); }

    private String extractAssistantDraft(JsonNode root) {
        String c1 = root.path("choices").path(0).path("message").path("content").asText("");
        if (org.springframework.util.StringUtils.hasText(c1)) return c1;
        String c2 = root.path("_provider_assistant").path("content").asText("");
        return org.springframework.util.StringUtils.hasText(c2) ? c2 : null;
    }

    /** 先从 choices[0].message.tool_calls 取；没有则回退到 _provider_assistant.tool_calls */
    private List<ToolCall> parseToolCallsWithFallback(JsonNode root, Set<String> clientNames) {
        // primary: choices[0].message.tool_calls
        JsonNode primary = root.path("choices").path(0).path("message").path("tool_calls");
        List<ToolCall> out = nodeToToolCalls(primary, clientNames);
        if (!out.isEmpty()) return out;

        // fallback: _provider_assistant.tool_calls
        JsonNode fallback = root.path("_provider_assistant").path("tool_calls");
        out = nodeToToolCalls(fallback, clientNames);
        return out;
    }

    private List<ToolCall> nodeToToolCalls(JsonNode toolCalls, Set<String> clientNames) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.size() == 0) return List.of();
        List<ToolCall> out = new ArrayList<>();
        for (JsonNode node : toolCalls) {
            String id = node.path("id").asText("call-" + UUID.randomUUID());
            String name = node.path("function").path("name").asText("");
            String argsJson = node.path("function").path("arguments").asText("{}");
            Map<String, Object> args = safeParseMap(argsJson);
            String target = clientNames.contains(name) ? "CLIENT" : "SERVER";
            out.add(ToolCall.of(id, name, args, target));
        }
        return out;
    }


    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...(truncated)";
    }



    // ---- helpers ----



    private Map<String, Object> msg(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private Set<String> clientToolNames(StepState st) {
        if (st == null || st.req() == null || st.req().clientTools() == null) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (Object o : st.req().clientTools()) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object fn = m.get("function");
            if (fn instanceof Map<?, ?> fm) {
                Object n = fm.get("name");
                if (n != null && org.springframework.util.StringUtils.hasText(n.toString())) {
                    names.add(n.toString());
                }
            }
        }
        return names;
    }

    private List<ToolCall> parseDecisionFromJson(String json, Set<String> clientNames) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode msg = root.path("choices").path(0).path("message");
            JsonNode toolCalls = msg.path("tool_calls");
            if (!toolCalls.isArray()) return List.of();

            List<ToolCall> out = new ArrayList<>();
            for (JsonNode node : toolCalls) {
                String id = node.path("id").asText("call-" + UUID.randomUUID());
                String name = node.path("function").path("name").asText("");
                String argsJson = node.path("function").path("arguments").asText("{}");
                Map<String, Object> args = safeParseMap(argsJson);
                String target = clientNames.contains(name) ? "CLIENT" : "SERVER";
                out.add(ToolCall.of(id, name, args, target));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseMap(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            String preview = truncate(json, 512);
            log.error("[decision] invalid tool arguments JSON, fallback to empty map. preview={}", preview, ex);
            return Map.of();
        }
    }

    private void persistAssistantDecisionDraft(
            ConversationMemoryService memoryService,
            ObjectMapper objectMapper,
            String userId,
            String conversationId,
            String stepId,
            List<ToolCall> calls,
            String assistantDraft
    ) {
        try {
            Integer max = memoryService.findMaxSeq(userId, conversationId, stepId);
            int seq = (max == null ? 0 : max) + 1;

            // 统一保存成 OpenAI 风格结构，后续回灌最稳
            List<Map<String, Object>> tcPayload = new ArrayList<>();
            for (var c : calls) {
                tcPayload.add(Map.of(
                        "id",        c.id(),
                        "type",      "function",
                        "function",  Map.of(
                                "name", c.name(),
                                // 这里一定是字符串；若你有 stableArgs(mapper) 可直接用
                                "arguments", c.stableArgs(objectMapper)
                        )
                ));
            }

            Map<String, Object> payload = Map.of(
                    "source",     "model",
                    "type",       "assistant_decision",
                    "tool_calls", tcPayload,
                    "stepId",     stepId
            );

            // 这里把 content 写成 LLM 的文本草稿（可能是对工具调用的解释/过场话术）
            String contentToSave = (assistantDraft != null && !assistantDraft.isBlank())
                    ? assistantDraft
                    : "";

            memoryService.upsertMessage(
                    userId,
                    conversationId,
                    "assistant",                 // ← 决策来自 assistant
                    contentToSave,                          // content 为空（只存决策结构）
                    objectMapper.writeValueAsString(payload),
                    stepId,
                    seq,
                    "DRAFT"                      // 本轮结束再 promote
            );

            log.debug("[memory] decision draft persisted: user={} conv={} step={} tcs={}",
                    userId, conversationId, stepId, tcPayload.size());
        } catch (Exception e) {
            log.warn("[memory] persist assistant decision failed: user={} conv={} step={} err={}",
                    userId, conversationId, stepId, e.toString());
        }
    }

    private boolean markDecisionOnce(String stepId, String decisionFp) {
        return decisionSeen
                .computeIfAbsent(stepId, k -> ConcurrentHashMap.newKeySet())
                .add(decisionFp);
    }

    private String fingerprintToolCalls(List<ToolCall> calls) {
        List<String> parts = new ArrayList<>();
        for (ToolCall c : calls) {
            String argsStr = c.stableArgs(mapper); // 你的 ToolCall 已有稳定参数方法
            try {
                // 规范化，忽略抖动字段（按你去重账本的习惯）
                var canon = JsonCanonicalizer.normalize(
                        mapper,
                        mapper.readTree(argsStr),
                        Set.of("nonce","timestamp","requestId")
                ).toString();
                parts.add(c.name() + "::" + canon);
            } catch (Exception e) {
                // 兜底：解析失败也别中断
                parts.add(c.name() + "::" + String.valueOf(argsStr));
            }
        }
        Collections.sort(parts);
        return Fingerprint.sha256(String.join("|", parts));
    }


}
