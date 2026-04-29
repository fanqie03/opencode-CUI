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
- `message` / `agent_message` / `text_chunk` 中的 `answer` / `text` 字段可能包含 `think` 标签，需要提取 `think` 标签后内容进行传递，开头的无效字符（如 `\n`、空格、空字符串）需要忽略

**输出转换示例**:

#### message / agent_message（文本内容，含 think 标签处理）
**原始响应**:
```json
{"event":"agent_message","answer":"think\n好的，让我来思考用户的需求。\n\n您好，请问有什么可以帮助您？","conversation_id":"conv-001","message_id":"msg-001"}
```
**转换结果**（提取 `think` 标签后内容）:
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

#### text_chunk（Workflow 文本块，含 think 标签处理）
**原始响应**:
```json
{"event":"text_chunk","data":{"text":"think\n正在分析用户问题\n\n工作流执行结果"},"task_id":"task-001"}
```
**转换结果**（提取 `think` 标签后内容）:
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

#### message_end（消息结束）
**原始响应**:
```json
{"event":"message_end","conversation_id":"conv-001","message_id":"msg-001","created_at":1775993023,"task_id":"task-001","id":"msg-001","metadata":{"annotation_reply":null,"retriever_resources":[],"usage":{"prompt_tokens":16,"prompt_unit_price":"0","prompt_price_unit":"0.001","prompt_price":"0","completion_tokens":25,"completion_unit_price":"0","completion_price_unit":"0.001","completion_price":"0","total_tokens":41,"total_price":"0","currency":"RMB","latency":2.060744408518076,"time_to_first_token":null,"time_to_generate":null}},"files":null}
```
**转换结果**:
```json
{
    "type": "tool_done",
    "toolSessionId": "conv-001"
}
```

#### workflow_finished（工作流结束）
**原始响应**:
```json
{"event":"workflow_finished","workflow_run_id":"7351221f-20b6-4dce-afe4-00f08518c2a9","task_id":"task-001","data":{"id":"7351221f-20b6-4dce-afe4-00f08518c2a9","workflow_id":"61de840d-fb67-4471-be35-8f79ebc548ea","status":"succeeded","outputs":{"a":"你好👋！有什么可以帮助你的吗？"},"error":null,"elapsed_time":1.979352,"total_tokens":24,"total_steps":4,"created_by":{"id":"0bf3e73f-7bb7-4d32-9062-10776beb7e67","user":"abc-123"},"created_at":1775994372,"finished_at":1775994374,"exceptions_count":0,"files":[]}}
```
**转换结果**:
```json
{
    "type": "tool_done",
    "toolSessionId": "task-001"
}
```

#### error（错误）
**原始响应**:
```json
{"event":"error","task_id":"task-001","message_id":"msg-001","status":500,"code":"internal_error","message":"服务内部错误"}
```
**转换结果**:
```json
{
    "type": "tool_error",
    "toolSessionId": "task-001",
    "error": "服务内部错误"
}
```

#### message_replace（消息内容替换）
**原始响应**:
```json
{"event":"message_replace","conversation_id":"conv-001","message_id":"msg-001","created_at":1775993023,"task_id":"task-001","answer":"这是替换后的内容"}
```
**转换结果**:
```json
{
    "type": "tool_event",
    "toolSessionId": "conv-001",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "这是替换后的内容"
        }
    }
}
```

---

### 2.2 标准协议（Standard）

**协议类型**: SSE（Server-Sent Events）

**适用场景**: 通用标准协议，支持多种消息类型

**关键说明**: 
- 响应中不包含 `topicId` 和 `messageId`，`toolSessionId` 从入参中提取（优先使用 `messageId`，其次使用 `topicId`）
- **容错处理**：即使对接方未发送 `isFinish=true`，当 SSE 连接正常断开时也会自动发送 `tool_done` 事件

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
| `content` | `query` | 用户输入内容 | - |
| `sendUserAccount` | `user` | 用户账号 | - |
| `extParameters` | - | 扩展参数 | - |

**aiGateway 输入格式**:
```json
{
    "content": "用户输入内容",
    "contentType": "text",
    "sendUserAccount": "user-001",
    "topicId": "topic-001",
    "extParameters": {}
}
```

**转换后的 UniKnow 输入格式**:
```json
{
    "query": "用户输入内容",
    "user": "user-001"
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

#### 响应为空（无回答内容）
**原始响应**:
```json
{
    "data": [
        {
            "taskInfo": {
                "slots": {
                    "result": {
                        "data": "",
                        "requestId": "req-67890"
                    }
                }
            }
        }
    ]
}
```

**转换结果**:
```json
{
    "type": "tool_done",
    "toolSessionId": "req-67890"
}
```

#### 异常响应（HTTP 非 200 状态码）
**原始响应**:
```json
{
    "error": "服务内部错误",
    "code": 500
}
```

**转换结果**:
```json
{
    "type": "tool_error",
    "toolSessionId": "",
    "error": "UniKnow service error: 服务内部错误"
}
```

---

### 2.4 Athena 协议（标准协议）

**协议类型**: 两步协议（创建任务 + SSE）

**请求流程**:
1. **POST `/tasks`** - 创建任务，获取 `taskId`
2. **GET `/stream?id={taskId}`** - 通过 SSE 获取流式结果

**事件类型映射**:

| Athena 数据类型 | Gateway 消息类型 | 说明 |
|-----------------|------------------|------|
| `text` | `tool_event` (text.delta) | 文本内容 |
| `planning` | `tool_event` (thinking) | 规划中 |
| `searching` | `tool_event` (searching) | 搜索中 |
| `searchResult` | `tool_event` (search_result) | 搜索结果 |
| `reference` | `tool_event` (reference) | 引用 |
| `think` | `tool_event` (thinking) | 深度思考 |
| `askMore` | `tool_event` (ask_more) | 追问 |
| `isFinish=true` | `tool_done` | 完成 |

**原始响应格式**:
```json
{
    "code": "0",
    "message": "",
    "error": "",
    "isFinish": false,
    "data": {
        "type": "text",
        "content": "响应内容"
    }
}
```

**输出转换**:
```json
// Athena text → Gateway tool_event
{
    "type": "tool_event",
    "toolSessionId": "taskId",
    "event": {
        "type": "text.delta",
        "properties": {
            "content": "响应内容"
        }
    }
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