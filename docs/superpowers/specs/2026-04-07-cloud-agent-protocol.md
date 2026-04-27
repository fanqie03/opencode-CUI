# 云端 Agent 对接协议规范

> 业务助手通过云端 Agent 服务进行对话，本文档定义 Skill Server、Gateway、云端三方之间的通信协议。

---

## 1. 概述

### 1.1 背景

当前所有助手（业务 + 个人）都通过 AK/SK 连接本地 PC Agent 进行对话。本地 Agent 离线则无法对话。新需求：业务助手直接连接云端 Agent 服务，个人助手保持现有链路不变。

### 1.2 术语

| 术语 | 说明 |
|------|------|
| 业务助手 | 由企业提供的内置助手，对话链路走云端 Agent 服务（`assistantScope = "business"`） |
| 个人助手 | 由用户自定义的助手，对话链路走本地 PC Agent（`assistantScope = "personal"`） |
| appId | 业务助手标识，用于区分不同的云端 Agent 服务，由上游助手管理平台分配 |

### 1.3 消息流总览

```
业务助手对话流：
  MiniApp / IM  →  Skill Server  →  Gateway  →  云端服务 (HTTP POST + SSE)
  MiniApp / IM  ←  Skill Server  ←  Gateway  ←  云端服务 (SSE 流式响应)

个人助手对话流（不变）：
  MiniApp / IM  →  Skill Server  →  Gateway  →  PC Agent (WebSocket)
  MiniApp / IM  ←  Skill Server  ←  Gateway  ←  PC Agent (WebSocket)
```

### 1.4 各方职责

| 角色 | 职责 | 不做什么 |
|------|------|----------|
| **Skill Server** | 业务逻辑中枢：会话管理、消息持久化、根据 appId 构建对应云端请求体、事件翻译、前端推送、IM 出站 | 不管理云端连接、不管理云端认证凭证 |
| **Gateway** | 连接路由器：根据 `assistantScope` 路由到 PC Agent 或云端；根据 `ak` 调用上游接口获取 endpoint/protocol/authType 并缓存；对云端发起 HTTP POST + 读取 SSE 流；自行获取认证凭证；透传请求体和响应，不解析业务内容 | 不理解 payload 业务含义，不做协议转换 |
| **云端服务** | 按本协议规范适配响应格式，通过 SSE 流式返回结果 | 不管理会话生命周期（由 SS 管理） |

### 1.5 协议分段

| 段 | 方向 | 传输方式 | 说明 |
|----|------|----------|------|
| P1 | SS → GW | WebSocket（复用现有 `/ws/skill` 连接） | invoke 消息，携带云端请求体 |
| P2 | GW → 云端 | HTTP POST（建立 SSE 连接） | Gateway 透传 SS 构建的请求体，自行处理认证 |
| P3 | 云端 → GW | SSE 流式响应 | 云端按本协议格式返回事件 |
| P4 | GW → SS | WebSocket（复用现有 `/ws/skill` 连接） | Gateway 透传云端事件，注入路由上下文 |

### 1.6 多业务助手对接模型

不同业务助手通过 `appId` 区分，可能有不同的云端地址、认证方式和请求格式：

```
                    ┌─ appId: "uniassistant"  → https://uni.example.com/chat    (authType: soa)
SS → GW → 路由 ─────┼─ appId: "code-review"   → https://cr.example.com/review  (authType: apig)
                    └─ appId: "future-app"    → https://xxx.example.com/api     (authType: ...)
```

**对接新业务助手的步骤**：

1. 上游助手管理平台注册新 appId，配置 endpoint、authType
2. SS 侧：默认策略够用则通过管理接口添加 appId 映射（零开发）；特殊格式则新增策略类
3. 云端服务按本协议 P3（响应协议）适配
4. GW 增加该 authType 的认证实现（如已有则无需改动）
5. **GW 不需要为新 appId 做任何改动**（GW 通过 ak 调上游接口获取路由信息）

---

## 2. 上游 API：助手信息查询

### 2.1 接口说明

SS 通过上游助手管理平台 API 查询助手的 scope 和云端配置信息。

**请求**：

```
GET https://api.openplatform.hisuat.huawei.com/appstore/wecodeapi/open/ak/info
Authorization: Bearer {token}
Content-Type: application/json

{"ak": "{ak}"}
```

### 2.2 响应格式

```json
{
    "code": "200",
    "messageZh": "成功！",
    "messageEn": "success!",
    "data": {
        "identityType": "3",
        "hisAppId": "app_36209",
        "endpoint": "https://cloud-agent.example.com/api/v1/chat",
        "protocol": "2",
        "authType": "1"
    }
}
```

### 2.3 字段说明

| 上游字段 | 类型 | 说明 | 我们的映射 |
|---------|------|------|-----------|
| identityType | String | `"2"` = 个人助理，`"3"` = 业务助理 | `"2"` → `assistantScope = "personal"`，`"3"` → `assistantScope = "business"` |
| hisAppId | String | 业务助手标识 | `appId` |
| endpoint | String | 云端服务地址（business 时有值） | `cloudEndpoint` |
| protocol | String | `"1"`=rest, `"2"`=sse, `"3"`=websocket | `cloudProtocol`（映射为 rest/sse/websocket） |
| authType | String | `"1"`=soa | `authType`（映射为 soa） |

### 2.4 缓存策略

SS 侧 Redis 缓存，key 为 `ss:assistant:info:{ak}`，跟随现有 assistant account 缓存 TTL 策略。

---

## 3. P1：SS → GW（invoke 消息）

### 3.1 消息格式

复用现有 `GatewayMessage` 结构，新增云端路由字段。

```json
{
  "type": "invoke",
  "ak": "agent-ak-123",
  "source": "skill-server",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "action": "chat",
  "assistantScope": "business",
  "payload": {
    "toolSessionId": "cloud-1001214",
    "cloudRequest": {
      "type": "text",
      "content": "JDK8和JDK21有什么区别？",
      "assistantAccount": "assistant-bot-001",
      "sendUserAccount": "c30051824",
      "imGroupId": null,
      "clientLang": "zh",
      "clientType": "asst-pc",
      "topicId": "cloud-1001214",
      "messageId": "202411121103",
      "extParameters": {
        "businessExtParam": {
          "isHwEmployee": false,
          "actionParam": "",
          "knowledgeId": []
        },
        "platformExtParam": {}
      }
    }
  }
}
```

### 3.2 字段说明

#### 现有字段（不变）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | 固定 `"invoke"` |
| ak | String | ✅ | 助手应用密钥 |
| source | String | ✅ | 固定 `"skill-server"` |
| userId | String | ✅ | 用户 ID |
| welinkSessionId | String | ✅ | Skill 侧会话 ID |
| traceId | String | ✅ | 链路追踪 ID |
| action | String | ✅ | 动作类型 |
| payload | Object | ✅ | 业务载荷 |

