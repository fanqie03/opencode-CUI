# AI Gateway 协议映射关系文档

## 1. 协议编码映射

### 1.1 上游协议编码 → 内部协议名称

| 编码 | 协议名称 | 策略类 | 文件路径 |
|------|----------|--------|----------|
| `1` | rest | `RestProtocolStrategy` | 现有 |
| `2` | sse | `SseProtocolStrategy` | 现有 |
| `3` | websocket | `WebSocketProtocolStrategy` | 现有 |
| `4` | dify | `DifyProtocolStrategy` | 新增 |
| `5` | agentmaker | `AgentMakerProtocolStrategy` | 新增 |
| `6` | uniknow | `UniKnowProtocolStrategy` | 新增 |
| `7` | athena | `AthenaProtocolStrategy` | 新增 |
| `8` | standard | `StandardProtocolStrategy` | 新增 |
| `9` | assistantmaker | `AssistantMakerProtocolStrategy` | 新增 |

### 1.2 认证类型编码映射

| 编码 | 认证类型 | 说明 |
|------|----------|------|
| `1` | soa | SOA Token 认证 |
| `2` | apig | APIG Token 认证 |
| 默认 | 原值 | 自定义认证类型 |

---

## 2. 协议策略实现概览

### 2.1 Dify 协议（灵雀/白泽）

**协议类型**: SSE（Server-Sent Events）

**支持的模式**:
- **chatflow**: 对话流模式
- **agent**: 智能体模式  
- **workflow**: 工作流模式

**关键说明**: 
- `toolSessionId` 优先从 `conversation_id` 获取，其次使用 `message_id` 或 `task_id`
- `ping` 事件用于心跳保持，不转发

**请求地址**:

| 平台 | 模式 | 请求地址 |
|------|------|----------|
| 灵雀 | chatflow/agent/workflow | `/api/v1/chat-messages` |
| 白泽 | chatflow/agent | `/v1/chat-messages` |
| 白泽 | workflow | `/v1/workflows/run` |

**请求头**:

| 平台 | 请求头 | 说明 |
|------|--------|------|
| 灵雀 | `Authorization: SOA token - S008026` | S008026 表示为该 appId 的 SOA token |
| 灵雀 | `x-app-id: {robot_id}` | 灵雀(dify)平台下的机器人 id |
| 白泽 | `Authorization: Bearer {api_key}` | 每个机器人的固定秘钥 |

**输入格式**（aiGateway CloudRequest → Dify）：

