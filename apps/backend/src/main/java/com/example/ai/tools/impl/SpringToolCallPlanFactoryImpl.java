package com.example.ai.tools.impl;

import com.example.ai.tools.SpringAiToolAdapter;
import com.example.ai.tools.ToolCallPlan;
import com.example.ai.tools.ToolCallPlanFactory;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * �?payload + EffectiveProps + SpringAiToolAdapter 统一转成 ToolCallPlan�?
 *
 * - 这里做完所有“工具选择”逻辑（tool_choice / tools / clientTools / toggles / tool_context）；
 * - 不再关心 Spring AI �?ToolCallingChatOptions�? */
@Component
@RequiredArgsConstructor
public class SpringToolCallPlanFactoryImpl implements ToolCallPlanFactory {

    private final EffectiveProps effectiveProps;
    private final SpringAiToolAdapter toolAdapter;
    private final ObjectMapper mapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public ToolCallPlan buildPlan(Map<String, Object> payload, AiProperties.Mode mode) {

        // 1) 模型名（请求优先，其�?runtime 配置�?
        String modelFromPayload = coerceString(payload.get("model"));
        String model = StringUtils.hasText(modelFromPayload)
                ? modelFromPayload
                : effectiveProps.model();

        // 2) 温度（可选）
        Double temperature = null;
        Object tempObj = payload.get("temperature");
        if (tempObj instanceof Number n) {
            temperature = n.doubleValue();
        } else if (tempObj instanceof String s && StringUtils.hasText(s)) {
            try {
                temperature = Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }

        // 3) tool_choice / toolChoice
        Object rawToolChoice = payload.containsKey("toolChoice")
                ? payload.get("toolChoice")
                : payload.get("tool_choice");
        String normalizedToolChoice = normalizeToolChoice(rawToolChoice);
        String forcedFunction = forcedFunctionName(rawToolChoice);

        // 4) 解析 tools + clientTools 定义
        Map<String, ToolCallPlan.ToolDef> mergedDefs = new LinkedHashMap<>();
        collectToolDefsFromPayload(payload.get("tools"), mergedDefs);
        collectToolDefsFromPayload(payload.get("clientTools"), mergedDefs);

        // 5) 补上服务端注册的工具定义（若未被请求体覆盖）
        for (ToolCallback cb : toolAdapter.toolCallbacks()) {
            ToolDefinition definition = cb.getToolDefinition();
            if (definition == null) {
                continue;
            }
            String name = definition.name();
            if (!StringUtils.hasText(name)) continue;
            if (mergedDefs.containsKey(name)) continue; // 请求体优先覆盖描�?schema
            JsonNode schema = safeParseSchema(definition.inputSchema());
            String desc = definition.description();
            mergedDefs.put(name, new ToolCallPlan.ToolDef(
                    name,
                    desc != null ? desc : "",
                    schema,
                    "server"
            ));
        }

        // 6) 基础允许集合：所有工具名（请�?+ 服务端）
        LinkedHashSet<String> allowed = mergedDefs.values().stream()
                .map(ToolCallPlan.ToolDef::name)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 7) 应用 tool_choice 逻辑
        if ("none".equals(normalizedToolChoice)) {
            // 显式禁止工具
            allowed.clear();
        } else if (forcedFunction != null && StringUtils.hasText(forcedFunction)) {
            // 强制单函�?
            allowed.clear();
            allowed.add(forcedFunction);
        }

        // 8) 应用运行时开关（禁用的从 allowed 移除�?
        Map<String, Boolean> toggles = effectiveProps.toolToggles();
        if (toggles != null && !toggles.isEmpty()) {
            allowed.removeIf(name -> Boolean.FALSE.equals(toggles.get(name)));
        }

        // 9) 最终暴露给模型的工具定义（enabled + allowed�?
        List<ToolCallPlan.ToolDef> finalDefs = mergedDefs.values().stream()
                .filter(def -> allowed.contains(def.name()))
                .toList();

        // 10) tool_context
        Map<String, Object> toolContext = new LinkedHashMap<>();
        Object scopeUser = payload.get("userId");
        Object scopeConversation = payload.get("conversationId");
        if (scopeUser != null) {
            toolContext.put("userId", scopeUser);
        }
        if (scopeConversation != null) {
            toolContext.put("conversationId", scopeConversation);
        }

        return new ToolCallPlan(
                model,
                temperature,
                rawToolChoice,
                normalizedToolChoice,
                forcedFunction,
                Collections.unmodifiableSet(allowed),
                Collections.unmodifiableMap(toolContext),
                List.copyOf(finalDefs)
        );
    }

    // =============== helpers ===============

    private void collectToolDefsFromPayload(Object toolsObj,
                                            Map<String, ToolCallPlan.ToolDef> merged) {
        if (!(toolsObj instanceof List<?> list)) return;
        for (Object item : list) {
            Map<String, Object> tool = castToMap(item);
            if (tool == null) continue;
            Map<String, Object> function = castToMap(tool.get("function"));
            if (function == null) continue;

            String name = coerceString(function.get("name"));
            if (!StringUtils.hasText(name)) continue;

            Object descriptionObj = function.containsKey("description")
                    ? function.get("description")
                    : tool.get("description");
            String description = descriptionObj != null ? descriptionObj.toString() : "";

            JsonNode schema = mapper.valueToTree(function.get("parameters"));
            String execTarget = resolveExecTarget(
                    coerceString(function.get("x-execTarget")),
                    coerceString(tool.get("x-execTarget"))
            );

            merged.put(name, new ToolCallPlan.ToolDef(name, description, schema, execTarget));
        }
    }

    private String resolveExecTarget(String functionLevel, String toolLevel) {
        String functionTarget = normalizeExecTarget(functionLevel);
        if (functionTarget != null) return functionTarget;
        String toolTarget = normalizeExecTarget(toolLevel);
        if (toolTarget != null) return toolTarget;
        return "server";
    }

    private String normalizeExecTarget(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeToolChoice(Object toolChoice) {
        if (toolChoice instanceof String str && StringUtils.hasText(str)) {
            return str.trim().toLowerCase(Locale.ROOT);
        }
        return "auto";
    }

    private String forcedFunctionName(Object toolChoice) {
        if (toolChoice instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String str && "function".equalsIgnoreCase(str.trim())) {
                Map<String, Object> fn = castToMap(map.get("function"));
                if (fn != null) {
                    String name = coerceString(fn.get("name"));
                    return StringUtils.hasText(name) ? name : null;
                }
            }
        }
        return null;
    }

    private JsonNode safeParseSchema(String schemaStr) {
        if (!StringUtils.hasText(schemaStr)) {
            return mapper.createObjectNode().put("type", "object");
        }
        try {
            return mapper.readTree(schemaStr);
        } catch (Exception e) {
            return mapper.createObjectNode().put("type", "object");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k == null ? null : k.toString(), v));
            return result;
        }
        if (value instanceof JsonNode node) {
            return mapper.convertValue(node, MAP_TYPE);
        }
        return null;
    }

    @Nullable
    private String coerceString(Object v) {
        if (v == null) return null;
        if (v instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) return null;
            return node.asText();
        }
        String s = v.toString();
        return (s == null || s.isBlank()) ? null : s;
    }
}

