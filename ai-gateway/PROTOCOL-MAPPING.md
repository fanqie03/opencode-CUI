# AI Gateway 协议映射关系文档

## 1. 协议编码映射

### 1.1 上游协议编码 → 内部协议名称

| 编码 | 协议名称 | 策略类 | 文件路径 |
|------|----------|--------|----------|
| `1` | rest | `RestProtocolStrategy` | 现有 |
| `2` | sse | `SseProtocolStrategy` | 现有 |
| `3` | websocket | `WebSocketProtocolStrategy` | 现有 |
| `6` | uniknow | `UniKnowProtocolStrategy` | 新增 |
| `8` | standard | `StandardProtocolStrategy` | 新增 |

### 1.2 认证类型编码映射

| 编码 | 认证类型 | 说明 |
|------|----------|------|
| `1` | soa | SOA Token 认证 |
| `2` | apig | APIG Token 认证 |
| 默认 | 原值 | 自定义认证类型 |

---

## 2. 协议策略实现概览

### 2.1 映射说明

本文档定义两套云端协议（**标准协议**、**UniKnow协议**）到 **ai-gateway 远端协议 v2** 的映射关系。

**核心映射方向**：
```
云端协议请求 → ai-gateway cloudRequest
ai-gateway 响应事件 ← 云端协议响应
```

---

## 3. ai-gateway 远端协议 v2 结构（目标协议）

### 3.1 请求体结构（cloudRequest）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 内容类型，默认 `"text"` |
| `content` | String | ✅ | 用户输入文本 |
| `assistantAccount` | String | | 助理账号 |
| `sendUserAccount` | String | | 发起请求的用户账号 |
| `imGroupId` | String | | IM 群聊 ID |
| `clientLang` | String | ✅ | 默认 `"zh"` |
| `clientType` | String | | 客户端类型 |
| `topicId` | String | ✅ | 会话主题 ID，等价 toolSessionId |
| `messageId` | String | | 消息 ID（用于幂等） |
| `extParameters` | Object | ✅ | 扩展参数容器，始终为 object |
| `extParameters.businessExtParam` | Object | ✅ | 业务方自由扩展 |
| `extParameters.platformExtParam` | Object | ✅ | 平台扩展 |

### 3.2 响应事件结构

**事件包络**：
```json
{
    "type": "tool_event" | "tool_done" | "tool_error",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "<子事件类型>",
        "properties": { ... }
    }
}
```

**支持的子事件类型**：

| 子事件类型 | 说明 | properties 关键字段 |
|-----------|------|-------------------|
| `text.delta` | 流式文本增量 | `content`, `role`, `messageId`, `partId` |
| `text.done` | 文本片段完成 | `content`, `role`, `messageId`, `partId` |
| `thinking.delta` | 深度思考增量 | `content`, `role`, `messageId`, `partId` |
| `thinking.done` | 深度思考完成 | `content`, `role`, `messageId`, `partId` |
| `planning.delta` | 规划内容增量 | `content`, `messageId`, `partId` |
| `planning.done` | 规划内容完成 | `content`, `messageId`, `partId` |
| `searching` | 搜索中 | `keywords`, `messageId`, `partId` |
| `search_result` | 搜索结果 | `searchResults`, `messageId`, `partId` |
| `reference` | 引用结果 | `references`, `messageId`, `partId` |
| `ask_more` | 追问建议 | `askMoreQuestions`, `messageId`, `partId` |
| `tool_done` | 完成（终态） | `usage`（外层） |
| `tool_error` | 错误（终态） | `error`（外层） |

---

## 4. 标准协议 → ai-gateway 协议映射

### 4.1 协议类型

- **流式**: SSE（Server-Sent Events）
- **非流式**: REST（同步请求，仅支持 text 类型）

### 4.2 请求体映射

