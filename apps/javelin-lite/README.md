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
- `get_server_time`
- `web_search`：通过 Serper 搜索网页、新闻和图片
- `web_fetch`：抓取公开网页并抽取正文，包含 SSRF 防护
- 直接 Java 方法调用与可选 NDJSON HTTP 包装
- 无模型配置时可用于测试的本地 fallback

这里没有直接复制原项目超过千行、并且依赖 SSE、数据库、多模型路由和客户端工具状态的 `SpringAiChatGateway`。精简版保留了原 JavelinAI 的 `DecisionService -> ChatGateway -> provider` 分层，同时用轻量 OpenAI-compatible Gateway 完成真实模型调用。

尚未迁入：

- 会话记忆与数据库
- `find_relevant_memory`
- 客户端工具恢复机制
- SSE Hub、MinIO、文件上传、图片分析、Python Docker
- 管理后台、审计和回放

## 启用真实模型

默认关闭真实模型调用，以便在没有 API Key 时也能启动和运行测试。

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

## 启用网页搜索

`web_search` 默认不注册，配置 Serper Key 后启用：

```bash
export SERPER_API_KEY=你的_Serper_Key
export JAVELIN_LITE_WEB_SEARCH_ENABLED=true
```

可选配置：

```bash
export JAVELIN_LITE_SEARCH_TOP_K=5
export JAVELIN_LITE_SEARCH_LANG=zh-cn
export JAVELIN_LITE_SEARCH_COUNTRY=cn
export JAVELIN_LITE_SEARCH_TIMEOUT=20s
```

启用后，工具 Schema 会自动加入模型请求。未启用时，模型不会看到 `web_search`。

## 网页读取

`web_fetch` 默认启用，无需 API Key。它只允许公开的 HTTP/HTTPS 地址，默认阻止：

- 回环地址
- 私网地址
- 链路本地地址
- 多播地址
- IPv4 共享地址空间
- IPv6 ULA 地址

常用配置：

```bash
export JAVELIN_LITE_WEB_FETCH_ENABLED=true
export JAVELIN_LITE_WEB_FETCH_MAX_CHARS=20000
export JAVELIN_LITE_WEB_FETCH_MAX_BYTES=2097152
export JAVELIN_LITE_WEB_FETCH_TIMEOUT=20s
```

不建议关闭 SSRF 防护。仅在受控测试环境中可设置：

```bash
export JAVELIN_LITE_WEB_FETCH_SSRF_GUARD=false
```

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

1. 加入会话消息存储和 `find_relevant_memory`。
2. 增加人物长期记忆与关系状态。
3. 由 `digital-person` 的 `Person.chat(...)` 直接调用 `OrchestratedChatController.chat(...)`。
