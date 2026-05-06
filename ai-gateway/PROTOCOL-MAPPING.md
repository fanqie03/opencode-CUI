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

### 2.2 标准协议（Standard）

**协议类型**: 支持两种请求方式
- **流式**: SSE（Server-Sent Events）
- **非流式**: REST（同步请求，仅支持 text 类型）

**适用场景**: 通用标准协议，支持多种消息类型

**关键说明**: 
- 响应中不包含 `topicId` 和 `messageId`，`toolSessionId` 从入参中提取（优先使用 `messageId`，其次使用 `topicId`）
- **流式容错处理**：即使对接方未发送 `isFinish=true`，当 SSE 连接正常断开时也会自动发送 `tool_done` 事件
- **非流式限制**：仅支持 `text` 类型，一次性返回完整响应

**请求头**（支持多种认证方式）：

| 认证类型 | 请求头 | 说明 |
|----------|--------|------|
| SOA token | `Authorization: soa_token` | athena 表示为该 appId 的 token |
| SOA token | `x-hw-id: xxx` |  -- athena |
| SOA token | `x-appkey: xxx` | -- athena |
| IAM token | `Authorization: iam_token` | athena 表示为该 appId 的 token |
| 集成账号 token | `Authorization: 集成账号token` | athena 表示为该 appId 的 token |
| 自定义 token | `自定义key: 自定义value` | 自定义认证方式 |
| cookie（附加） | `cookie: 个人cookie` | 附加 cookie |

**输入格式映射**（aiGateway CloudRequest → 标准协议）：

| aiGateway CloudRequest 字段 | 标准协议字段 | 说明 | 默认值 |
|-----------------------------|--------------|------|--------|
| `content` | `content` | 消息内容 | - |
| `contentType` | `type` | 内容类型 | `"text"` |
| `assistantAccount` | `assistantAccount` | 助理账号 | - |
| `sendUserAccount` | `sendUserAccount` | 发送用户账号 | - |
| `imGroupId` | `imGroupId` | IM 群组 ID | - |
| `clientLang` | `clientLang` | 客户端语言 | `"zh"` |
| `clientType` | `clientType` | 客户端类型 | - |
| `topicId` | `topicId` | 话题 ID | - |
| `messageId` | `messageId` | 消息 ID | - |
| `extParameters` | `extParameters` | 扩展参数 | `{}` |

**aiGateway 输入格式**（`GatewayMessage.payload.cloudRequest`）：
```json
{
    "content": "发送内容",
    "contentType": "text",
    "assistantAccount": "",
    "sendUserAccount": "发送人账号",
    "imGroupId": "",
    "clientLang": "zh",
    "clientType": "asst-pc",
    "topicId": "123",
    "messageId": "456",
    "extParameters": {
        "isHwEmployee": true,
        "knowledgeId": ["1122aa"]
    }
}
```

**转换后的标准协议输入格式**：
```json
{
    "type": "text",
    "content": "发送内容",
    "assistantAccount": "",
    "sendUserAccount": "发送人账号",
    "imGroupId": "",
    "clientLang": "zh",
    "clientType": "asst-pc",
    "topicId": "123",
    "messageId": "456",
    "extParameters": {
        "isHwEmployee": true,
        "knowledgeId": ["1122aa"]
    }
}
```

**事件类型映射**:

| 标准协议数据类型 | Gateway 消息类型 | 说明 |
|------------------|------------------|------|
| `text` | `tool_event` (text.delta) | 文本内容 |
| `planning` | `tool_event` (thinking) | 规划中 |
| `searching` | `tool_event` (searching) | 搜索中 |
| `searchResult` | `tool_event` (search_result) | 搜索结果 |
| `reference` | `tool_event` (reference) | 引用 |
| `think` | `tool_event` (thinking) | 深度思考 |
| `askMore` | `tool_event` (ask_more) | 追问 |
| `isFinish=true` | `tool_done` | 完成 |

**输入格式**:
```json
{
    "type": "text",
    "content": "发送内容",
    "sendUserAccount": "发送人账号",
    "imGroupId": "",
    "clientLang": "zh",
    "clientType": "asst-pc",
    "topicId": 123,
    "messageId": 123,
    "extParameters": {
        "isHwEmployee": true,
        "actionParam": "",
        "knowledgeId": ["1122aa"]
    }
}
```

**原始响应格式**:
```json
{
    "code": "0",
    "message": "提示信息",
    "error": "异常信息",
    "isFinish": false,
    "data": {
        "type": "text",
        "content": "响应文本内容",
        "planning": "",
        "searching": [],
        "searchResult": [],
        "references": [],
        "askMore": []
    }
}
```

**输出转换示例**:

#### text（文本内容）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"text","content":"您好，请问有什么可以帮助您？"}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "您好，请问有什么可以帮助您？"
        }
    }
}
```

#### planning（规划中）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"planning","planning":"正在分析用户意图"}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "thinking",
        "properties": {
            "content": "正在分析用户意图"
        }
    }
}
```