| 标准协议字段 | 标准协议类型 | ai-gateway cloudRequest 字段 | cloudRequest 类型 | 说明 | 默认值 |
|--------------|--------------|------------------------------|-------------------|------|--------|
| `type` | String | `type` | String | 内容类型 | `"text"` |
| `content` | String | `content` | String | 用户输入内容 | - |
| `sendUserAccount` | String | `sendUserAccount` | String | 发送人账号 | - |
| `imGroupId` | String | `imGroupId` | String | IM 群组 ID | - |
| `clientLang` | String | `clientLang` | String | 客户端语言 | `"zh"` |
| `clientType` | String | `clientType` | String | 客户端类型 | - |
| `topicId` | **long** | `topicId` | **String** | 会话主题 ID，需做类型转换 | - |
| `messageId` | **long** | `messageId` | **String** | 消息 ID，需做类型转换 | - |
| `extParameters` | Object | `extParameters.businessExtParam` | Object | 扩展参数 | `{}` |
| - | - | `extParameters.platformExtParam` | Object | 平台扩展 | `{}` |

> **类型转换注意**：标准协议中的 `topicId` 和 `messageId` 为 `long` 类型，映射到 ai-gateway 的 `cloudRequest` 时需要转换为 `String` 类型。

### 4.2.1 字段差异分析

**标准协议请求 body 中有但 cloudRequest 中没有的字段**：

| 标准协议字段 | 类型 | 说明 | 映射处理方式 |
|--------------|------|------|--------------|
| `extParameters.isHwEmployee` | Boolean | 是否是华为雇员 | 放入 `extParameters.businessExtParam` |
| `extParameters.actionParam` | String | 机器人自定义参数 | 放入 `extParameters.businessExtParam` |
| `extParameters.filesCard` | Array | 文件卡片列表（type=filesCard 时必传） | 放入 `extParameters.businessExtParam` |
| `extParameters.filesCard[].id` | String | 文件 ID | 放入 `extParameters.businessExtParam.filesCard` |
| `extParameters.filesCard[].order` | int | 文件顺序 | 放入 `extParameters.businessExtParam.filesCard` |
| `extParameters.knowledgeId` | Array\<String\> | 知识库 ID 列表 | 放入 `extParameters.businessExtParam` |

**cloudRequest 中有但标准协议请求 body 中没有的字段**：

| cloudRequest 字段 | 类型 | 说明 | 处理方式 |
|-------------------|------|------|----------|
| `assistantAccount` | String | 助理账号 | 标准协议不使用，忽略 |
| `extParameters.platformExtParam` | Object | 平台扩展参数 | 标准协议不使用，忽略 |

**映射策略**：标准协议的 `extParameters` 下的所有子字段，在映射到 cloudRequest 时统一放入 `extParameters.businessExtParam` 中。

### 4.3 请求头映射

| 标准协议请求头 | ai-gateway 请求头 | 说明 |
|----------------|------------------|------|
| `Authorization: soa_token` | `X-Auth-Type: soa`, `X-App-Id` | SOA token 认证 |
| `Authorization: iam_token` | `X-Auth-Type: soa`, `X-App-Id` | IAM token 认证 |
| `Authorization: 集成账号token` | `X-Auth-Type: soa`, `X-App-Id` | 集成账号 token |
| `x-hw-id: xxx` | 透传到 `extParameters` | 华为标识 |
| `x-appkey: xxx` | 透传到 `extParameters` | 应用 key |
| `cookie: 个人cookie` | 透传到 `extParameters` | 附加 cookie |
| 自定义 key-value | 透传到 `extParameters` | 自定义认证 |

### 4.4 响应事件映射

**标准协议响应结构**：
```json
{
    "code": "0",
    "message": "提示信息",
    "error": "异常信息",
    "isFinish": false,
    "data": {
        "type": "<数据类型>",
        "content": "...",
        "planning": "...",
        "searching": [],
        "searchResult": [],
        "references": [],
        "askMore": []
    }
}
```

**事件类型映射表**：

| 标准协议 data.type | ai-gateway 事件类型 | ai-gateway event.type | 说明 |
|---------------------|---------------------|----------------------|------|
| `text` | `tool_event` | `text.delta` | 文本内容增量 |
| `planning` | `tool_event` | `planning.delta` | 规划中 |
| `searching` | `tool_event` | `searching` | 搜索中 |
| `searchResult` | `tool_event` | `search_result` | 搜索结果 |
| `reference` | `tool_event` | `reference` | 引用 |
| `think` | `tool_event` | `thinking.delta` | 深度思考 |
| `askMore` | `tool_event` | `ask_more` | 追问 |
| `isFinish=true` | `tool_done` | - | 完成（终态） |

