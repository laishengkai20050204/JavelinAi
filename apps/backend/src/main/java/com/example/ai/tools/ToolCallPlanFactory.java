package com.example.ai.tools;

import com.example.config.AiProperties;

import java.util.Map;

/**
 * 根据请求 payload + 运行时配置，生成一个与具体客户端无关的 ToolCallPlan?
 *
 * ChatGateway 再把 ToolCallPlan 转成 Spring AI ?ToolCallingChatOptions
 * 或其它客户端需要的参数?
 */
public interface ToolCallPlanFactory {

    ToolCallPlan buildPlan(Map<String, Object> payload, AiProperties.Mode mode);

}

