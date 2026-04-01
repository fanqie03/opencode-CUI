# IM 长链接 WebSocket 通信接口文档

> 规范 IM 平台与 Skill-Server 之间双向长连接通信协议。出站侧透传 17 种服务端流式消息事件，IM 客户端自行完成差异化渲染。

## 接口概述

| 项目 | 说明 |
| --- | --- |
| **接口名称** | 客户端长连接入站与流式出站 |
| **WebSocket URI** | `wss://{domain}/ws/link` |
| **交互心跳** | 是，支持双向心跳包 |

---

## 1. 握手与鉴权

客户端在建立 WebSocket 连接时，必须通过 `Sec-WebSocket-Protocol` 子协议头传递鉴权与环境信息（类似现有内部网关的机制）。

### Header 结构
- `Sec-WebSocket-Protocol`: `token, clientType, instanceId`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `token` | ✅ | 安全认证 Token，用于 skill-server 校验 |
| `clientType` | ✅ | 客户端类型标识。IM 渠道必须固定传 `"im"` |
| `instanceId` | ✅ | 客户端实例 ID，用于标记连接来源（如集群多实例下用于区分节点） |

### 错误处理
如果缺少 Header 或者鉴权未通过，Skill-Server 会发送 Close 帧（`CloseStatus.BAD_DATA` 等）后强制关闭连接。

---

## 2. 消息封装信封模式 (Envelope Pattern)

无论是上行入站还是下行出站消息，均需使用同一标准的 `LinkEnvelope` JSON 包装器进行通信及路由定位。

| 字段 | 类型 | 方向 | 说明 |
| --- | --- | --- | --- |
| `type` | String | 双向 | 顶层包类型标识：<br>- 上行: `"message"`, `"heartbeat"`<br>- 下行: `"stream"`, `"ack"`, `"heartbeat_ack"`, `"error"` |
| `requestId` | String | 双向 | (可选) 一次请求的唯一流水号，用于关联 ACK 响应 |
| `businessDomain` | String | 双向 | 业务域标识，IM 通道填写 `"im"` |
| `sessionType` | String | 双向 | 对话会话类型：`"direct"`（单聊）、`"group"`（群聊） |
| `sessionId` | String | 双向 | 在 IM 平台的会话唯一标识 |
| `assistantAccount` | String | 上行 | 助手账号标识（仅上行业务消息必传） |
| `content` | String | 上行 | 聊天文本内容（仅 `"message"` 类型必传） |
| `msgType` | String | 上行 | 消息媒体类型（当前仅支持 `"text"`） |
| `chatHistory` | Array | 上行 | 群聊等历史聊天上下文数组 |
| `message` | Object | 下行 | 当下行 `type="stream"` 时包装底层核心的 `StreamMessage` 对象 |

*(注：部分字段视不同顶层 `type` 是否为必传向外透出或丢弃)*

---

## 3. 入站通信 (IM -> Skill-Server)

### 3.1 业务会话消息
IM 用来向 Skill-Server 发起一次正常提问。

**请求示例 `type: message`**
```json
{
  "type": "message",
  "requestId": "req-102938ax",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "user-abc-123",
  "assistantAccount": "assistant-bot-001",
  "content": "请帮我查一下今天下午的行程",
  "msgType": "text"
}
```

*Skill-Server 收到后会立即且仅作技术层面的异步确认 (ACK) 发送：*
```json
{
  "type": "ack",
  "requestId": "req-102938ax"
}
```

### 3.2 心跳探活
客户端应保持定期的心跳发送，避免被远端断开连接（服务端超时配置通常为 60 秒内）。

**请求示例 `type: heartbeat`**
```json
{
  "type": "heartbeat",
  "requestId": "hb-1"
}
```

**响应示例 `type: heartbeat_ack`**
```json
{
  "type": "heartbeat_ack",
  "requestId": "hb-1"
}
```

---

## 4. 出站通信 (Skill-Server -> IM)