### 4.5 响应字段详细映射

#### 4.5.1 text → text.delta

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.content` | `content` |
| - | `role` = `"assistant"` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"text","content":"您好"}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "您好",
            "role": "assistant",
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-text-xxx"
        }
    }
}
```

#### 4.5.2 planning → planning.delta

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.planning` | `content` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"planning","planning":"正在分析用户意图"}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "planning.delta",
        "properties": {
            "content": "正在分析用户意图",
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-plan-xxx"
        }
    }
}
```

#### 4.5.3 searching → searching

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.searching` | `keywords` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"searching","searching":["正在检索知识库","正在联网搜索"]}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "searching",
        "properties": {
            "keywords": ["正在检索知识库", "正在联网搜索"],
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-search-xxx"
        }
    }
}
```

#### 4.5.4 searchResult → search_result

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.searchResult` | `searchResults` |
| `data.searchResult[].index` | `searchResults[].index` |
| `data.searchResult[].title` | `searchResults[].title` |
| `data.searchResult[].source` | `searchResults[].source` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"searchResult","searchResult":[{"index":"1","title":"华为云介绍","source":"官网"}]}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "search_result",
        "properties": {
            "searchResults": [
                {"index": "1", "title": "华为云介绍", "source": "官网"}
            ],
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-search-xxx"
        }
    }
}
```

#### 4.5.5 reference → reference

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.references` | `references` |
| `data.references[].index` | `references[].index` |
| `data.references[].title` | `references[].title` |
| `data.references[].source` | `references[].source` |
| `data.references[].url` | `references[].url` |
| `data.references[].content` | `references[].content` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"reference","references":[{"index":"1","title":"API文档","source":"内部Wiki","url":"http://wiki.example.com","content":"相关接口定义"}]}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "reference",
        "properties": {
            "references": [
                {"index": "1", "title": "API文档", "source": "内部Wiki", "url": "http://wiki.example.com", "content": "相关接口定义"}
            ],
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-ref-xxx"
        }
    }
}
```

#### 4.5.6 think → thinking.delta

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.content` | `content` |
| - | `role` = `"assistant"` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"think","content":"首先需要考虑用户的核心需求..."}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "thinking.delta",
        "properties": {
            "content": "首先需要考虑用户的核心需求...",
            "role": "assistant",
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-think-xxx"
        }
    }
}
```

#### 4.5.7 askMore → ask_more

| 标准协议字段 | ai-gateway properties 字段 |
|--------------|---------------------------|
| `data.askMore` | `askMoreQuestions` |
| - | `messageId`（自动生成） |
| - | `partId`（自动生成） |

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"askMore","askMore":["如何创建项目？","支持哪些语言？"]}}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "<topicId>",
    "event": {
        "type": "ask_more",
        "properties": {
            "askMoreQuestions": ["如何创建项目？", "支持哪些语言？"],
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-askmore-xxx"
        }
    }
}
```

#### 4.5.8 isFinish=true → tool_done

**示例**：
```json
// 标准协议响应
{"code":"0","message":"","error":"","isFinish":true,"data":{"type":"text","content":"回答已完成"}}

