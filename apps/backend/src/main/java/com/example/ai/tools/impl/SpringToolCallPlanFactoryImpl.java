package com.example.ai.tools.impl;

import com.example.ai.tools.SpringAiToolAdapter;
import com.example.ai.tools.ToolCallPlan;
import com.example.ai.tools.ToolCallPlanFactory;
import com.example.config.AiMultiModelProperties;
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
 * Build a tool-call plan from payload + runtime config + SpringAiToolAdapter (client-agnostic).
 * Handles tool_choice/tool toggles/tool_context; leaves Spring AI specifics to the gateway.
 */
@Component
@RequiredArgsConstructor
public class SpringToolCallPlanFactoryImpl implements ToolCallPlanFactory {

    private final EffectiveProps effectiveProps;
    private final SpringAiToolAdapter toolAdapter;
    private final ObjectMapper mapper;
    private final AiMultiModelProperties multiProps;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public ToolCallPlan buildPlan(Map<String, Object> payload, AiProperties.Mode mode) {

        // 1) Model: profile wins (ignore payload.model); fallback to runtime/static default
        String profileName = profileFromPayload(payload);
        String model = StringUtils.hasText(profileName)
                ? multiProps.requireProfile(profileName).getModelId()
                : effectiveProps.model();

        // 2) Temperature (optional)
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

        // 4) Parse tools + clientTools definitions
        Map<String, ToolCallPlan.ToolDef> mergedDefs = new LinkedHashMap<>();
        collectToolDefsFromPayload(payload.get("tools"), mergedDefs);
        collectToolDefsFromPayload(payload.get("clientTools"), mergedDefs);

        // 5) Fill missing server-side tool definitions
        for (ToolCallback cb : toolAdapter.toolCallbacks()) {
            ToolDefinition definition = cb.getToolDefinition();
            if (definition == null) {
                continue;
            }
            String name = definition.name();
            if (!StringUtils.hasText(name)) continue;
            if (mergedDefs.containsKey(name)) continue; // payload overrides schema/desc
            JsonNode schema = safeParseSchema(definition.inputSchema());
            String desc = definition.description();
            mergedDefs.put(name, new ToolCallPlan.ToolDef(
                    name,
                    desc != null ? desc : "",
                    schema,
                    "server"
            ));
        }

        // 6) Allowed set (after payload + server merge)
        LinkedHashSet<String> allowed = mergedDefs.values().stream()
                .map(ToolCallPlan.ToolDef::name)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 7) Apply tool_choice
        if ("none".equals(normalizedToolChoice)) {
            allowed.clear();
        } else if (forcedFunction != null && StringUtils.hasText(forcedFunction)) {
            allowed.clear();
            allowed.add(forcedFunction);
        }

        // 8) Apply runtime toggles
        Map<String, Boolean> toggles = effectiveProps.toolToggles();
        if (toggles != null && !toggles.isEmpty()) {
            allowed.removeIf(name -> Boolean.FALSE.equals(toggles.get(name)));
        }

        // 9) Final tool definitions (enabled + allowed)
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

    private String profileFromPayload(Map<String, Object> payload) {
        String p = coerceString(payload.get("_profile"));
        if (StringUtils.hasText(p)) return p;
        p = coerceString(payload.get("profile"));
        if (StringUtils.hasText(p)) return p;
        p = coerceString(payload.get("modelProfile"));
        if (StringUtils.hasText(p)) return p;
        // 默认落到 primary-model（若未配置则返回 null）
        String primary = multiProps.getPrimaryModel();
        return StringUtils.hasText(primary) ? primary : null;
    }

    // =============== helpers ===============

    /**
     * Collect tool definitions from request tools/clientTools into merged map.
     */
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
