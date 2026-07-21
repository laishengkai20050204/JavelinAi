# Javelin Lite

`javelin-lite` 是从现有 JavelinAI 中抽取的精简运行时，目标是作为 `digital-person` 中 `Person.chat(...)` 的内部大模型与函数调用引擎。

## 当前能力

已保留并实现：

- 统一控制层结构化入口
- `ChatRequest` 与 `StepEvent`
- 最多 8 轮的模型/工具编排循环
- OpenAI/OpenRouter 兼容的真实 `/chat/completions` 模型调用
- OpenAI 风格 `tools`、`tool_choice` 和 `tool_calls` 解析
- 同轮多工具调用及工具结果规范回灌
- 工具接口、工具注册表和执行管线
- `get_server_time` 示例工具
- 直接 Java 方法调用与可选 NDJSON HTTP 包装
- 无模型配置时可用于测试的本地 fallback

这里没有直接复制原项目超过千行、并且依赖 SSE、数据库、多模型路由和客户端工具状态的 `SpringAiChatGateway`。精简版保留了原 JavelinAI 的 `DecisionService -> ChatGateway -> provider` 分层，同时用轻量 OpenAI-compatible Gateway 完成真实模型调用。

尚未迁入：

- 会话记忆与数据库
- `web_search`、`web_fetch`、记忆检索工具
- 客户端工具恢复机制
- SSE Hub、MinIO、文件上传、图片分析、Python Docker
- 管理后台、审计和回放

## 启用真实模型

默认关闭真实模型调用，以便在没有 API Key 时也能启动和运行测试。

配置环境变量：

```bash
export JAVELIN_LITE_MODEL_ENABLED=true
export JAVELIN_LITE_BASE_URL=https://openrouter.ai/api/v1
export JAVELIN_LITE_API_KEY=你的_API_Key
export JAVELIN_LITE_MODEL=你要使用的模型名称
```

可选配置：

```bash
export JAVELIN_LITE_TEMPERATURE=0.8
export JAVELIN_LITE_TIMEOUT=120s
```

`JAVELIN_LITE_BASE_URL` 也可以指向任何兼容 OpenAI Chat Completions 协议的服务。对于不需要认证的本地服务，可以不设置 API Key。

## 运行

在仓库根目录执行：

```bash
mvn -f apps/javelin-lite/pom.xml spring-boot:run
```

测试：

```bash
mvn -f apps/javelin-lite/pom.xml test
```

## 直接 Java 调用

同一个 Spring 容器内，`Person` 应调用结构化入口，而不是通过 HTTP：

```java
Flux<StepEvent> events = orchestratedChatController.chat(request);
```

HTTP/NDJSON 方法只作为兼容和调试入口：

```text
POST /ai/v3/chat/step/ndjson
```

## 模型调用流程

```text
ChatRequest
  -> ModelDecisionService
  -> ChatGateway
  -> OpenAI-compatible provider
  -> assistant reply or tool_calls
  -> AiToolRegistry
  -> tool results
  -> ModelDecisionService again
  -> final assistant reply
```

工具结果回灌时会同时重建模型上一轮产生的 `assistant.tool_calls` 消息，再追加对应的 `role=tool` 消息，符合 OpenAI 兼容接口的函数调用消息序列。

## 下一步

1. 迁入 `web_search` 和 `web_fetch`。
2. 加入会话记忆和 `find_relevant_memory`。
3. 由 `digital-person` 的 `Person.chat(...)` 直接调用 `OrchestratedChatController.chat(...)`。