| aiGateway CloudRequest 字段 | Dify 字段 | 说明 | 默认值 |
|-----------------------------|-----------|------|--------|
| `content` | `query` | 用户输入内容 | - |
| `topicId` | `conversation_id` | 会话 ID | - |
| `sendUserAccount` | `user` | 用户 ID | - |
| - | `response_mode` | 响应模式 | `"streaming"` |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "topicId": "conv-001",
    "sendUserAccount": "user-001",
    "extParameters": {}
}
```

**转换后的 Dify 输入格式**:
```json
{
    "query": "用户输入内容",
    "response_mode": "streaming",
    "conversation_id": "conv-001",
    "user": "user-001"
}
```

**事件类型映射**:

| 模式 | Dify 事件类型 | Gateway 消息类型 | 说明 |
|------|---------------|------------------|------|
| chatflow | `message` | `tool_event` (text.delta) | 文本内容块，需提取 `think` 标签后内容 |
| agent | `agent_message` | `tool_event` (text.delta) | Agent 模式文本，需提取 `think` 标签后内容 |
| agent | `agent_thought` | `tool_event` (thinking) | Agent 思考过程 |
| workflow | `text_chunk` | `tool_event` (text.delta) | Workflow 文本块，需提取 `think` 标签后内容 |
| 所有 | `message_end` | `tool_done` | 消息结束 |
| workflow | `workflow_finished` | `tool_done` | 工作流结束 |
| workflow | `workflow_started` | 忽略 | workflow 开始执行 |
| workflow | `node_started` | 忽略 | node 开始执行 |
| workflow | `node_finished` | 忽略 | node 执行结束 |
| workflow | `tts_message` | 忽略 | TTS 音频流事件 |
| workflow | `tts_message_end` | 忽略 | TTS 音频流结束事件 |
| 所有 | `message_replace` | `tool_event` (text.delta) | 消息内容替换事件 |
| 所有 | `message_file` | 忽略 | 文件事件 |
| 所有 | `error` | `tool_error` | 错误 |
| 所有 | `ping` | 忽略 | 心跳保持 |

**特殊处理**：
- `message` / `agent_message` / `text_chunk` 中的 `answer` / `text` 字段可能包含 `<think>` 标签
- `<think>` 标签内的内容需要提取出来，转换为 `thinking` 事件发送给前端展示
- `</think>` 标签后的内容作为 `text.delta` 事件发送

**输出转换示例**:

#### message / agent_message（文本内容，含 `<think>` 标签处理）
**原始响应**:
```json
{"event":"agent_message","answer":"<think>好的，让我来思考用户的需求。</think>您好，请问有什么可以帮助您？","conversation_id":"conv-001","message_id":"msg-001"}
```
**转换结果**（生成两个事件）:

**第一个事件 - thinking**:
```json
{
    "type": "tool_event",
    "toolSessionId": "conv-001",
    "event": {
        "type": "thinking",
        "properties": {
            "content": "好的，让我来思考用户的需求。"
        }
    }
}
```

**第二个事件 - text.delta**:
```json
{
    "type": "tool_event",
    "toolSessionId": "conv-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "您好，请问有什么可以帮助您？"
        }
    }
}
```

#### text_chunk（Workflow 文本块，含 `<think>` 标签处理）
**原始响应**:
```json
{"event":"text_chunk","data":{"text":"<think>正在分析用户问题</think>工作流执行结果"},"task_id":"task-001"}
```
**转换结果**（生成两个事件）:

**第一个事件 - thinking**:
```json
{
    "type": "tool_event",
    "toolSessionId": "task-001",
    "event": {
        "type": "thinking",
        "properties": {
            "content": "正在分析用户问题"
        }
    }
}
```

**第二个事件 - text.delta**:
```json
{
    "type": "tool_event",
    "toolSessionId": "task-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "工作流执行结果"
        }
    }
}
```

#### agent_thought（思考过程）
**原始响应**:
```json
{"event":"agent_thought","conversation_id":"conv-001","message_id":"msg-001","created_at":1775993023,"task_id":"task-001","id":"thought-001","position":1,"thought":"我需要分析用户的问题","observation":"用户想要了解华为云","tool":"","tool_labels":{},"tool_input":"","message_files":[]}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "conv-001",
    "event": {
        "type": "thinking",
        "properties": {
            "thought": "我需要分析用户的问题",
            "observation": "用户想要了解华为云"
        }
    }
}
```

---

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

### 2.3 AgentMaker 协议

**协议类型**: SSE（Server-Sent Events）

**适用场景**: AgentMaker 云端服务对接

**关键说明**: 
- AgentMaker 使用 JSON API 风格响应格式
- `toolSessionId` 优先从 `sessionId` 获取，其次使用 `requestId`

**输入格式**（aiGateway CloudRequest → AgentMaker）：

| aiGateway CloudRequest 字段 | AgentMaker 字段 | 说明 | 默认值 |
|-----------------------------|-----------------|------|--------|
| `content` | `userInput` | 用户输入内容 | - |
| `topicId` | `sessionId` | 会话 ID（无值新会话，有值当前会话） | - |
| `sendUserAccount` | `w3Account` | 用户账号 | - |
| `extParameters.agentUuid` | `agentUuid` | AgentMaker 平台下机器人的 ID | - |
| - | `needSave` | 是否保存会话 | `true` |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "topicId": "session-001",
    "sendUserAccount": "user-001",
    "extParameters": {
        "agentUuid": "agent-123"
    }
}
```

**转换后的 AgentMaker 输入格式**:
```json
{
    "agentUuid": "agent-123",
    "userInput": "用户输入内容",
    "sessionId": "session-001",
    "w3Account": "user-001",
    "needSave": true
}
```

**入参 Header**:
```
Authorization: IAM token - S008026  # S008026 表示为该 appId 的 iam token
```

**事件类型映射**:

| AgentMaker 状态 | Gateway 消息类型 | 说明 |
|-----------------|------------------|------|
| `PROCESSING` | 忽略 | 接口收到请求后会立即返回 |
| `PLANNING` | 忽略 | 模型返回了规划的结果 |
| `TOOL_EXECUTE` | 忽略 | 模型准备调用技能 |
| `TOOL_RESULT` | `tool_event` (tool_exec) | 工具执行结果，返回包含技能的完整入参和出参 |
| `TOOL_NOT_FOUND` | 忽略 | 模型调用技能失败，技能不存在 |
| `SUMMARY` | `tool_event` (text.delta) | 总结内容（思考内容放在 `<think></think>` 标签内，需提取后传递） |
| `ANSWER` | `tool_event` (text.delta) | 回答内容 |
| `ASK_USER` | `tool_event` (ask_more) | agent对用户发起了追问 |
| `USER_CONFIRM` | `tool_event` (text.delta) | 用户确认（we卡，需要转为im的卡片消息） |
| `END` | 忽略 | 问答结束 |
| `ERROR` | `tool_error` | agent运行异常 |
| `HITL` | 忽略 | FlowChain消息回复事件 |
| `CUSTOM_EVENT` | 忽略 | 知识agent默认返回的事件 |
| `ASYNC` | 忽略 | FlowChain异步事件 |
| `RETRIVE` | 忽略 | 检索refer_list内容 |

**输出转换示例**（忽略的状态类型：PROCESSING、PLANNING、TOOL_EXECUTE、TOOL_NOT_FOUND、END、HITL、CUSTOM_EVENT、ASYNC、RETRIVE）：

#### TOOL_RESULT（工具执行结果）
**原始响应**:
```json
{"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{"agentStatus":"TOOL_RESULT","content":"查询完成","toolResult":{"toolName":"search","parameters":{"query":"华为云"},"result":"搜索结果内容"}}}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "session-001",
    "event": {
        "type": "tool_exec",
        "properties": {
            "content": "查询完成",
            "toolResult": {
                "toolName": "search",
                "parameters": {"query": "华为云"},
                "result": "搜索结果内容"
            }
        }
    }
}
```

#### SUMMARY（总结，含思考内容）
**原始响应**:
```json
{"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{"agentStatus":"SUMMARY","content":"<think>我需要总结回答</think>这是总结内容"}}}
```
**转换结果**（提取 `<think>` 标签后内容）:
```json
{
    "type": "tool_event",
    "toolSessionId": "session-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是总结内容"
        }
    }
}
```

#### ANSWER（回答内容）
**原始响应**:
```json
{"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{"agentStatus":"ANSWER","content":"这是回答内容"}}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "session-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是回答内容"
        }
    }
}
```

#### ASK_USER（追问用户）
**原始响应**:
```json
{"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{"agentStatus":"ASK_USER","content":"你好，我是个智能助手，请问有什么可以帮助您？","requestId":"112233","sessionId":"778899"}}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "778899",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "你好，我是个智能助手，请问有什么可以帮助您？"
        }
    }
}
```

#### USER_CONFIRM（用户确认）
**原始响应**:
```json
{"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{"agentStatus":"USER_CONFIRM","content":"确认发送吗？"}}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "session-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "确认发送吗？"
        }
    }
}
```


#### ERROR（错误）
**原始响应**:
```json
[{"status":"500","title":"Internal Server Error","detail":"服务未知异常"}]
```
**转换结果**:
```json
{
    "type": "tool_error",
    "toolSessionId": "session-001",
    "error": "服务未知异常"
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

### 2.4 Athena 协议

**协议类型**: 两步协议（HTTP + SSE）

**适用场景**: Athena 智能对话系统对接

**请求流程**:
1. **首次对话**：POST `/chat` → 获取 SSE ID → GET `/sse?id={sse_id}`
2. **二次对话**（切换模型）：POST `/rechat` → 获取 SSE ID → GET `/sse?id={sse_id}`

**请求地址**:

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat` | POST | 首次 HTTP 请求 |
| `/rechat` | POST | 二次 HTTP 请求（仅切换模型时调用） |
| `/sse?id={sse_id}` | GET | SSE 订阅请求，id 放 URL 中 |

**请求头**:

