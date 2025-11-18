package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.tools.AiTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 全局任务规划工具：plan_task
 *
 * 用途：
 *   - 当任务比较复杂（需要多步推理 / 多个工具 / 先看图再写代码）时，
 *     让主 LLM 先调用本工具，生成结构化 JSON 计划，然后再按 steps 执行。
 *
 * 约定：
 *   - 模型在调用时，应该在 arguments 里填好：
 *       goal, context, steps[], final_answer_format 等字段
 *   - 本工具不会“再规划一次”，只负责把这些字段原样放进 data.plan 里返回。
 */
@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class PlanTaskTool implements AiTool {

    @Override
    public String name() {
        return "plan_task";
    }

    @Override
    public String description() {
        return """
                为当前用户需求生成一个结构化的解决计划（全局 Planner）。
                适用于需要多步推理或调用多个工具（如 web_search、python_exec、analyze_image 等）的场景。
                模型在调用本工具时，应：
                1) 在 goal 中简要概括本次任务目标；
                2) 在 context 中写明与任务高度相关的背景信息（可选）；
                3) 在 steps 数组中按顺序列出每一步要做什么（思考 / 工具调用 / 向用户追问 / 最终回答）；
                4) 在 final_answer_format 中说明最终回答的格式（例如语言、结构、是否包含代码/公式等）。
                如果后续会调用 analyze_image，建议在对应步骤中补充 vision_prompt 字段，
                以指导多模态模型应该重点关注图片中的哪些内容。
                本工具只会把这些字段原样返回到 data.plan 中，不做额外修改。
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();

        // 任务总体目标
        props.put("goal", Map.of(
                "type", "string",
                "description", "对当前用户需求的简要概括。"
        ));

        // 已知关键上下文（可选）
        props.put("context", Map.of(
                "type", "string",
                "description", "与任务高度相关的背景信息摘要，可引用用户历史对话。"
        ));

        // 任务重要性（可选）
        props.put("priority", Map.of(
                "type", "string",
                "enum", List.of("low", "normal", "high"),
                "description", "任务重要程度，默认为 normal。"
        ));

        // 希望的最终回答形式
        props.put("final_answer_format", Map.of(
                "type", "string",
                "description", "期望的最终回答形式说明，例如：'用中文，先给结论，再给步骤，最后给代码示例'。"
        ));

        // 步骤 schema：steps[]
        Map<String, Object> stepSchema = new LinkedHashMap<>();
        stepSchema.put("type", "object");

        Map<String, Object> stepProps = new LinkedHashMap<>();
        stepProps.put("id", Map.of(
                "type", "string",
                "description", "步骤 ID，用于依赖引用，比如 s1、s2。"
        ));
        stepProps.put("kind", Map.of(
                "type", "string",
                "enum", List.of("think", "tool", "ask_user", "answer"),
                "description", "步骤类型：think(内部思考) / tool(调用工具) / ask_user(向用户追问) / answer(形成最终回答)。"
        ));
        stepProps.put("description", Map.of(
                "type", "string",
                "description", "这一步要做什么的自然语言说明。"
        ));
        stepProps.put("depends_on", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "本步骤依赖的前置步骤 ID 列表，可为空。"
        ));
        stepProps.put("tool_name", Map.of(
                "type", "string",
                "description", "如果 kind=tool，这里给出建议调用的工具名，如 web_search、python_exec、analyze_image。"
        ));
        stepProps.put("tool_args_hint", Map.of(
                "type", "string",
                "description", "对工具参数的高层次说明，例如需要传入的关键字段、约束等。"
        ));
        stepProps.put("expected_output", Map.of(
                "type", "string",
                "description", "这一步完成后希望得到什么结果，用自然语言说明。"
        ));
        // 可选：给图片分析用的提示词
        stepProps.put("vision_prompt", Map.of(
                "type", "string",
                "description", "如果此步骤会调用 analyze_image，可在这里写给视觉模型的提示词（中文或英文均可）。"
        ));

        stepSchema.put("properties", stepProps);
        // 要求最基本的字段
        stepSchema.put("required", List.of("id", "kind", "description"));

        props.put("steps", Map.of(
                "type", "array",
                "description", "完成任务所需的有序步骤列表。",
                "items", stepSchema
        ));

        schema.put("properties", props);
        // 强制要求有 goal 和 steps
        schema.put("required", List.of("goal", "steps"));
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        Map<String, Object> plan = new LinkedHashMap<>();
        if (args != null) {
            plan.putAll(args);
        }

        String goal = Objects.toString(plan.getOrDefault("goal", "（未提供目标）"));
        Object stepsObj = plan.get("steps");
        int stepCount = (stepsObj instanceof List<?> l) ? l.size() : 0;

        String summary = "已生成结构化计划：goal=" + goal + "，steps=" + stepCount + " 个步骤。";
        log.debug("[plan_task] {}", summary);

        // ===== 可读文本：把所有步骤都展开给 LLM 看 =====
        StringBuilder sb = new StringBuilder();

        sb.append("【plan_task 全局任务规划】\n");
        sb.append("总体目标：").append(goal).append("\n");

        Object priority = plan.get("priority");
        if (priority != null) {
            sb.append("任务优先级：").append(priority).append("\n");
        }

        Object finalFormat = plan.get("final_answer_format");
        if (finalFormat != null) {
            sb.append("最终回答形式（final_answer_format）：")
                    .append(finalFormat)
                    .append("\n");
        }

        Object context = plan.get("context");
        if (context != null && !Objects.toString(context, "").isBlank()) {
            sb.append("背景信息（context）：").append(context).append("\n");
        }

        sb.append("\n步骤列表（共 ").append(stepCount).append(" 步）：\n");

        if (stepsObj instanceof List<?>) {
            List<?> steps = (List<?>) stepsObj;
            for (Object o : steps) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> step = (Map<String, Object>) o;

                String id = Objects.toString(step.getOrDefault("id", "（无ID）"));
                String kind = Objects.toString(step.getOrDefault("kind", "unknown"));
                String desc = Objects.toString(step.getOrDefault("description", "（无描述）"));

                sb.append("- [").append(id).append("] kind=")
                        .append(kind).append(" ：")
                        .append(desc).append("\n");

                Object dependsOn = step.get("depends_on");
                if (dependsOn instanceof List<?> deps && !deps.isEmpty()) {
                    sb.append("  · depends_on: ").append(deps).append("\n");
                }

                Object toolName = step.get("tool_name");
                if (toolName != null) {
                    sb.append("  · tool_name: ").append(toolName).append("\n");
                }

                Object toolArgsHint = step.get("tool_args_hint");
                if (toolArgsHint != null && !Objects.toString(toolArgsHint, "").isBlank()) {
                    sb.append("  · tool_args_hint: ")
                            .append(toolArgsHint)
                            .append("\n");
                }

                Object expectedOutput = step.get("expected_output");
                if (expectedOutput != null && !Objects.toString(expectedOutput, "").isBlank()) {
                    sb.append("  · expected_output: ")
                            .append(expectedOutput)
                            .append("\n");
                }

                Object visionPrompt = step.get("vision_prompt");
                if (visionPrompt != null && !Objects.toString(visionPrompt, "").isBlank()) {
                    sb.append("  · vision_prompt: ")
                            .append(visionPrompt)
                            .append("\n");
                }
            }
        } else {
            sb.append("（steps 字段缺失或不是数组）\n");
        }

        // 也可以在最后附一行简单摘要，方便人类阅读
        sb.append("\n小结：").append(summary);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", plan);       // 原始结构化 JSON 仍然完整保留
        data.put("summary", summary); // 简短摘要
        data.put("text", sb.toString()); // 可读内容：包含所有步骤，供 LLM/Mem 使用
        data.put("_source", "plan_task");

        return ToolResult.success(null, name(), false, data);
    }


}
