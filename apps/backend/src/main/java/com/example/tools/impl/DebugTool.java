package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class DebugTool implements AiTool {

    private final ObjectMapper mapper; // 你项目里已有 ObjectMapper Bean

    @Override
    public String name() {
        return "debug_tool";
    }

    @Override
    public String description() {
        return "Print 'debug tool' to log and return a short text payload.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // 无参数工具
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        log.info("debug tool from tool");
        String summary = "debug from tool";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("value", summary);
        payload.put("message", summary);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload", payload);
        data.put("text", summary);
        return ToolResult.success(null, name(), false, data);
    }
}