#### 新增字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| assistantScope | String | ✅ | `"business"` 或 `"personal"`。缺失时视为 `"personal"` |

#### payload 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| payload.toolSessionId | String | ✅ | 会话 ID（业务助手由 SS 本地生成） |
| payload.cloudRequest | Object | 条件 | `assistantScope = "business"` 时必填，完整的云端请求体 |

#### payload.cloudRequest 字段

SS 按云端 API 规范构建，GW 不解析，直接作为 HTTP Body 发送。

不同 `appId` 的 `cloudRequest` 格式不同，以下是 `uniassistant` 的字段定义：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | 消息类型：`"text"` / `"IMAGE-V1"` |
| content | String | ✅ | 用户消息内容 |
| assistantAccount | String | ✅ | 助手账号 ID（SS 数据库中已有） |
| sendUserAccount | String | ✅ | 发送人账号 |
| imGroupId | String | | 群 ID，无则 null |
| clientLang | String | ✅ | 客户端语言：`"zh"` / `"en"` |
| clientType | String | | 客户端类型：`"asst-pc"` / `"asst-wecode"` / null |
| topicId | String | ✅ | 会话主题 ID，用于多轮会话聚合 |
| messageId | String | ✅ | 消息 ID，用于追踪每次对话 |
| extParameters | Object | ✅ | 扩展参数容器（始终为 object） |
| extParameters.businessExtParam | Object | ✅ | 业务方扩展参数（自由 JSON 对象）；由 IM/external/miniapp 入参信封透传，skill-server 不解析；缺省 / 类型异常 / 解析失败时由 skill-server 兜底为 `{}`；业务字段（如 `isHwEmployee` / `actionParam` / `knowledgeId`）由业务方自行定义放入此对象内 |
| extParameters.platformExtParam | Object | ✅ | 平台扩展参数（skill-server 自填）；首期占位 `{}`，将来用于塞 traceId / 版本号 / 租户标识等平台元数据 |

### 3.3 与个人助手 invoke 的区别

| 维度 | 个人助手（personal） | 业务助手（business） |
|------|---------------------|---------------------|
| assistantScope | `"personal"` 或缺失 | `"business"` |
| payload.cloudRequest | 无 | 必填（完整云端请求体） |
| payload.text | 用户消息文本 | 无（内容在 cloudRequest.content 中） |
| payload.assistantId | 由 SS 注入 | 无（云端不需要） |
| action | `chat` / `create_session` / `close_session` 等 | `chat`；P2 阶段增加 `question_reply` / `permission_reply` |
| toolSessionId | 由 PC Agent 返回 session_created 绑定 | 由 SS 本地生成 |

---

## 4. P2：GW → 云端（HTTP POST + SSE）

### 4.1 请求格式

Gateway 收到 `assistantScope = "business"` 的 invoke 后：

1. 根据 `ak`（invoke 中已有）**调用上游接口**获取 endpoint、protocol、authType（结果缓存在 GW 侧）
2. 根据 `authType` **自行获取认证凭证**
3. 将 `payload.cloudRequest` 作为请求体发送

```
POST {上游接口返回的 endpoint}
Content-Type: application/json
{认证头由 GW 根据 authType 自行填充}
X-Trace-Id: {traceId}
X-Request-Id: {GW 自动生成的 UUID}
X-App-Id: {上游接口返回的 hisAppId}

{payload.cloudRequest 原样透传}
```

### 4.2 认证方式

| authType | 说明 | GW 认证行为 |
|----------|------|------------|
| `soa` | SOA 认证 | GW 根据 appId 获取 SOA 凭证，按 SOA 规范填充请求头 |
| `apig` | APIG 网关认证 | GW 根据 appId 获取 APIG 凭证，按 APIG 规范填充请求头 |

> 新增认证方式只需：在上游 API 注册新 authType 值 + GW 实现对应认证策略。SS 和云端无需改动。

### 4.3 实际请求示例（appId: "uniassistant", authType: "soa"）

```
POST https://cloud-agent.example.com/api/v1/chat
Content-Type: application/json
X-SOA-Token: {GW 自行获取的 SOA 凭证}
X-Trace-Id: trace-uuid-xxx
X-Request-Id: req-uuid-yyy
X-App-Id: uniassistant

{
  "type": "text",
  "content": "JDK8和JDK21有什么区别？",
  "assistantAccount": "assistant-bot-001",
  "sendUserAccount": "c30051824",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": "asst-pc",
  "topicId": "cloud-1001214",
  "messageId": "202411121103",
  "extParameters": {
    "businessExtParam": {
      "isHwEmployee": false,
      "actionParam": "",
      "knowledgeId": []
    },
    "platformExtParam": {}
  }
}
```

### 4.4 响应要求

