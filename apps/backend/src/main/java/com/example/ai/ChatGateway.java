package com.example.ai;

import com.example.config.AiProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 抽象的对话网关接口。
 *
 * 设计原则：
 * - 调用方只面向这个接口，不依赖 Spring AI 的具体版本和实现细节。
 * - payload 使用 Map<String, Object>，保持和你现在 Controller 传过来的格式兼容。
 * - mode 用 AiProperties.Mode，保留 OPENAI / OLLAMA / GEMINI 等兼容模式。
 *
 * 后续要升级 Spring AI 1.1.0 / 1.2.0 / 换成直连 HTTP Provider，
 * 只需要新增一个实现类（比如 NewSpringAiChatGateway），让它实现这个接口即可。
 */
public interface ChatGateway {

    /**
     * 一次性调用（非流式），返回完整 JSON 字符串。
     * 约定返回的是你的「Javelin 统一 JSON」，而不是 Spring AI 自己的结构。
     */
    Mono<String> call(Map<String, Object> payload, AiProperties.Mode mode);

    /**
     * 流式调用（SSE / NDJSON），每个元素也是你的「Javelin 流式 chunk JSON」字符串。
     */
    Flux<String> stream(Map<String, Object> payload, AiProperties.Mode mode);
}
