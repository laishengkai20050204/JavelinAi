# JavelinAI SDK

JavelinAI 是一套 **Java 21 + Spring Boot WebFlux** 的后台配 **React 控制台 / Demo UI** 的例子，用来展示「先 NDJSON、再 SSE」的两段式聊天编排。它适合需要可回放、可审计、可控工具链的 AI 应用。

---

## 1. 你可以用它做什么

- **统一编排**：`SinglePathChatService` 把“决策 → 工具 → 续写/等待 → 终结”收敛在一个循环内。
- **双通道输出**：`POST /ai/v3/chat/step/ndjson` 给结构化事件，`GET /ai/v2/chat/sse` 即时推 token、clientCalls。
- **客户端工具对账**：`StepContextStore` 记录 `stepId/userId/conversationId` 与 `clientCalls`，resume 时强校验。
- **工具治理**：去重账本 + 可热禁用 + `toolToggles`，并将 user/conversation 作为审计维度。
- **热配置**：`/admin/config` + `EffectiveProps` 让运行时可切换模型、超时、内存窗口等。

---

## 2. 环境要求

- Java 21、Maven 3.9+
- Node.js 20+（pnpm/npm/yarn 任意）
- （可选）MySQL：`jdbc:mysql://localhost:3306/java_ai`

---

## 3. 快速开始

```bash
# 1. 启动后端
cd apps/backend
mvn spring-boot:run
# 默认 http://localhost:8080

# 2. 启动运行时控制台
cd apps/console
pnpm install && pnpm dev
# 默认 http://localhost:5173

# 3. (可选) 前端 Demo
cd apps/frontend
pnpm install && pnpm dev
```

配置方式（按照优先级）：
1. `apps/backend/src/main/resources/application.yaml`
2. 环境变量（如 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`SERPER_API_KEY`）
3. 运行时 `/admin/config`（合并更新）或 `/admin/config/replace`（全量替换）

---

## 4. 三步式编排流

| 步骤 | 调用 | 说明 |
| --- | --- | --- |
| ① Start | `POST /ai/v3/chat/step/ndjson` | 发送 `userId/conversationId` 与用户问题，可附带 `clientTools` schema。返回 NDJSON，第一行含 `stepId`。 |
| ② Observe | `GET /ai/v2/chat/sse?stepId=...` | 立刻订阅 SSE，收到 `"message"`（token/决策片段）、`"clientCalls"` 等事件。 |
| ③ Resume | `POST /ai/v3/chat/step/ndjson` | 带上 `resumeStepId` 和 `clientResults`。`tool_call_id` 必须来自步骤 ① 下发的 `clientCalls`。直至 `{"event":"finished"}`。 |

最小开始请求：

```json
{
  "userId": "u1",
  "conversationId": "c1",
  "q": "帮我总结最新的 OpenAI 定价",
  "toolChoice": "auto",
  "responseMode": "step-json-ndjson",
  "clientTools": [
    {
      "type": "function",
      "function": {
        "name": "open_url",
        "description": "Open a URL in the browser",
        "parameters": {
          "type": "object",
          "properties": { "url": { "type": "string", "format": "uri" } },
          "required": ["url"]
        }
      }
    }
  ]
}
```

Resume 请求需要回传已执行的客户端工具结果：

```json
{
  "userId": "u1",
  "conversationId": "c1",
  "resumeStepId": "step-123",
  "clientResults": [
    {
      "tool_call_id": "call_abc",
      "name": "open_url",
      "status": "ok",
      "args": { "url": "https://example.com" },
      "payload": { "type": "text", "value": "页面已打开，摘要如下..." }
    }
  ]
}
```

---

## 5. 仓库结构

```
apps/
  backend/         # Spring Boot WebFlux + MyBatis + Spring AI
    controller/    # NDJSON+SSE/API/管理控制器
    service/       # 编排核心、记忆、工具管线
    tools/         # 内置工具（web_search/web_fetch/python_exec/...）
    infra/         # SSE Hub、FinalAnswerStreamManager 等
    runtime/       # 运行时配置服务
    config/        # 属性类与 WebClient/Spring AI 配置
    resources/     # application.yaml, MyBatis Mapper
  console/         # Vite + React 控制台（运行时配置、审计等）
  frontend/        # 最小聊天 UI + 工具可视化示例
```

---

## 6. 配置与治理要点

- **模型 / API Key**：`spring.ai.openai.*`、`spring.ai.ollama.*` 或 `/admin/config` 动态覆盖。
- **工具治理**：`ai.tools.dedup` 控制去重 TTL 与参数白名单；`toolToggles` 可热禁用任意工具。
- **内存模式**：`ai.memory.storage=database|in-memory`，数据库模式支持草稿→Final 提升与审计链。
- **SSE 调优**：`sse.heartbeat-every`、`sse.step-ttl`、`sse.janitor-every` 控制连接保活与垃圾清理。
- **Python/Shell 工具**：`ai.tools.python.*` 可限制 timeout、输出大小、pip、Docker 沙箱等。

---

## 7. 管理控制台 (`apps/console`)

- 查看 runtime / effective 配置（敏感信息做掩码）。
- 编辑 `model`、`compatibility`、`toolsMaxLoops`、`toolToggles` 等并即时下发。
- 一键触发 `Reload`，方便多个实例同时刷新。

---

## 8. 常见问题

- **SSE 没数据**：确认先从 NDJSON 获取 `stepId`，并在 `sse.step-ttl` 过期前订阅。
- **clientResults 被拒**：`resumeStepId` 与 `userId/conversationId` 不匹配，或 `tool_call_id` 不在本次 `clientCalls` 中。
- **未看到模型流式 token**：检查 `EffectiveProps.streamDecision()`、模型是否支持流。
- **连接 OpenAI 官方失败**：将 `spring.ai.openai.base-url` 设置为 `https://api.openai.com` 并确保 `OPENAI_API_KEY` 有效。

---

## 9. License

请按需选择并补充（MIT / Apache-2.0 / …）。

---

Enjoy building auditable, tool-aware chat flows 🚀