| 请求头 | 说明 |
|--------|------|
| `Authorization: {集成账号token}` | 集成账号认证 |
| `cookie: {个人cookie}` | 个人 cookie |
| `x-tenant-id: {租户id}` | 租户 ID |
| `x-welink-id: {个人账号}` | 个人账号 |

**输入格式**（aiGateway CloudRequest → Athena HTTP）：

| aiGateway CloudRequest 字段 | Athena 字段 | 说明 | 默认值 |
|-----------------------------|-------------|------|--------|
| `extParameters.botId` | `botId` | 机器人 ID | - |
| `extParameters.skillType` | `skillType` | 技能类型 | `"skill"` |
| `extParameters.skillId` | `skillId` | 技能 ID | - |
| `contentType` | `msgType` | 消息类型 | `"text"` |
| `content` | `msgBody` | 消息体 | - |
| `clientType` | `clientType` | 设备类型 | - |
| `clientLang` | `clientLang` | 客户端语言 | `"zh"` |
| `extParameters.version` | `version` | 客户端版本 | `"20260414"` |
| `imGroupId` | `imGroupId` | IM 群组 ID | - |
| `topicId` | `topicId` | 会话主题 ID | - |
| `messageId` | `userMessageId` | 用户消息 ID（用于二次对话保持连续性） | - |
| - | `channel` | 通道类型 | `"sse"` |
| - | `requireMsgType` | 消息类型 | `"ATHENA-STREAM-CARD"` |
| `extParameters.extraParameters` | `extraParameters` | 额外参数 | `{}` |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "sendUserAccount": "user-001",
    "clientType": "asst-pc",
    "clientLang": "zh",
    "topicId": "topic-001",
    "messageId": "msg-001",
    "extParameters": {
        "botId": "robot-123",
        "skillType": "skill",
        "skillId": "skill-456",
        "version": "20260414",
        "extraParameters": {
            "pluginSetting": {
                "switchModel": "QWEN-V3-8B"
            }
        }
    }
}
```

**HTTP 响应**（chat/rechat 返回相同格式）:
```json
{
    "data": "sse_id 用于后续sse请求",
    "code": "200",
    "message": "ok"
}
```

**SSE 事件类型映射**:

| Athena eventType | Gateway 消息类型 | 说明 |
|------------------|------------------|------|
| `route` | 忽略 | 路由事件，传递技能配置信息 |
| `processStep` | `tool_event` (thinking) | 处理步骤，根据 code 判断类型 |
| `message` | `tool_event` (text.delta) | 文本消息内容 |
| `question` | `tool_event` (ask_more) | 追问事件（textList 多段文本列表） |
| `planning` | `tool_event` (thinking) | 计划事件 |
| `searching` | `tool_event` (searching) | 搜索中 |
| `searchResult` | `tool_event` (search_result) | 搜索结果 |
| `reference` | `tool_event` (reference) | 引用 |
| `askMore` | `tool_event` (ask_more) | 猜你想问 |
| `think` | `tool_event` (thinking) | 思考阶段 |
| `error` | `tool_error` | 错误 |
| `finish` | `tool_done` | 完成 |
| `ping` | 忽略 | 心跳事件 |
| `urlAttachment` | 忽略 | 附件事件 |

**processStep.code 映射**:

| processStep.code | Gateway 事件类型 | 说明 |
|------------------|------------------|------|
| `PROCESSING` | `tool_event` (thinking) | 处理中 |
| `download` | `tool_event` (thinking) | 下载中 |
| `analyze` | `tool_event` (thinking) | 分析中 |
| `think` | `tool_event` (thinking) | 思考中 |

**SSE 输出转换示例**:

#### message（文本消息）
**原始响应**:
```json
{"code": "200","data": {"messageId": "1122","robotId": "1122","sendAt": 1122,"costTime": 1122,"w3Account": "1122","messageBody": {"text": "你好，我是Qwen模型"},"eventType": "message"}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "你好，我是Qwen模型"
        }
    }
}
```

#### processStep（处理步骤）
**原始响应**:
```json
{"code": "200","data": {"messageId": "1122","robotId": "1122","sendAt": 1122,"costTime": 1122,"w3Account": "1122","processStep": {"type": "TEXT","code": "think","message": "思考中...用户说的是..."},"eventType": "processStep"}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "thinking",
        "properties": {
            "content": "思考中...用户说的是..."
        }
    }
}
```

#### question（追问）
**原始响应**:
```json
{"code": "200","data": {"messageId": "1122","robotId": "1122","sendAt": 1122,"costTime": 1122,"w3Account": "1122","messageBody": {"textList": ["今天天气怎么样", "我需要一个天气报告"]},"eventType": "question"}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "ask_more",
        "properties": {
            "questions": ["今天天气怎么样", "我需要一个天气报告"]
        }
    }
}
```

#### error（错误）
**原始响应**:
```json
{"code": "500","message": "服务器内部错误", "messageEn": "Internal Server Error", "eventType": "error"}
```
**转换结果**:
```json
{
    "type": "tool_error",
    "toolSessionId": "",
    "error": "服务器内部错误"
}
```

#### finish（完成）
**原始响应**:
```
event:finish
data:FINISH
```
**转换结果**:
```json
{
    "type": "tool_done",
    "toolSessionId": ""
}
```

---

### 2.5 AssistantMaker（助手maker）协议

**协议类型**: 两步协议（HTTP + SSE/REST）

**适用场景**: 助手maker技能平台对接

**适用场景**: 助手maker可能返回html

**请求流程**:
1. **未指定技能**：调用意图识别接口 → 查询技能详情 → 调用技能接口（流式或非流式）
2. **指定技能**：查询技能详情 → 调用技能接口（流式或非流式）

**请求地址**:

| 接口 | 方法 | 说明 |
|------|------|------|
| `/v1/projects/{project_id}/skill/skillMultiIntentDetection` | POST | 意图识别（未指定技能时调用） |
| `/v1/projects/{project_id}/skills/{skill_id}` | GET | 查询技能详情（判断流式/非流式） |
| `/v1/projects/{project_id}/skills/{skill_id}/invoke` | POST | 技能调用（流式或非流式） |

**请求头**:

| 请求头 | 说明 |
|--------|------|
| `cookie: {个人cookie}` | 个人 cookie |

**输入格式**（aiGateway CloudRequest → AssistantMaker）：

| aiGateway CloudRequest 字段 | AssistantMaker 字段 | 说明 | 默认值 |
|-----------------------------|---------------------|------|--------|
| `content` | `question` | 用户问题 | - |
| `extParameters.skillId` | `skillId` | 技能 ID（未指定时传空） | "" |
| `extParameters.assistantId` | `assistantId` | 助手 ID | - |
| `topicId` | `sessionId` | 会话 ID（空则生成新会话） | "" |
| `extParameters.projectId` | `project_id` | 项目 ID（URL 路径参数） | - |
| - | `channel` | 渠道 | "Web-sidebar" |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "topicId": "session-001",
    "extParameters": {
        "projectId": "project-123",
        "skillId": "skill-456",
        "assistantId": "assistant-789"
    }
}
```