#### searching（搜索中）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"searching","searching":["正在检索知识库","正在联网搜索"]}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "searching",
        "properties": {
            "type": "searching",
            "searching": ["正在检索知识库", "正在联网搜索"]
        }
    }
}
```

#### searchResult（搜索结果）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"searchResult","searchResult":[{"index":"1","title":"华为云介绍","source":"官网"},{"index":"2","title":"灵码功能说明","source":"帮助文档"}]}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "search_result",
        "properties": {
            "type": "searchResult",
            "searchResult": [
                {"index": "1", "title": "华为云介绍", "source": "官网"},
                {"index": "2", "title": "灵码功能说明", "source": "帮助文档"}
            ]
        }
    }
}
```

#### reference（引用）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"reference","references":[{"index":"1","title":"API文档","source":"内部Wiki","url":"http://wiki.example.com/api","content":"相关接口定义..."}]}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "reference",
        "properties": {
            "type": "reference",
            "references": [
                {"index": "1", "title": "API文档", "source": "内部Wiki", "url": "http://wiki.example.com/api", "content": "相关接口定义..."}
            ]
        }
    }
}
```

#### think（深度思考）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"think","content":"首先需要考虑用户的核心需求..."}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "thinking",
        "properties": {
            "content": "首先需要考虑用户的核心需求..."
        }
    }
}
```

#### askMore（追问）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":false,"data":{"type":"askMore","askMore":["如何创建项目？","支持哪些语言？"]}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "ask_more",
        "properties": {
            "type": "askMore",
            "askMore": ["如何创建项目？", "支持哪些语言？"]
        }
    }
}
```

#### isFinish=true（完成）
**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":true,"data":{"type":"text","content":"回答已完成"}}
```
**转换结果**:
```json
{
    "type": "tool_done",
    "toolSessionId": "123"
}
```

### 非流式协议输出转换示例

**说明**: 非流式协议采用同步 REST 调用，一次性返回完整响应，仅支持 `text` 类型。

**原始响应**:
```json
{"code":"0","message":"","error":"","isFinish":true,"data":{"type":"text","content":"您好，我是智能助手。"}}
```

**转换结果 - 第一步（文本事件）**:
```json
{
    "type": "tool_event",
    "toolSessionId": "123",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "您好，我是智能助手。"
        }
    }
}
```

**转换结果 - 第二步（完成事件）**:
```json
{
    "type": "tool_done",
    "toolSessionId": "123"
}
```

---

### 2.3 UniKnow 协议

**协议类型**: REST（非流式同步）

**适用场景**: UniKnow 知识问答系统对接

**关键说明**: 
- UniKnow 采用同步 REST 调用方式，非流式响应
- `toolSessionId` 从响应中的 `requestId` 获取
- 响应处理流程：先发送 `tool_event` 携带文本内容，再发送 `tool_done` 标记完成

**请求头**:

| 请求头 | 说明 |
|--------|------|
| `Authorization: SOAtoken - athena` | athena 表示为该 appId 的 SOA token |
| `origin-tenant-id: {appId}` | 该机器人所属的 appId |

**输入格式**（aiGateway CloudRequest → UniKnow）：

| aiGateway CloudRequest 字段 | UniKnow 字段 | 说明 | 默认值 |
|-----------------------------|--------------|------|--------|
| `content` | `input_text` | 用户输入内容 | - |
| `sendUserAccount` | `user_id` | 用户 ID | - |
| `extParameters.cookie` | `set_meta_data.cookie` | 个人 cookie | - |
| `extParameters.robot_uuid` | `robot_uuid` | UniKnow 平台机器人 ID | - |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "sendUserAccount": "user-001",
    "topicId": "topic-001",
    "extParameters": {
        "robot_uuid": "robot-123",
        "cookie": "personal-cookie"
    }
}
```

**转换后的 UniKnow 输入格式**:
```json
{
    "input_text": "用户输入内容",
    "user_id": "user-001",
    "set_meta_data": {
        "cookie": "personal-cookie"
    },
    "robot_uuid": "robot-123"
}
```

**输出转换示例**:

#### 正常响应（有回答内容）
**原始响应**:
```json
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
```

**转换结果 - 第一步（文本事件）**:
```json
{
    "type": "tool_event",
    "toolSessionId": "req-12345",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是 UniKnow 的回答内容"
        }
    }
}
```

**转换结果 - 第二步（完成事件）**:
```json
{
    "type": "tool_done",
    "toolSessionId": "req-12345"
}
```

#### 无结果响应
**原始响应**:
```json
{
    "data": [
        {
            "chatScriptContent": "抱歉，我还在学习中，请换个问题试试呗~"
        }
    ]
}
```

**转换结果 - 第一步（文本事件）**:
```json
{
    "type": "tool_event",
    "toolSessionId": "",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "抱歉，我还在学习中，请换个问题试试呗~"
        }
    }
}
```

**转换结果 - 第二步（完成事件）**:
```json
{
    "type": "tool_done",
    "toolSessionId": ""
}
```

#### 异常响应（HTTP 非 200 状态码）
**原始响应**:
```json
[
    {
        "status": "500",
        "title": "Internal Server Error",
        "detail": "服务未知异常"
    }
]
```

**转换结果**:
```json
{
    "type": "tool_error",
    "toolSessionId": "",
    "error": "服务未知异常"
}
```

---