// ai-gateway 事件（先发送 text.delta，再发送 tool_done）
{
    "type": "tool_done",
    "toolSessionId": "<topicId>",
    "usage": {}
}
```

---

## 5. UniKnow 协议 → ai-gateway 协议映射

### 5.1 协议类型

- **REST**（非流式同步）

### 5.2 请求体映射

| UniKnow 字段 | ai-gateway cloudRequest 字段 | 说明 |
|--------------|------------------------------|------|
| `input_text` | `content` | 用户输入内容 |
| `user_id` | `sendUserAccount` | 用户 ID |
| `set_meta_data.cookie` | `extParameters.businessExtParam.cookie` | 个人 cookie |
| `robot_uuid` | `extParameters.businessExtParam.robot_uuid` | UniKnow 机器人 ID |
| - | `type` | 默认 `"text"` |
| - | `clientLang` | 默认 `"zh"` |
| - | `topicId` | 由 ai-gateway 生成 |
| - | `extParameters.platformExtParam` | `{}` |

### 5.3 请求头映射

| UniKnow 请求头 | ai-gateway 请求头 | 说明 |
|----------------|------------------|------|
| `Authorization: SOAtoken - athena` | `X-Auth-Type: soa`, `X-App-Id` | SOA token |
| `origin-tenant-id: {appId}` | `X-App-Id` | 机器人所属 appId |

### 5.4 响应事件映射

**UniKnow 响应结构**：

#### 正常响应（有回答）：
```json
{
    "data": [
        {
            "taskInfo": {
                "slots": {
                    "result": {
                        "data": "回答内容",
                        "requestId": "req-12345"
                    }
                }
            }
        }
    ]
}
```

#### 无结果响应：
```json
{
    "data": [
        {
            "chatScriptContent": "抱歉，我还在学习中..."
        }
    ]
}
```

#### 异常响应（HTTP 非 200）：
```json
[
    {
        "status": "500",
        "title": "Internal Server Error",
        "detail": "服务未知异常"
    }
]
```

**映射表**：

| UniKnow 响应类型 | ai-gateway 事件类型 | 说明 |
|------------------|---------------------|------|
| `taskInfo.slots.result` | `tool_event` → `text.delta` | 有回答内容 |
| `chatScriptContent` | `tool_event` → `text.delta` | 无结果回复 |
| 任意响应 | `tool_done` | 完成（终态） |
| HTTP 非 200 | `tool_error` | 错误（终态） |

### 5.5 响应字段详细映射

#### 5.5.1 有回答内容

| UniKnow 字段 | ai-gateway 字段 |
|--------------|-----------------|
| `data[0].taskInfo.slots.result.data` | `event.properties.content` |
| `data[0].taskInfo.slots.result.requestId` | `toolSessionId` |
| - | `event.type` = `"text.delta"` |
| - | `event.properties.role` = `"assistant"` |

**示例**：
```json
// UniKnow 响应
{
    "data": [
        {
            "taskInfo": {
                "slots": {
                    "result": {
                        "data": "这是 UniKnow 的回答内容",
                        "requestId": "req-12345"
                    }
                }
            }
        }
    ]
}

// ai-gateway 事件 - 第一步（文本）
{
    "type": "tool_event",
    "toolSessionId": "req-12345",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是 UniKnow 的回答内容",
            "role": "assistant",
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-text-xxx"
        }
    }
}

// ai-gateway 事件 - 第二步（完成）
{
    "type": "tool_done",
    "toolSessionId": "req-12345",
    "usage": {}
}
```

#### 5.5.2 无结果响应

| UniKnow 字段 | ai-gateway 字段 |
|--------------|-----------------|
| `data[0].chatScriptContent` | `event.properties.content` |
| - | `toolSessionId`（空或生成） |
| - | `event.type` = `"text.delta"` |

**示例**：
```json
// UniKnow 响应
{
    "data": [
        {
            "chatScriptContent": "抱歉，我还在学习中，请换个问题试试呗~"
        }
    ]
}

// ai-gateway 事件
{
    "type": "tool_event",
    "toolSessionId": "",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "抱歉，我还在学习中，请换个问题试试呗~",
            "role": "assistant",
            "messageId": "cloud-msg-xxx",
            "partId": "cloud-part-text-xxx"
        }
    }
}
```

#### 5.5.3 异常响应

| UniKnow 字段 | ai-gateway 字段 |
|--------------|-----------------|
| `[0].detail` | `error` |
| - | `toolSessionId`（空） |

**示例**：
```json
// UniKnow 异常响应
[
    {
        "status": "500",
        "title": "Internal Server Error",
        "detail": "服务未知异常"
    }
]