> [!IMPORTANT]
> **全量流式透传**：Skill-Server 侧不对下发消息做纯文本过滤，直接透传与原生 MiniApp 同等能力的所有 `StreamMessage` 信息流（总计 17 种场景分类），IM 端负责处理相应的文本拼接、卡片 UI 渲染（如工具图文、选项等）。

服务端将下发的原始模型 `StreamMessage` 套入 `LinkEnvelope` 的 `message` 字段进行发送（此时 `type`=`"stream"`）。

### 4.1 基础的 StreamMessage 内嵌结构示例
**示例下发流：**
```json
{
  "type": "stream",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "user-abc-123",
  "message": {
    "type": "text.delta",
    "seq": 1024,
    "role": "assistant",
    "content": "根据查询，您今天",
    "emittedAt": "2026-03-31T09:00:00Z"
  }
}
```

### 4.2 StreamMessage 具体各类型说明表

所有下发消息实体都在 Envelope 的 `message.type` 维度上进一步细分。请据此在联调处理时匹配。

| `message.type` 取值 | 类别及生命周期 | 描述行为及包含的核心字段 |
| --- | --- | --- |
| `text.delta` | 内容流 | 对话纯文本的增量片段。需拼接 `message.content` 展示。 |
| `text.done` | 内容流 | 单次内容的结束通知（携带此段流完整聚合 `content`）。 |
| `thinking.delta` | 思维链 | 大脑思考过程的增量片段。拼接 `message.content` 用于透视思维链过程。 |
| `thinking.done` | 思维链 | 思考过程的聚合完成通知。 |
| `tool.update` | 工具流 | 智能体在调用工具/插件，包含实时函数调用记录。参数挂载于 `message.tool` 对象。 |
| `step.start` | 执行节点 | 工作流的各步级开启节点。 |
| `step.done` | 执行节点 | 步级结束，通常在此处携带 `message.usage` (如耗费的 token / 时延)。 |
| `session.status` | 状态变更 | 当前答复线程全局状态变更，如 `"busy"`, `"idle"`。 |
| `session.title` | 状态变更 | 通知新计算出的摘要主标题（字段随 `message.title` ）。 |
| `session.error` | 全局异常 | 会话维度的重度失败级回传。 |
| `permission.ask` | 卡片交互 | 向用户申请使用特定权限的审批，IM 需唤出二次确认的交互卡片（带 `message.permission`）。 |
| `permission.reply` | 卡片交互 | 对用户批复授权状态的最终执行确认。 |
| `question` | 卡片交互 | 触发主动多轮提问，包含 `message.questionInfo` (题干及 `options` 选项数组)，供前端发渲染。 |
| `file` | 附件/多媒体 | 文件/链接的传回，见 `message.file`。 |
| `agent.online` / `agent.offline` | Agent状态 | 特定助手在线及下线信号。 |
| `error` | 普通异常 | 流式请求的底层传输时阻断性异常报错。可将详细挂载在 `message.error` 里。 |
| `snapshot` / `streaming`| 同步缓存包 | 只在断线重连、或初次建立连接时用于批量同步完整上下文缓存。 |

### 4.3 `message` 内部主要对象说明：
针对特定的 `type`（如遇到 `tool.update`, `question` 等），会有特殊嵌套对象展开：
1. **ToolInfo (`tool`)**
   - `toolName`: 插件名称
   - `input` / `output`: 请求/响应。
2. **PermissionInfo (`permission`)**
   - `permissionId`, `permType` 等审批透传标识。
3. **QuestionInfo (`questionInfo`)**
   - `header`, `question`: 头部引导、题干文字描述。
   - `options(List<String>)`: 选项。

---

## 5. 出站失败降级模式
如果 `skill-server` 检查到当前该 `clientType="im"` 没有活跃的 websocket 连接，在内部机制中会自动回落 (Fallback) 到原有的 `/api/outbound/messages` REST 层推送请求机制，以规避不可挽回的通信卡死；
在此模式下，它会退回到旧有的 `buildImText(...)` 实现——仅挑选 `text.done` 等终端纯文本回吐事件。

强烈建议 IM 服务在生产稳定环境中必须持有 websocket 活跃链路以获完整功能。
