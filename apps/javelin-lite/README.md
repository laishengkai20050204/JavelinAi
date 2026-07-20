# Javelin Lite

`javelin-lite` 是从现有 JavelinAI 中逐步抽取的精简版运行时，目标是作为 `digital-person` 中 `Person.chat(...)` 的内部大模型与函数调用引擎。

## 当前阶段

本目录先提供一个独立、可编译的最小项目骨架，原 JavelinAI 项目保持不变。

已保留：

- 统一控制层入口
- 结构化 `ChatRequest` 与 `StepEvent`
- 多轮编排循环和最大轮数保护
- 模型决策接口 `DecisionService`
- 工具接口、工具注册表和执行管线
- `get_server_time` 示例工具
- 直接 Java 方法调用与可选 NDJSON HTTP 包装

暂未迁入：

- 原项目的 Spring AI 模型适配器
- 会话记忆与数据库
- `web_search`、`web_fetch`、记忆检索工具
- 客户端工具恢复机制
- SSE Hub、MinIO、文件上传、图片分析、Python Docker
- 管理后台、审计和回放

## 直接调用

同一个 Spring 容器内，`Person` 应调用结构化入口，而不是 HTTP：

```java
Flux<StepEvent> events = orchestratedChatController.chat(request);
```

HTTP/NDJSON 方法只作为兼容和调试入口：

```text
POST /ai/v3/chat/step/ndjson
```

## 下一步

1. 将原 JavelinAI 的 `DecisionServiceSpringAi` 适配到本项目的 `DecisionService`。
2. 迁入服务端工具：时间、搜索、网页读取、相关记忆检索。
3. 增加 `personId`、人物 system prompt 和运行时上下文。
4. 由 `digital-person` 的 `Person.chat(...)` 直接调用 `OrchestratedChatController.chat(...)`。

当前的 `FallbackDecisionService` 只是保证骨架可启动的临时实现，不代表最终模型行为。