云端必须返回：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
```

响应体为 SSE 流，每行格式：`data: {JSON}`，JSON 格式见 P3 章节。

### 4.5 错误场景

| 场景 | HTTP 状态码 | GW 处理 |
|------|------------|---------|
| 正常 | 200 | 读取 SSE 流，逐条转发给 SS |
| 流量限制 | 429 | 构建 `tool_error` 消息回传 SS |
| 请求参数错误 | 400 | 构建 `tool_error` 消息回传 SS |
| 认证失败 | 401 | 构建 `tool_error` 消息回传 SS |
| 服务不可用 | 502 / 503 | 构建 `tool_error` 消息回传 SS |
| 连接超时 | - | 构建 `tool_error` 消息回传 SS |
| 读取超时（SSE 流中断） | - | 构建 `tool_error` 消息回传 SS |

GW 构建的 `tool_error` 消息格式：

```json
{
  "type": "tool_error",
  "ak": "agent-ak-123",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "toolSessionId": "ts-local-generated-789",
  "traceId": "trace-uuid-xxx",
  "error": "cloud_service_unavailable",
  "reason": "HTTP 503: Service Temporarily Unavailable"
}
```

GW 侧错误码：

| error | 说明 |
|-------|------|
| `cloud_connection_timeout` | 连接云端超时 |
| `cloud_read_timeout` | SSE 流读取超时 |
| `cloud_service_unavailable` | 云端返回 502/503 |
| `cloud_rate_limited` | 云端返回 429 |
| `cloud_auth_failed` | 认证失败（凭证获取失败或云端返回 401） |
| `cloud_bad_request` | 云端返回 400 |
| `cloud_unknown_error` | 其他未预期错误 |

---

## 5. P3：云端 → GW（SSE 响应协议）

### 5.1 总体格式

云端必须按以下 SSE 格式返回，每条事件为一行 `data: {JSON}`。

所有响应 JSON 共享以下外层结构（兼容 GatewayMessage）：

```json
{
  "type": "tool_event | tool_done | tool_error",
  "toolSessionId": "ts-local-generated-789",
  "event": {
    "type": "<event_type>",
    "properties": { ... }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"tool_event"` / `"tool_done"` / `"tool_error"` |
| toolSessionId | String | ✅ | 回传请求中的 topicId（由 GW 注入，云端也可回传） |
| event | Object | 条件 | `tool_event` 时必填 |
| event.type | String | ✅ | 事件类型，见 5.2 枚举 |
| event.properties | Object | ✅ | 事件数据 |
| usage | Object | 条件 | `tool_done` 时可选 |
| error | String | 条件 | `tool_error` 时必填 |
| reason | String | | `tool_error` 时可选，错误描述 |

**event.properties 公共必填字段**：

Part 级事件（text/thinking/tool/question/file/permission/planning/searching/search_result/reference/ask_more）的 properties 中**必须包含**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| messageId | String | ✅ | 消息 ID，同一轮对话（同一次 chat invoke）的所有事件**必须共享同一个 messageId**。不同轮次必须不同。由云端生成，全局唯一。 |
| partId | String | ✅ | Part ID，同类型事件共享同一个 partId（如所有 text.delta 和 text.done 共享一个 partId），不同类型不同 partId。由云端生成。 |
| role | String | | 固定 `"assistant"`，可选（SS 兜底补充） |

Message 级事件（step.start/step.done）的 properties 中：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| messageId | String | ✅ | 同上，与同轮次的 Part 级事件共享 |

Session 级事件（session.status/session.title/session.error）不需要 messageId/partId。

> **重要**：messageId 和 partId 由云端自行生成并传入，SS/GW 不会自动补充。缺失时 SS 会打 warn 日志，前端 StreamAssembler 可能无法正确组装消息。

### 5.2 三方协议映射

我们的协议事件类型与 OpenCode 原始事件、云端 API 原始类型的对应关系：

| 我们的 event.type | OpenCode 原始事件 | 云端原始类型（以 uniassistant 为例） | 说明 |
|------------------|------------------|----------------------------------|------|
| `text.delta` | `message.part.delta` (partType=text) | `data.type="text"` (isFinish=false) | 流式文本增量 |
| `text.done` | `message.part.updated` (type=text, 无delta) | `data.type="text"` (isFinish=true) 或最后一条 text | 文本片段完成，携带完整内容 |
| `thinking.delta` | `message.part.delta` (partType=reasoning) | `data.type="think"` (isFinish=false) | 深度思考增量 |
| `thinking.done` | `message.part.updated` (type=reasoning, 无delta) | 最后一条 think | 思考完成，携带完整内容 |
| `tool.update` | `message.part.updated` (type=tool) | — | 工具调用状态更新 |
| `step.start` | `message.part.updated` (type=step-start) | — | 步骤开始 |
| `step.done` | `message.part.updated` (type=step-finish) 或 `message.updated` (有finish) | — | 步骤完成，携带 usage |
| `question` | `question.asked` 或 `message.part.updated` (tool=question, completed) | — | 交互式提问 |
| `permission.ask` | `permission.asked` / `permission.updated` (未resolved) | — | 权限请求 |
| `permission.reply` | `permission.replied` / `permission.updated` (已resolved) | — | 权限应答 |
| `session.status` | `session.status` / `session.idle` | — | 会话状态变更 |
| `session.title` | `session.updated` | — | 会话标题更新 |
| `session.error` | `session.error` | — | 会话级错误 |
| `file` | `message.part.updated` (type=file) | — | 文件附件 |
| `planning.delta` | — | `data.type="planning"` (isFinish=false) | 规划增量（云端扩展） |
| `planning.done` | — | 最后一条 planning | 规划完成（云端扩展） |
| `searching` | — | `data.type="searching"` | 搜索关键词（云端扩展） |
| `search_result` | — | `data.type="searchResult"` | 搜索结果（云端扩展） |
| `reference` | — | `data.type="reference"` | 引用结果（云端扩展） |
| `ask_more` | — | `data.type="askMore"` | 追问建议（云端扩展） |

> **OpenCode 协议的完整事件流**：一次 text 回复的典型序列为 `message.part.updated`(初始,缓存partType) → `message.part.delta`*N(增量) → `message.part.updated`(完整内容,无delta) → `message.updated`(finish)。我们的协议简化了这个流程：`text.delta`*N → `text.done`(完整内容) → `step.done`(可选)。

---

### 5.3 event.type 总览

所有支持的事件类型按功能分类如下，云端服务根据自身能力选择需要返回的类型：

| 分类 | event.type | 说明 | 必须支持 |
|------|-----------|------|---------|
| **文本内容** | `text.delta` | 流式文本增量 | ✅ |
| | `text.done` | 文本片段完成（完整内容） | |
| | `thinking.delta` | 深度思考增量 | |
| | `thinking.done` | 深度思考完成（完整内容） | |
| **工具执行** | `tool.update` | 工具调用状态更新 | |
| | `step.start` | 执行步骤开始 | |
| | `step.done` | 执行步骤完成（含 usage） | |
| **交互** | `question` | 向用户提问，等待回答 | |
| | `permission.ask` | 请求用户授权 | |
| | `permission.reply` | 权限应答结果 | |
| **会话状态** | `session.status` | 会话状态变更（busy/idle/retry） | |
| | `session.title` | 会话标题更新 | |
| | `session.error` | 会话级错误 | |
| **文件** | `file` | 文件附件输出 | |
| **云端扩展** | `planning.delta` | 规划内容增量 | |
| | `planning.done` | 规划内容完成 | |
| | `searching` | 搜索中（关键词列表） | |
| | `search_result` | 搜索结果（支持多条，按 index 排序） | |
| | `reference` | 引用结果（支持多条，按 index 排序） | |
| | `ask_more` | 追问建议 | |
| **结束标记** | `tool_done` | 本次请求处理完成，会话进入 idle | ✅ |
| | `tool_error` | 本次请求处理异常 | ✅ |

> **最小实现**：云端只需实现 `text.delta` + `tool_done` 即可完成基本对话。其余类型按需选用。

---

### 5.3 文本内容类事件

#### 5.3.1 `text.delta` —— 流式文本增量

可多次返回，每次返回增量文本。文本内容可能包含自定义 Markdown 协议（见第 8 章）。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"迁","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"移","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"价","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 增量文本内容 |
| role | String | ✅ | 固定 `"assistant"` |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.3.2 `text.done` —— 文本片段完成

一个文本片段输出完成时发送，携带该片段的完整内容。可选——如果只用 `text.delta`，SS 会自行拼接。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"完整的回复文本内容...","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 完整文本内容 |
| role | String | ✅ | 固定 `"assistant"` |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.3.3 `thinking.delta` —— 深度思考增量

可多次返回，每次返回增量思考内容。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"嗯","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"，用户在问JDK版本对比","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 增量思考内容 |
| role | String | ✅ | 固定 `"assistant"` |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.3.4 `thinking.done` —— 深度思考完成

思考过程结束时发送，携带完整思考内容。可选。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.done","properties":{"content":"用户在问JDK8和21的区别，需要从语言特性、性能、生态等维度对比","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 完整思考内容 |
| role | String | ✅ | 固定 `"assistant"` |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

---

### 5.4 工具执行类事件

#### 5.4.1 `tool.update` —— 工具调用状态更新

云端 Agent 调用工具时发送，展示工具执行过程。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"tool.update","properties":{"toolName":"web_search","toolCallId":"call-001","status":"running","input":{"query":"JDK21 new features"},"output":null,"error":null,"title":"搜索 JDK21 新特性","messageId":"msg-001","partId":"prt-tool-01"}}}
```

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"tool.update","properties":{"toolName":"web_search","toolCallId":"call-001","status":"completed","input":{"query":"JDK21 new features"},"output":"JDK21 引入了虚拟线程...","error":null,"title":"搜索 JDK21 新特性","messageId":"msg-001","partId":"prt-tool-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| toolName | String | ✅ | 工具名称 |
| toolCallId | String | ✅ | 工具调用 ID |
| status | String | ✅ | `"pending"` / `"running"` / `"completed"` / `"error"` |
| input | Object | | 工具输入参数 |
| output | String | | 工具输出结果（completed 时） |
| error | String | | 错误信息（error 时） |
| title | String | | 工具调用的显示标题 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.4.2 `step.start` —— 执行步骤开始

云端 Agent 开始一个新的推理/执行步骤时发送。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.start","properties":{"messageId":"msg-001","role":"assistant"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| messageId | String | | 消息 ID |
| role | String | | 固定 `"assistant"` |

#### 5.4.3 `step.done` —— 执行步骤完成

一个执行步骤完成时发送，可携带该步骤的 token 用量。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.done","properties":{"messageId":"msg-001","usage":{"input_tokens":100,"output_tokens":50},"cost":0.003,"reason":"end_turn"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| messageId | String | | 消息 ID |
| usage | Object | | token 用量统计 |
| usage.input_tokens | Integer | | 输入 token 数 |
| usage.output_tokens | Integer | | 输出 token 数 |
| cost | Double | | 费用 |
| reason | String | | 结束原因（如 `"end_turn"`, `"max_tokens"`） |

---

### 5.5 交互类事件

#### 5.5.1 `question` —— 向用户提问

云端 Agent 需要用户回答问题时发送。用户回答后，SS 通过新的 invoke（action=chat）将答案传回。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"question","properties":{"toolCallId":"call-002","messageId":"msg-001","partId":"prt-q-01","header":"请确认操作","question":"您想查询哪个部门的信息？","options":["IT部","研发部","市场部"]}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| toolCallId | String | ✅ | 工具调用 ID（用户回答时需回传） |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |
| header | String | | 问题标题 |
| question | String | ✅ | 问题内容 |
| options | List\<String\> | | 可选项列表（无则为开放式问答） |

#### 5.5.2 `permission.ask` —— 请求用户授权

云端 Agent 需要用户授权某项操作时发送。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"permission.ask","properties":{"permissionId":"perm-001","permType":"file_access","title":"请求访问文件","metadata":{"path":"/data/report.xlsx"},"messageId":"msg-001","partId":"prt-perm-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| permissionId | String | ✅ | 权限请求 ID |
| permType | String | ✅ | 权限类型 |
| title | String | ✅ | 权限描述 |
| metadata | Object | | 权限相关的额外信息 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.5.3 `permission.reply` —— 权限应答结果

云端返回权限处理结果（通常在用户授权后由云端确认）。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"permission.reply","properties":{"permissionId":"perm-001","permType":"file_access","response":"once","status":"completed","messageId":"msg-001","partId":"prt-perm-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| permissionId | String | ✅ | 权限请求 ID |
| permType | String | ✅ | 权限类型 |
| response | String | ✅ | `"once"` / `"always"` / `"reject"` |
| status | String | | 状态 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

---

### 5.6 会话状态类事件

#### 5.6.1 `session.status` —— 会话状态变更

通知 SS 当前会话的处理状态。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"session.status","properties":{"status":"busy"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| status | String | ✅ | `"busy"` / `"idle"` / `"retry"` |

状态归一化规则：

| 云端原始值 | 归一化后 |
|-----------|---------|
| active, running, busy | `busy` |
| idle, completed | `idle` |
| reconnecting, retry, recovering | `retry` |

#### 5.6.2 `session.title` —— 会话标题更新

云端根据对话内容自动生成/更新会话标题。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"session.title","properties":{"title":"JDK8 与 JDK21 的区别对比"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| title | String | ✅ | 会话标题 |

#### 5.6.3 `session.error` —— 会话级错误

会话级别的错误（非终止流），如上下文溢出等。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"session.error","properties":{"error":"ContextOverflowError","message":"上下文长度超出限制"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| error | String | ✅ | 错误名称 |
| message | String | | 错误描述 |

> **特殊处理**：当 error 为 `"ContextOverflowError"` 时，SS 会触发会话重建流程。

---

### 5.7 文件类事件

#### 5.7.1 `file` —— 文件附件输出

云端 Agent 输出文件时发送。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"file","properties":{"fileName":"report.xlsx","fileUrl":"https://cdn.example.com/files/report.xlsx","fileMime":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","messageId":"msg-001","partId":"prt-file-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| fileName | String | ✅ | 文件名 |
| fileUrl | String | ✅ | 文件下载地址 |
| fileMime | String | | MIME 类型 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

---

### 5.8 云端扩展类事件

以下事件为云端特有类型，本地 PC Agent 不产生。

#### 5.8.1 `planning.delta` —— 规划内容增量

可多次返回，每次返回增量文本。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.delta","properties":{"content":"用户询问JDK8和JDK21的区别，需","messageId":"msg-001","partId":"prt-plan-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.delta","properties":{"content":"要分别搜索两者的特性并进行对比","messageId":"msg-001","partId":"prt-plan-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 增量规划内容 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.8.2 `planning.done` —— 规划内容完成

规划阶段结束时发送，携带完整规划内容。可选——如果只用 `planning.delta`，SS 会自行拼接。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.done","properties":{"content":"用户询问JDK8和JDK21的区别，需要分别搜索两者的特性并进行对比","messageId":"msg-001","partId":"prt-plan-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| content | String | ✅ | 完整规划内容 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.8.3 `searching` —— 搜索中

一次性返回完整搜索关键词列表。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"searching","properties":{"keywords":["JDK8","JDK21"],"messageId":"msg-001","partId":"prt-search-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| keywords | List\<String\> | ✅ | 搜索关键词列表（可以是空数组） |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

#### 5.8.4 `search_result` —— 搜索结果

一次性返回完整搜索结果列表。支持多条结果，按 `index` 字段升序排列。可多次返回（多轮搜索场景），每次返回一批结果。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"search_result","properties":{"results":[{"index":"1","title":"java学习系列15","source":"CSDN博客"},{"index":"2","title":"JAVA8新特性","source":"菜鸟教程"},{"index":"3","title":"JDK21发布说明","source":"Oracle官网"}],"messageId":"msg-001","partId":"prt-sr-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| results | List\<SearchResult\> | ✅ | 搜索结果列表，按 index 升序排列（可以是空数组） |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

**SearchResult 对象**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| index | String | ✅ | 序号（从 "1" 开始，全局递增，跨多次 search_result 不重复） |
| title | String | ✅ | 标题 |
| source | String | ✅ | 来源 |

#### 5.8.5 `reference` —— 引用结果

一次性返回完整引用列表。支持多条引用，按 `index` 字段升序排列。index 与 `search_result` 中的 index 对应，用于文本中的角标引用（如 `[1]`）。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"reference","properties":{"references":[{"index":"1","title":"java学习系列15","url":"https://blog.csdn.net/xxx","source":"CSDN博客","content":"JDK8是JAVA开发工具包的一个版本..."},{"index":"2","title":"Java8新特性","url":"https://www.runoob.com/java/java8-new-features.html","source":"菜鸟教程","content":"Java8是java语言开发的一个主要版本..."},{"index":"3","title":"JDK21发布说明","url":"https://openjdk.org/projects/jdk/21/","source":"Oracle官网","content":"JDK 21 is the open-source reference implementation..."}],"messageId":"msg-001","partId":"prt-ref-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| references | List\<Reference\> | ✅ | 引用列表，按 index 升序排列 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

**Reference 对象**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| index | String | ✅ | 序号 |
| title | String | ✅ | 标题 |
| source | String | ✅ | 来源 |
| url | String | ✅ | 链接（仅支持 https/http） |
| content | String | ✅ | 内容摘要 |

#### 5.8.6 `ask_more` —— 追问建议

一次性返回追问建议列表。

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"ask_more","properties":{"questions":["Lambda表达式可以用于哪些典型场景？","JDK8的Lambda表达式如何使用"],"messageId":"msg-001","partId":"prt-askmore-01"}}}
```

| properties 字段 | 类型 | 必填 | 说明 |
|----------------|------|------|------|
| questions | List\<String\> | ✅ | 追问问题列表 |
| messageId | String | | 消息 ID |
| partId | String | | 片段 ID |

---

### 5.9 结束标记消息

#### 5.9.1 `tool_done` —— 本次请求处理完成

标记本次 chat invoke 的处理完成，会话进入 idle 状态。**是本次 SSE 流的最后一条消息。**

> **重要语义说明**：`tool_done` 表示的是**一次请求（invoke）的处理完成**，不是对话/会话的终结。会话仍然存在，用户可以继续发送新消息开启新一轮对话。SS 收到 tool_done 后会将会话状态标记为 idle 并广播给前端。

```
data: {"type":"tool_done","toolSessionId":"ts-789","usage":{"input_tokens":150,"output_tokens":320}}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| usage | Object | | 可选，token 用量统计 |
| usage.input_tokens | Integer | | 输入 token 数 |
| usage.output_tokens | Integer | | 输出 token 数 |

#### 5.9.2 `tool_error` —— 本次请求处理异常

云端处理异常时返回。返回后本次 SSE 流结束，**不需要再发 `tool_done`**。与 `tool_done` 一样，不代表会话终结，用户可以重试或发送新消息。

```
data: {"type":"tool_error","toolSessionId":"ts-789","error":"internal_error","reason":"模型推理超时"}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| error | String | ✅ | 错误码 |
| reason | String | | 错误描述 |

**云端错误码**：

| error | 说明 |
|-------|------|
| `internal_error` | 云端内部错误 |
| `model_timeout` | 模型推理超时 |
| `rate_limited` | 流量限制 |
| `invalid_input` | 输入内容不合规 |

#### 5.9.3 事件有序性保证

云端场景下的事件有序性由传输层保证：

- **SSE**：HTTP 响应流是严格有序的，GW 按顺序读取每条 `data:` 行并逐条转发给 SS
- **WebSocket**（未来扩展）：WebSocket 消息在单连接内也是有序的

`tool_done` 之前的所有 `tool_event` 一定先于 `tool_done` 到达 SS，不存在事件丢失风险。

---

### 5.10 完整响应示例

#### 示例一：简单文本对话（最小实现）

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"你好","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"！有什么可以帮你的？","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"你好！有什么可以帮你的？","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_done","toolSessionId":"ts-789"}
```

#### 示例二：带思考和文本的完整对话

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.start","properties":{"messageId":"msg-001","role":"assistant"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"用户想了解","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"Spring Boot的优势","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.done","properties":{"content":"用户想了解Spring Boot的优势","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"Spring Boot 的主要优势：\n\n","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"1. 自动配置\n2. 起步依赖","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"Spring Boot 的主要优势：\n\n1. 自动配置\n2. 起步依赖","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.done","properties":{"messageId":"msg-001","usage":{"input_tokens":50,"output_tokens":30},"reason":"stop"}}}
data: {"type":"tool_done","toolSessionId":"ts-789","usage":{"input_tokens":50,"output_tokens":30}}
```

#### 示例三：带工具调用的对话

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.start","properties":{"messageId":"msg-001","role":"assistant"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"用户想查员工信息，需要调用人事系统","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.done","properties":{"content":"用户想查员工信息，需要调用人事系统","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"tool.update","properties":{"toolName":"hr_query","toolCallId":"call-001","status":"pending","input":{"employeeId":"300518xx"},"title":"查询员工信息","messageId":"msg-001","partId":"prt-tool-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"tool.update","properties":{"toolName":"hr_query","toolCallId":"call-001","status":"running","input":{"employeeId":"300518xx"},"title":"查询员工信息","messageId":"msg-001","partId":"prt-tool-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"tool.update","properties":{"toolName":"hr_query","toolCallId":"call-001","status":"completed","input":{"employeeId":"300518xx"},"output":"{\"name\":\"张三\",\"dept\":\"IT部\"}","title":"查询员工信息","messageId":"msg-001","partId":"prt-tool-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"查询到员工信息：","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"张三，IT部","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"查询到员工信息：张三，IT部","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"step.done","properties":{"messageId":"msg-001","usage":{"input_tokens":200,"output_tokens":80},"reason":"stop"}}}
data: {"type":"tool_done","toolSessionId":"ts-789","usage":{"input_tokens":200,"output_tokens":80}}
```

#### 示例四：带搜索和引用的完整对话（appId: "uniassistant"）

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.delta","properties":{"content":"用户询问JDK8和JDK21的区别，需","messageId":"msg-001","partId":"prt-plan-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.delta","properties":{"content":"要分别搜索两者的特性并进行对比","messageId":"msg-001","partId":"prt-plan-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"planning.done","properties":{"content":"用户询问JDK8和JDK21的区别，需要分别搜索两者的特性并进行对比","messageId":"msg-001","partId":"prt-plan-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"searching","properties":{"keywords":["JDK8特性","JDK21特性"],"messageId":"msg-001","partId":"prt-search-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"search_result","properties":{"results":[{"index":"1","title":"java学习系列15","source":"CSDN博客"},{"index":"2","title":"JAVA8新特性","source":"菜鸟教程"}],"messageId":"msg-001","partId":"prt-sr-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"reference","properties":{"references":[{"index":"1","title":"java学习系列15","url":"https://blog.csdn.net/xxx","source":"CSDN博客","content":"JDK8是JAVA开发工具包的一个版本..."},{"index":"2","title":"Java8新特性","url":"https://www.runoob.com/java/java8-new-features.html","source":"菜鸟教程","content":"Java8是java语言开发的一个主要版本..."}],"messageId":"msg-001","partId":"prt-ref-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.delta","properties":{"content":"嗯，用户在问版本区别，我需要整理对比","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"thinking.done","properties":{"content":"嗯，用户在问版本区别，我需要整理对比","role":"assistant","messageId":"msg-001","partId":"prt-think-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"# JDK8 vs JDK21 主要区别\n\n","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"JDK8 引入了 Lambda 表达式和 Stream API[1]...","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"# JDK8 vs JDK21 主要区别\n\nJDK8 引入了 Lambda 表达式和 Stream API[1]...","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}

data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"ask_more","properties":{"questions":["Lambda表达式可以用于哪些典型场景？","JDK21的虚拟线程如何使用？"],"messageId":"msg-001","partId":"prt-askmore-01"}}}

data: {"type":"tool_done","toolSessionId":"ts-789","usage":{"input_tokens":150,"output_tokens":320}}
```

#### 示例五：带交互式问答的对话

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"我需要了解更多信息","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"我需要了解更多信息","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"question","properties":{"toolCallId":"call-003","messageId":"msg-001","partId":"prt-q-01","header":"请选择查询范围","question":"您想查询哪个部门的信息？","options":["IT部","研发部","市场部"]}}}
data: {"type":"tool_done","toolSessionId":"ts-789"}
```

> 用户回答后，SS 通过新的 invoke 将答案传回，云端继续处理并返回新的 SSE 流。

#### 示例六：带权限请求的对话

```
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.delta","properties":{"content":"需要访问您的文件来完成分析","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"text.done","properties":{"content":"需要访问您的文件来完成分析","role":"assistant","messageId":"msg-001","partId":"prt-text-01"}}}
data: {"type":"tool_event","toolSessionId":"ts-789","event":{"type":"permission.ask","properties":{"permissionId":"perm-001","permType":"file_access","title":"请求访问 report.xlsx","metadata":{"path":"/data/report.xlsx"},"messageId":"msg-001","partId":"prt-perm-01"}}}
data: {"type":"tool_done","toolSessionId":"ts-789"}
```

> 用户授权后，SS 通过 invoke 将权限回复传回。

### 5.11 事件顺序约束

**delta/done 配对规则**：
- 流式内容类型（text/thinking/planning）遵循 `delta`*N → `done` 的模式
- `delta` 携带增量内容，`done` 携带该片段的完整内容
- `done` 事件是可选的，SS 可以从 delta 自行拼接完整内容；但**建议云端在每种流式内容结束时发送对应的 done 事件**

**整体顺序规则**：
- 一次完整回复的典型流程：`step.start`? → `thinking.delta`*N → `thinking.done`? → `text.delta`*N → `text.done`? → `step.done`? → `tool_done`
- `tool.update` 可穿插在文本之间（工具调用场景）：`thinking` → `tool.update`(pending→running→completed) → `text` → `step.done` → `tool_done`
- `question` 和 `permission.ask` 发出后，发送 `tool_done` 结束本次 SSE 流，用户回答后 SS 会发起新的 invoke
- 云端扩展类事件遵循以下顺序：`planning.delta`*N → `planning.done`? → `searching` → `search_result` → `reference`? → 其他事件 → `ask_more`?
- `tool_done` 或 `tool_error` 必须是本次 SSE 流的最后一条（二选一）
- 并非所有类型都必须出现，最简场景只需 `text.delta`*N → `text.done` → `tool_done`
- `tool_done` / `tool_error` 只表示一次请求处理完成，不代表会话终结。用户可继续发消息开启新一轮请求

---

## 6. P4：GW → SS（上行消息）

### 6.1 处理逻辑

Gateway 读取云端 SSE 流后，逐条转发给 SS。处理规则：

1. **读取 SSE 流**：逐条读取 `data:` 行，解析为 JSON
2. **注入路由上下文**：注入 `ak`、`userId`、`traceId`、`welinkSessionId`（从原始 invoke 消息中获取）
3. **注入 toolSessionId**：如果云端响应中未携带 toolSessionId，GW 从原始 invoke 的 `payload.toolSessionId` 注入
4. **投递给 SS**：复用现有 `SkillRelayService.relayToSkill()` 上行路由逻辑

### 6.2 消息格式示例

GW 注入上下文后发给 SS 的 `tool_event`：

```json
{
  "type": "tool_event",
  "ak": "agent-ak-123",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "toolSessionId": "ts-local-generated-789",
  "event": {
    "type": "text.delta",
    "properties": {
      "content": "JDK8 引入了 Lambda 表达式...",
      "role": "assistant"
    }
  }
}
```

GW 注入上下文后发给 SS 的 `tool_done`：

```json
{
  "type": "tool_done",
  "ak": "agent-ak-123",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "toolSessionId": "ts-local-generated-789",
  "usage": {
    "input_tokens": 150,
    "output_tokens": 320
  }
}
```

GW 注入上下文后发给 SS 的 `tool_error`：

```json
{
  "type": "tool_error",
  "ak": "agent-ak-123",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "toolSessionId": "ts-local-generated-789",
  "error": "cloud_service_unavailable",
  "reason": "HTTP 503: Service Temporarily Unavailable"
}
```

### 6.3 与本地 Agent 上行消息的兼容性

| 维度 | 本地 Agent 上行 | 云端上行 |
|------|----------------|---------|
| type | tool_event / tool_done / tool_error | 相同 |
| ak / userId / traceId | GW 从 Redis 查询注入 | GW 从原始 invoke 消息中获取注入 |
| toolSessionId | Agent 返回 | SS 本地生成，GW 从 invoke 注入 |
| event.type | message.part.delta / session.status 等 | text.delta / think.delta / planning.delta / searching / search_result / reference / ask_more |
| session_created | Agent 返回 | 无（SS 本地完成） |
| agent_online / agent_offline | GW 生成 | 无（业务助手永远在线） |

SS 侧通过 `event.type` 区分消息来源并分别处理。

---

## 7. SS 侧业务处理差异

### 7.1 会话创建流程对比

**个人助手（现有）**：
```
SS 创建 SkillSession(toolSessionId=null)
  → 发送 invoke(action=create_session) 到 GW
  → GW 转发给 PC Agent
  → Agent 返回 session_created(toolSessionId=xxx)
  → SS 绑定 toolSessionId
  → SS 消费待发消息队列
```

**业务助手（新增）**：
```
SS 创建 SkillSession(toolSessionId=SS本地生成)
  → 会话直接就绪，无需等待回调
  → 直接发送 invoke(action=chat) 到 GW
```

### 7.2 Agent 在线检查

| 助手类型 | 在线检查 |
|---------|---------|
| 个人助手 | 检查 PC Agent 在线状态，离线则返回错误 |
| 业务助手 | 跳过在线检查（视为永远在线），云端不可用时通过 tool_error 实时反馈 |

### 7.3 事件翻译（OpenCodeEventTranslator 扩展）

SS 收到云端 event 后需翻译为 StreamMessage 推送前端：

| 云端 event.type | StreamMessage.type | 处理说明 |
|----------------|-------------------|---------|
| text.delta | text.delta | 复用现有逻辑 |
| text.done | text.done | 复用现有逻辑 |
| thinking.delta | thinking.delta | 复用现有逻辑 |
| thinking.done | thinking.done | 复用现有逻辑 |
| tool.update | tool.update | 复用现有逻辑 |
| step.start | step.start | 复用现有逻辑 |
| step.done | step.done | 复用现有逻辑 |
| question | question | 复用现有逻辑 |
| permission.ask | permission.ask | 复用现有逻辑 |
| permission.reply | permission.reply | 复用现有逻辑 |
| session.status | session.status | 复用现有逻辑 |
| session.title | session.title | 复用现有逻辑 |
| session.error | session.error | 复用现有逻辑 |
| file | file | 复用现有逻辑 |
| planning.delta | planning.delta（新增） | 新增 StreamMessage 类型 |
| planning.done | planning.done（新增） | 新增 StreamMessage 类型 |
| searching | searching（新增） | 新增 StreamMessage 类型 |
| search_result | search_result（新增） | 新增 StreamMessage 类型 |
| reference | reference（新增） | 新增 StreamMessage 类型 |
| ask_more | ask_more（新增） | 新增 StreamMessage 类型 |

> 云端直接使用 StreamMessage 的 event.type 命名，SS 可以直接映射，无需像本地 Agent 那样从 OpenCode 协议翻译。

### 7.4 IM 出站处理

IM 场景下，只发送 `text.delta` 的最终聚合文本到 IM 平台。以下过程性事件**不发送**到 IM：

- `planning.delta` —— 规划过程
- `think.delta` —— 思考过程
- `searching` —— 搜索关键词
- `search_result` —— 搜索结果
- `reference` —— 引用列表
- `ask_more` —— 追问建议

---

## 8. 自定义 Markdown 协议规范

云端回复的 `text.delta` 内容中可能包含自定义 Markdown 协议，用于在客户端实现交互功能。SS 和 GW 透传这些内容，由前端/IM 客户端负责解析和执行。

### 8.1 发送文本消息

用户点击后，客户端直接发送指定文本消息到当前对话。

**Markdown 格式**：
```markdown
[显示文案](data:{"action":"sendTextMsg","value":"要发送的内容","actionParam":""})
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| action | String | 固定 `"sendTextMsg"` |
| value | String | 点击后直接发送的文本内容 |
| actionParam | String | 额外自定义参数，随 chat 接口发送到服务端。业务方应将其塞入 `cloudRequest.extParameters.businessExtParam.actionParam`（嵌套结构升级后的位置） |

**示例**：
```markdown
您好，以下是我所了解到的关于xxx的信息
工号：300518xx
所属部门：xxx部->xxx部->xxxx部->xxxx
直接主管：[田xx 654321](data:{"action":"sendTextMsg","value":"请帮我介绍下员工田xx(654321)","actionParam":""})
部门主管：[许xx 123456](data:{"action":"sendTextMsg","value":"请帮我介绍下员工许xx(123456)","actionParam":""})
```

**特殊场景 —— withoutSkillParams**：

当 `actionParam` 是 JSON 字符串且包含 `"withoutSkillParam":"1"` 时，PC 端发送消息到服务端时不携带当前选中的技能参数，使用助手配置的默认技能参数。

```markdown
[许xx 123456](data:{"action":"sendTextMsg","value":"请帮我介绍下员工许xx(123456)","actionParam":"{\\\"withoutSkillParams\\\":\\\"1\\\"}"})
```

### 8.2 打开单聊或群聊

用户点击后，客户端打开指定的单聊或群聊窗口。

**Markdown 格式**：
```markdown
[显示文案](data:{"action":"openIMChat","value":"联系人ID或群聊ID","actionParam":"0或1"})
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| action | String | 固定 `"openIMChat"` |
| value | String | 要打开的群聊 ID 或联系人 ID（联系人 ID 格式为姓名首字母 + 8 位数字） |
| actionParam | String | `"0"` = 打开单聊，`"1"` = 打开群聊 |

**示例**：
```markdown
单聊：
[去聊天](data:{"action":"openIMChat","value":"h30071xxx","actionParam":"0"})

群聊：
[去聊天](data:{"action":"openIMChat","value":"941884899239932688","actionParam":"1"})
```

### 8.3 格式注意事项

- Markdown 链接中的 `data:` JSON 体内**不能有空格**
- 如确实需要显示空格，使用 Unicode 四分之一空格 `\u2005` 代替
- JSON 中的引号需要正确转义

---

## 9. 云端 IM 推送接口

### 9.1 概述

云端 Agent 有定时任务推送功能，需要主动向 IM 单聊/群聊发送消息。此接口独立于对话流程，是单向推送。

**消息流**：

```
云端定时任务 → GW REST 接口 → GW 构建 im_push → WS 通道 → SS → IM 出站
```

### 9.2 云端 → GW（REST 接口）

**请求**：

```
POST /api/gateway/cloud/im-push
Content-Type: application/json
{认证头由 GW 验证}
```

**请求体**：

```json
{
  "assistantAccount": "assistant-bot-001",
  "userAccount": "c30051824",
  "imGroupId": null,
  "topicId": "cloud-1001214",
  "content": "您好，这是定时推送的消息内容"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| assistantAccount | String | ✅ | 以哪个助手身份发送 |
| userAccount | String | ✅ | 目标用户账号 |
| imGroupId | String | | 群 ID。有值 → 群聊推送；null → 单聊推送 |
| topicId | String | ✅ | 会话主题 ID（= toolSessionId），用于 GW 路由到对应 SS 实例 |
| content | String | ✅ | 文本内容（可含自定义 Markdown 协议） |

**响应**：

| 状态码 | 说明 |
|--------|------|
| 200 | 推送成功 |
| 400 | 参数校验失败（assistantAccount/content 为空、无效 assistantAccount、单聊缺 userAccount） |
| 403 | 单聊 userAccount 与助手创建人（create_by）不匹配 |

**GW 安全校验**：

1. 基础校验：assistantAccount、content 非空
2. 调上游 resolve API 验证 assistantAccount 有效性（`GET /assistant-api/.../query?partnerAccount={assistantAccount}`），获取 `create_by`
3. 单聊（imGroupId 为空）：校验 `userAccount == create_by`
4. 群聊：不校验 userAccount，仅校验 assistantAccount 有效

resolve 结果 Redis 缓存：`gw:assistant:resolve:{assistantAccount}`，TTL 300s。

### 9.3 GW → SS（WS 通道）

校验通过后，GW 构建 `GatewayMessage(type="im_push")` 通过现有 `/ws/skill` WS 通道发给 SS：

```json
{
  "type": "im_push",
  "toolSessionId": "1001214",
  "traceId": "trace-uuid-xxx",
  "payload": {
    "assistantAccount": "assistant-bot-001",
    "userAccount": "c30051824",
    "imGroupId": null,
    "content": "您好，这是定时推送的消息内容"
  }
}
```

### 9.4 SS 处理

SS 收到 `im_push` 后：
- 根据 `assistantAccount` + `userAccount` / `imGroupId` 确定 IM 发送目标
- 直接调用 IM 出站接口发送消息
- **不走事件翻译**（不经过 Translator）
- **不推送到 MiniApp 前端**

> GW 通过 `toolSessionId`（= topicId）使用现有上行路由机制（`gw:route:{toolSessionId}`）精确投递到拥有该会话的 SS 实例。

---

## 10. TODO：云端 Question/Permission 旁路回复接口

> **不纳入本次设计和实施范围。** 以下为预定义协议，供后续实施参考。首期要求云端避免返回 question/permission 事件。

### 10.1 概述

云端 SSE 是单向流（云端 → GW），当云端发出 `question` 或 `permission.ask` 事件后，SSE 连接保持打开等待回复。用户的回复需要通过独立的 REST 接口（旁路）发送给云端，云端收到后继续在同一 SSE 流上推送后续事件。

**消息流**：

```
阶段 1：云端提问
  云端 SSE → question/permission.ask → GW → SS → 前端展示
  （SSE 连接保持打开，云端等待回复）

阶段 2：用户回复（旁路 REST）
  用户回答 → SS 构建回复 → SS 发 invoke(action=question_reply/permission_reply) → GW
  → GW 调云端旁路 REST 接口发送回复

阶段 3：云端继续
  云端收到回复 → 继续在同一 SSE 流上推送后续事件 → GW → SS → 前端
  → 最终 tool_done → SSE 结束
```

### 10.2 SS → GW（invoke 消息）

复用现有 invoke 格式，`action` 为 `question_reply` 或 `permission_reply`：

**question_reply**：

```json
{
  "type": "invoke",
  "ak": "agent-ak-123",
  "source": "skill-server",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "action": "question_reply",
  "assistantScope": "business",
  "payload": {
    "toolSessionId": "cloud-1001214",
    "answer": "Yes",
    "toolCallId": "call-q001"
  }
}
```

**permission_reply**：

```json
{
  "type": "invoke",
  "ak": "agent-ak-123",
  "source": "skill-server",
  "userId": "user-001",
  "welinkSessionId": "1234567890",
  "traceId": "trace-uuid-xxx",
  "action": "permission_reply",
  "assistantScope": "business",
  "payload": {
    "toolSessionId": "cloud-1001214",
    "permissionId": "perm-001",
    "response": "once"
  }
}
```

### 10.3 GW → 云端（旁路 REST 接口）

GW 收到 `question_reply` / `permission_reply` 的 invoke 后，不走 SSE，而是调用云端的旁路 REST 接口。

**接口规范**（我们定义，云端适配）：

```
POST {cloudEndpoint}/reply
Content-Type: application/json
{认证头由 GW 填充}
X-Trace-Id: {traceId}
X-App-Id: {appId}
```

**question_reply 请求体**：

```json
{
  "type": "question_reply",
  "topicId": "cloud-1001214",
  "toolCallId": "call-q001",
  "answer": "Yes"
}
```

**permission_reply 请求体**：

```json
{
  "type": "permission_reply",
  "topicId": "cloud-1001214",
  "permissionId": "perm-001",
  "response": "once"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"question_reply"` / `"permission_reply"` |
| topicId | String | ✅ | 会话主题 ID（= toolSessionId，关联到正在等待的 SSE 连接） |
| toolCallId | String | 条件 | question_reply 时必填，对应 question 事件的 toolCallId |
| answer | String | 条件 | question_reply 时必填，用户的回答 |
| permissionId | String | 条件 | permission_reply 时必填，对应 permission.ask 的 permissionId |
| response | String | 条件 | permission_reply 时必填，`"once"` / `"always"` / `"reject"` |

**响应**：

```json
{ "code": "200", "message": "success" }
```

### 10.4 已知待解决问题

实施前需解决以下问题：

1. **多 GW 实例映射**：SSE 连接在 GW-A 上，reply invoke 可能路由到 GW-B，本地内存映射无法跨实例。需 Redis 共享或定向路由
2. **旁路 REST 端点**：不应简单拼接 `{endpoint}/reply`，需由上游接口单独提供 reply endpoint
3. **线程占用**：SSE 连接等待回复期间阻塞读取线程，需异步方案（WebClient）

---

## 11. 扩展性设计

### 11.1 新增业务助手（appId）

| 步骤 | 责任方 | 改动 |
|------|--------|------|
| 1. 注册 appId，配置 endpoint/authType | 上游助手管理平台 | 新增配置 |
| 2. 增加 cloudRequest 构建逻辑 | SS | 根据 appId 构建对应格式的请求体 |
| 3. 按 P3 协议适配响应格式 | 云端服务 | 返回标准 tool_event/tool_done/tool_error |
| 4. 增加 authType 认证实现（如已有则跳过） | GW | 仅新 authType 时需要 |

**GW 不需要为每个新 appId 做改动**——GW 通过 ak 调上游接口获取路由信息。

### 11.2 新增 event.type

1. 在本协议 P3 章节注册新的 `event.type`
2. 定义 `event.properties` 结构
3. SS 侧 `OpenCodeEventTranslator` 增加翻译逻辑
4. 前端增加渲染支持
5. GW 无需改动（透传）

### 11.3 新增 action 类型

如未来业务助手需要支持 `close_session` 等：
1. 扩展 P1 的 `action` 字段
2. 在 `cloudRequest` 中定义对应请求体
3. 云端适配对应接口
4. GW 无需改动（透传）

### 11.4 新增传输协议

如需支持 WebSocket（双向）：
1. 上游 API 返回 `protocol = "websocket"`
2. GW 的 `CloudProtocolClient` 增加 WebSocket 策略实现
3. 请求/响应格式不变，仅传输层切换

### 11.5 新增认证方式

1. 上游 API 返回新 `authType` 值
2. GW 实现对应认证策略
3. SS 和云端无需改动