// ai-gateway 事件
{
    "type": "tool_error",
    "toolSessionId": "",
    "error": "服务未知异常"
}
```

---

## 6. 关键说明

### 6.1 toolSessionId 生成规则

| 协议类型 | toolSessionId 来源 |
|----------|-------------------|
| 标准协议 | 优先使用 `messageId`，其次使用 `topicId` |
| UniKnow 协议 | 从响应的 `requestId` 获取 |
| 通用兜底 | ai-gateway 自动生成 UUID |

### 6.2 流式容错处理

- **标准协议流式**：即使对接方未发送 `isFinish=true`，当 SSE 连接正常断开时也会自动发送 `tool_done` 事件

### 6.3 非流式限制

- **标准协议非流式**：仅支持 `text` 类型，一次性返回完整响应
- **UniKnow 协议**：始终为非流式，仅支持文本响应

### 6.4 字段命名注意事项

| 协议字段 | 正确名称 | 常见错误 |
|----------|----------|----------|
| 搜索结果列表 | `searchResults` | `results` |
| 追问列表 | `askMoreQuestions` | `questions` |
| 选项数组 | `options` | `choices` |
| 工具调用 ID | `toolCallId` | `callId` |

---

## 7. ai-gateway 协议判断与认证机制

### 7.1 协议判断流程

ai-gateway 通过 **多层级映射** 来判断调用哪种协议，核心决策依据是 `CallbackConfig` 配置对象。

#### 7.1.1 判断流程总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        协议判断决策链                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  1. GatewayMessage.action  ──▶ 2. CallbackConfig.scope                │
│         │                             │                                │
│         ▼                             ▼                                │
│  chat / question_reply /              │                                │
│  permission_reply                     ▼                                │
│                              3. CallbackConfig.channelType            │
│                                       │                                │
│              ┌────────────────────────┼────────────────────────┐       │
│              ▼                        ▼                        ▼       │
│         "webhook"                "sse"                   "websocket"   │
│              │                        │                        │       │
│              ▼                        ▼                        ▼       │
│       WebHookExecutor         SseProtocolStrategy      WebSocketProtocolStrategy│
└─────────────────────────────────────────────────────────────────────────┘
```

#### 7.1.2 核心决策字段

| 决策层级 | 字段来源 | 字段名称 | 取值范围 | 说明 |
|----------|----------|----------|----------|------|
| 第一层 | `GatewayMessage` | `action` | `chat` / `question_reply` / `permission_reply` | 业务动作类型 |
| 第二层 | `CallbackConfig` | `scope` | `callback:weagent:chat` / `callback:weagent:question_reply` / `callback:weagent:permission_reply` | 回调订阅范围 |
| 第三层 | `CallbackConfig` | `channelType` | `webhook` / `sse` / `websocket` | 通道协议类型 |

#### 7.1.3 action → scope 映射表

```java
// CloudAgentService.ACTION_TO_SCOPE 硬编码映射
{
    "chat":             "callback:weagent:chat",
    "question_reply":   "callback:weagent:question_reply",
    "permission_reply": "callback:weagent:permission_reply"
}
```

#### 7.1.4 channelType 与 action 匹配规则

| action | 允许的 channelType | 说明 |
|--------|-------------------|------|
| `chat` | `sse`, `websocket` | 长连接流式协议 |
| `question_reply` | `webhook` | 同步短连接 |
| `permission_reply` | `webhook` | 同步短连接 |

**校验逻辑**：
- `question_reply` 和 `permission_reply` **必须**使用 `webhook`
- `chat` **必须**使用 `sse` 或 `websocket`
- 不匹配时返回 `tool_error`

#### 7.1.5 协议策略调度器（CloudProtocolClient）

```
┌─────────────────────────────────────────────────────────────────────┐
│              CloudProtocolClient 策略分发                           │
├─────────────────────────────────────────────────────────────────────┤
│  Spring 启动时自动扫描所有 CloudProtocolStrategy 实现               │
│                                                                     │
│  strategyMap = {                                                   │
│      "sse":         SseProtocolStrategy,                           │
│      "websocket":   WebSocketProtocolStrategy,                     │
│      "rest":        RestProtocolStrategy,  // 新增标准协议策略       │
│      "uniknow":     UniKnowProtocolStrategy  // 新增 UniKnow 策略   │
│  }                                                                 │
│                                                                     │
│  connect(protocol, context, ...)                                   │
│      │                                                             │
│      ▼                                                             │
│  strategyMap.get(protocol).connect(context, ...)                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 请求头（认证）判断流程

ai-gateway 通过 `authType` 字段判断使用哪种认证方式，并注入对应的请求头。

#### 7.2.1 认证判断流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    认证决策流程                                  │
├─────────────────────────────────────────────────────────────────┤
│  CallbackConfig.authType                                        │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────────┐       │
│  │           CloudAuthService.applyAuth()               │       │
│  └──────────────────────────────────────────────────────┘       │
│         │                                                       │
│         ▼                                                       │
│  strategyMap.get(authType).applyAuth(requestBuilder, appId)     │
│         │                                                       │
│    ┌────┴────┬────────┬────────┐                                │
│    ▼         ▼        ▼        ▼                                │
│  "none"   "soa"    "apig"   "custom"                            │
│    │         │        │        │                                │
│    ▼         ▼        ▼        ▼                                │
│  NoAuth    SoaAuth  ApigAuth  CustomAuth                        │
│  Strategy  Strategy Strategy  Strategy                           │
└─────────────────────────────────────────────────────────────────┘
```