**转换后的 AssistantMaker 输入格式**（技能调用接口）:
```json
{
    "question": "用户输入内容",
    "skillId": "skill-456",
    "assistantId": "assistant-789",
    "sessionId": "session-001",
    "rewriteContext": [],
    "channel": "Web-sidebar"
}
```

**技能类型判断**：通过查询技能详情接口返回的 `externalParams` 中是否包含 `"stream":true` 字段来判断：
- `"stream":true` → 流式技能，使用 SSE 请求
- 否则 → 非流式技能，使用 REST 请求

**事件类型映射**:

| AssistantMaker status | Gateway 消息类型 | 说明 |
|----------------------|------------------|------|
| `finish` | `tool_event` (text.delta) | 文本内容 |
| `finish` + 最后一条 | `tool_done` | 完成 |
| 其他 | 忽略 | 中间状态 |

**SSE 输出转换示例**:

#### 流式响应（文本内容）
**原始响应**:
```jsonc
data:{"errors":null,"meta":null,"data":{"id":"1","type":"SkillInvokeVO","attributes":{"status":"finish","content":"您好，请问有什么可以帮助您？","sessionId":"1122","chatRecordId":"技能名称","skillType":"flowchain","outputStyle":"Markdown","template":null,"conditionName":"默认输出","renderStatus":1}}}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "您好，请问有什么可以帮助您？"
        }
    }
}
```

