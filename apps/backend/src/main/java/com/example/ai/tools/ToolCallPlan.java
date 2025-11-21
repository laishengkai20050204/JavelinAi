package com.example.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域层的“工具调用规划”结果，不依赖 Spring AI。
 *
 * - 决定用哪个模型 / 温度；
 * - 模型收到的原始 tool_choice；
 * - 最终允许调用的函数名称集合；
 * - 最终暴露给模型的工具定义（含 schema / execTarget）；
 * - 注入到 tool_context 的上下文信息。
 */
public record ToolCallPlan(
        String model,
        Double temperature,
        Object rawToolChoice,
        String normalizedToolChoice,
        String forcedFunctionName,
        Set<String> allowedFunctions,
        Map<String, Object> toolContext,
        List<ToolDef> toolDefs
) {

    public record ToolDef(
            String name,
            String description,
            JsonNode schema,
            String execTarget // "server" / "client"
    ) {}
}