#### 7.2.2 认证类型字段

| 字段来源 | 字段名称 | 取值范围 | 说明 |
|----------|----------|----------|------|
| `CallbackConfig` | `authType` | `none` / `soa` / `apig` / `custom` | 认证类型标识 |
| `CallbackConfig` | `appId` | 字符串 | 云端应用 ID（v1 由 hisAppId 映射，v2 为 null 时不发送） |

#### 7.2.3 认证策略映射表

| authType | 策略类 | 请求头注入 |
|----------|--------|-----------|
| `none` | `NoAuthStrategy` | 不注入任何认证头 |
| `soa` | `SoaAuthStrategy` | `X-Auth-Type: soa`, `X-App-Id: {appId}` |
| `apig` | `ApigAuthStrategy` | `X-Auth-Type: apig`, `X-App-Id: {appId}` |
| `custom` | `CustomAuthStrategy` | 自定义头（需扩展） |

#### 7.2.4 公共请求头

无论哪种认证类型，ai-gateway 都会注入以下公共头：

| 请求头 | 说明 | 出现条件 |
|--------|------|----------|
| `Content-Type: application/json` | 请求体类型 | 始终存在 |
| `X-Trace-Id: <uuid>` | 链路追踪 ID | `traceId` 非空时 |
| `X-Auth-Type: <type>` | 认证类型标识 | `authType` 非 `none` 时 |
| `X-App-Id: <appId>` | 应用 ID | `appId` 非空且 `authType` 非 `none` 时 |

#### 7.2.5 认证策略调度器（CloudAuthService）

```java
// CloudAuthService 构造时自动扫描所有 CloudAuthStrategy 实现
public CloudAuthService(List<CloudAuthStrategy> strategies) {
    this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(CloudAuthStrategy::getAuthType, Function.identity()));
}

// 运行时按 authType 分派
public void applyAuth(HttpRequest.Builder requestBuilder, String appId, String authType) {
    CloudAuthStrategy strategy = strategyMap.get(authType);
    if (strategy == null) {
        throw new IllegalArgumentException("Unknown cloud auth type: " + authType);
    }
    strategy.applyAuth(requestBuilder, appId);
}
```

### 7.3 新增协议实现指南

如果需要实现新的协议策略（如 `standard` 或 `uniknow`），需要遵循以下步骤：

#### 7.3.1 新增协议策略类

```java
// 示例：StandardProtocolStrategy
@Component
public class StandardProtocolStrategy implements CloudProtocolStrategy {
    
    @Override
    public String getProtocol() {
        return "standard";  // 协议标识
    }
    
    @Override
    public void connect(CloudConnectionContext context, 
                        CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, 
                        Consumer<Throwable> onError) {
        // 1. 读取 context.cloudRequest
        // 2. 转换为标准协议请求格式
        // 3. 发起 HTTP 请求（SSE 或 REST）
        // 4. 解析响应，转换为 GatewayMessage
        // 5. 调用 onEvent 回调
    }
}
```

#### 7.3.2 新增认证策略类（如需要）

```java
// 示例：CustomAuthStrategy
@Component
public class CustomAuthStrategy implements CloudAuthStrategy {
    
    @Override
    public String getAuthType() {
        return "custom";  // 认证类型标识
    }
    
    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder, String appId) {
        // 注入自定义认证头
        requestBuilder.header("X-Custom-Token", "...");
    }
}
```