#### 流式响应（完成）
**原始响应**（最后一条数据）:
```jsonc
data:{"errors":null,"meta":null,"data":{"id":"1","type":"SkillInvokeVO","attributes":{"status":"finish","content":"回答完成","sessionId":"1122","chatRecordId":"技能名称","skillType":"flowchain","outputStyle":"Markdown","template":null,"conditionName":"默认输出","renderStatus":1}}}
```
**转换结果**（两个事件）:

**第一步 - 文本事件**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "回答完成"
        }
    }
}
```

**第二步 - 完成事件**:
```json
{
    "type": "tool_done",
    "toolSessionId": "1122"
}
```

**非流式输出转换示例**:

#### 非流式响应
**原始响应**:
```json
{
    "errors": null,
    "meta": null,
    "data": {
        "id": "1",
        "type": "SkillInvokeVO",
        "attributes": {
            "status": "finish",
            "content": "这是非流式回答内容",
            "sessionId": "1122",
            "chatRecordId": "技能名称",
            "skillType": "flowchain",
            "outputStyle": "Markdown",
            "template": null,
            "conditionName": "默认输出",
            "renderStatus": 1
        }
    }
}
```

**转换结果 - 第一步（文本事件）**:
```json
{
    "type": "tool_event",
    "toolSessionId": "1122",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是非流式回答内容"
        }
    }
}
```

**转换结果 - 第二步（完成事件）**:
```json
{
    "type": "tool_done",
    "toolSessionId": "1122"
}
```

---

## 3. Gateway 内部消息类型

### 3.1 消息类型枚举

| 消息类型 | 说明 | 触发场景 |
|----------|------|----------|
| `tool_event` | 工具事件 | 流式文本、思考过程、搜索状态等 |
| `tool_done` | 工具完成 | 对话结束、任务完成 |
| `tool_error` | 工具错误 | 发生错误 |

### 3.2 tool_event 事件类型

| 事件子类型 | 说明 | 来源协议 |
|------------|------|----------|
| `text.delta` | 文本增量 | Dify, AgentMaker, UniKnow, Athena |
| `thinking` | 思考过程 | Dify(agent_thought), AgentMaker(PROCESSING), Athena(planning/think) |
| `searching` | 搜索中 | Athena |
| `search_result` | 搜索结果 | Athena |
| `reference` | 引用 | Athena |
| `ask_more` | 追问 | Athena |
| `tool_exec` | 工具执行 | AgentMaker(TOOL_EXEC) |

---

## 4. 认证方式映射

### 4.1 支持的认证类型

| 认证类型 | Header 格式 | 适用协议 |
|----------|-------------|----------|
| `soa` | `Authorization: SOA token` | Dify(灵雀), UniKnow, Athena |
| `apig` | `Authorization: apig token` | 通用 |
| `bearer` | `Authorization: Bearer {api_key}` | Dify(白泽) |

---

## 5. 协议策略架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                      CloudRouteService                          │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              协议编码映射 (mapProtocol)                   │  │
│  │  1→rest  2→sse  3→websocket  4→dify  5→agentmaker       │  │
│  │  6→uniknow  7→athena  8→standard                         │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              CloudProtocolStrategy (接口)                  │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │  getProtocol() → 返回协议名称                        │  │  │
│  │  │  connect(context, lifecycle, onEvent, onError)      │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              ▲                                  │
│         ┌────────────────────┼────────────────────┐             │
│         │                    │                    │             │
│         ▼                    ▼                    ▼             │
│  ┌─────────────┐    ┌───────────────┐    ┌───────────────┐    │
│  │ DifyStrategy│    │AgentMakerStrategy│   │UniKnowStrategy│   │
│  │  (SSE)      │    │    (SSE)       │    │   (REST)      │    │
│  └─────────────┘    └───────────────┘    └───────────────┘    │
│         │                    │                    │             │
│         ▼                    ▼                    ▼             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    GatewayMessage                         │  │
│  │  type: tool_event / tool_done / tool_error               │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. 输入参数映射

### 6.1 上游请求 → CloudRequest 映射

| 上游字段 | CloudRequest 字段 | 说明 |
|----------|-------------------|------|
| `type` | `cloudRequest.type` | 消息类型(text/image) |
| `content` | `cloudRequest.content` | 用户输入内容 |
| `sendUserAccount` | `cloudRequest.userId` | 发送人账号 |
| `imGroupId` | `cloudRequest.groupId` | 群组ID |
| `clientLang` | `cloudRequest.lang` | 客户端语言 |
| `clientType` | `cloudRequest.clientType` | 客户端类型 |
| `topicId` | `cloudRequest.topicId` | 会话主题ID |
| `messageId` | `cloudRequest.messageId` | 消息ID |
| `extParameters` | `cloudRequest.extParams` | 扩展参数 |

---

## 7. 错误处理映射

### 7.1 HTTP 状态码处理

| HTTP 状态码 | 处理方式 | Gateway 消息类型 |
|-------------|----------|------------------|
| 200 | 正常处理 | 根据协议解析 |
| 401 | 认证失败 | `tool_error` |
| 403 | 权限不足 | `tool_error` |
| 404 | 端点不存在 | `tool_error` |
| 5xx | 服务端错误 | `tool_error` |
| 其他 | 未知错误 | `tool_error` |

### 7.2 协议特定错误

| 协议 | 错误标识 | 处理 |
|------|----------|------|
| Dify | `error` 事件 | 解析错误码和消息 |
| Athena | `code != "0"` | 解析 error 字段 |
| 通用 | 连接异常 | 捕获异常并转换 |

---

## 8. 生命周期管理

### 8.1 CloudConnectionLifecycle 回调

| 回调方法 | 触发时机 |
|----------|----------|
| `onConnected()` | 连接建立成功 |
| `onEventReceived()` | 收到事件 |
| `onHeartbeat()` | 收到心跳 |
| `onTerminalEvent()` | 收到终端事件(tool_done/tool_error) |

### 8.2 超时配置

| 超时类型 | 默认值 | 配置项 |
|----------|--------|--------|
| 连接超时 | 30秒 | `gateway.cloud.connect-timeout-seconds` |
| 首事件超时 | 30秒 | `gateway.cloud.first-event-timeout-seconds` |
| 空闲超时 | 60秒 | `gateway.cloud.idle-timeout-seconds` |
| 最大连接时长 | 10分钟 | `gateway.cloud.max-duration-seconds` |

---

## 9. 新增文件清单

| 文件路径 | 说明 | 状态 |
|----------|------|------|
| `src/main/java/.../cloud/DifyProtocolStrategy.java` | Dify 协议策略 | 新增 |
| `src/main/java/.../cloud/AgentMakerProtocolStrategy.java` | AgentMaker 协议策略 | 新增 |
| `src/main/java/.../cloud/UniKnowProtocolStrategy.java` | UniKnow 协议策略 | 新增 |
| `src/main/java/.../cloud/AthenaProtocolStrategy.java` | Athena 协议策略 | 新增 |
| `src/main/java/.../cloud/StandardProtocolStrategy.java` | Standard 标准协议策略 | 新增 |
| `src/test/java/.../cloud/DifyProtocolStrategyTest.java` | Dify 测试 | 新增 |
| `src/test/java/.../cloud/AgentMakerProtocolStrategyTest.java` | AgentMaker 测试 | 新增 |
| `src/test/java/.../cloud/UniKnowProtocolStrategyTest.java` | UniKnow 测试 | 新增 |

---

## 10. 代码位置引用

| 组件 | 文件路径 | 行号 |
|------|----------|------|
| 协议映射 | `CloudRouteService.java` | 214-227 |
| 协议策略接口 | `CloudProtocolStrategy.java` | 1 |
| Dify 策略 | `DifyProtocolStrategy.java` | 1 |
| AgentMaker 策略 | `AgentMakerProtocolStrategy.java` | 1 |
| UniKnow 策略 | `UniKnowProtocolStrategy.java` | 1 |
| Athena 策略 | `AthenaProtocolStrategy.java` | 1 |
| GatewayMessage | `GatewayMessage.java` | 1 |
| CloudConnectionLifecycle | `CloudConnectionLifecycle.java` | 1 |