#### 7.3.3 配置要求

新协议实现后，需要在 api-server 的回调订阅配置中设置：

| 配置项 | 值 |
|--------|-----|
| `channelType` | 新协议标识（如 `standard`） |
| `authType` | 对应的认证类型（如 `soa`） |
| `channelAddress` | 云端 endpoint URL |

---

## 8. 映射流程图

> **详细映射图**：完整字段映射关系请查看 [protocol-mapping.drawio](protocol-mapping.drawio)（使用 drawio/OpenDiagrams 编辑）

### 8.1 cloudRequest ↔ 标准协议 映射图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           请求方向 (cloudRequest → 标准协议)                   │
├───────────────────────┬─────────────────────┬───────────────────────────────┤
│  ai-gateway           │   转换               │   标准协议                      │
│  cloudRequest         │   StandardProtocol   │   请求body                     │
│                       │   Strategy           │                               │
│  {                   │   请求转换            │   {                           │
│    "type": "text",   │   ────────────────▶  │     "type": "text",           │
│    "content": "...",  │                      │     "content": "...",          │
│    "topicId": "str", │   ★ String→long     │     "topicId": 123,            │
│    "messageId": "str",│   (topicId,messageId)│     "messageId": 456,          │
│    "extParameters": { │                      │     "extParameters": {        │
│      "businessExtParam":│                     │       "isHwEmployee": true,    │
│        {...}          │                      │       "knowledgeId": [...]      │
│    }                  │                      │     }                          │
│  }                   │                      │   }                           │
└───────────────────────┴─────────────────────┴───────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                           响应方向 (标准协议 → ai-gateway)                    │
├───────────────────────┬─────────────────────┬───────────────────────────────┤
│  标准协议              │   转换               │   ai-gateway                  │
│  响应body              │   StandardProtocol   │   GatewayMessage              │
│                       │   Strategy           │                               │
│  {                   │   响应转换            │   {                           │
│    "code": "0",      │   ────────────────▶  │     "type": "tool_event",     │
│    "isFinish": false,│                      │     "toolSessionId": "...",    │
│    "data": {         │                      │     "event": {                 │
│      "type": "text", │                      │       "type": "text.delta",    │
│      "content": "..."│   字段映射            │       "properties": {          │
│    }                 │                      │         "content": "...",      │
│  }                   │                      │         "role": "assistant"   │
│                       │                      │       }                       │
│  ─────────────────────────────────────────▶  │   }                           │
│  "isFinish=true"      │                      │     OR                        │
│                       │                      │   {                           │
│                       │                      │     "type": "tool_done",       │
│                       │                      │     "toolSessionId": "..."      │
│                       │                      │   }                           │
└───────────────────────┴─────────────────────┴───────────────────────────────┘
```

### 8.2 事件类型映射速查表

| 标准协议 data.type | → | ai-gateway event.type | 说明 |
|---------------------|---|----------------------|------|
| `text` | → | `text.delta` | 文本增量，content 映射 |
| `planning` | → | `planning.delta` | 规划，planning 映射为 content |
| `searching` | → | `searching` | 搜索中，searching 映射为 keywords |
| `searchResult` | → | `search_result` | 搜索结果，searchResult 映射为 searchResults |
| `reference` | → | `reference` | 引用，references 映射 |
| `think` | → | `thinking.delta` | 深度思考，content 映射 |
| `askMore` | → | `ask_more` | 追问，askMore 映射为 askMoreQuestions |
| `isFinish=true` | → | `tool_done` | 完成（终态） |
| `code≠"0"` 或 `error≠""` | → | `tool_error` | 错误（终态） |

### 8.3 关键类型转换

| 转换方向 | 字段 | 转换规则 |
|----------|------|----------|
| cloudRequest → 标准协议 | `topicId` | `String` → `long` |
| cloudRequest → 标准协议 | `messageId` | `String` → `long` |
| 标准协议 → ai-gateway | `searching` → `keywords` | 数组重命名 |
| 标准协议 → ai-gateway | `searchResult` → `searchResults` | 数组重命名 |
| 标准协议 → ai-gateway | `askMore` → `askMoreQuestions` | 数组重命名 |
