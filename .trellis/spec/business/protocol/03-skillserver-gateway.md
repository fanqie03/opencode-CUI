# Layer 3：Skill Server ↔ AI Gateway 协议

## 概述

Skill Server 和 AI Gateway 之间使用 WebSocket 双向通信，载荷统一为 `GatewayMessage`。

```text
Skill Server
  GatewayWSClient
  -> ws://<gateway>/ws/skill
  -> AI Gateway SkillWebSocketHandler
  -> AgentWebSocketHandler / cloud strategy
```

这层是内部中继协议，不直接暴露给 miniapp 或 externalWs 消费者。

## 一、WebSocket 连接

### 1.1 Skill Server 连接 Gateway

endpoint：

```text
ai-gateway /ws/skill
```

Skill Server 默认配置：

```text
skill.gateway.ws-url = ws://localhost:8081/ws/skill
```

认证：

```text
Sec-WebSocket-Protocol: auth.<Base64URL(JSON)>
```

认证 JSON：

```json
{
  "token": "<skill.gateway.internal-token>",
  "source": "skill-server",
  "instanceId": "ss-instance-id"
}
```

### 1.2 Agent 连接 Gateway

endpoint：

```text
ai-gateway /ws/agent
```

认证同样走 `Sec-WebSocket-Protocol` auth 子协议。连接建立后 agent 必须先发送：

```json
{
  "type": "register",
  "deviceName": "dev-machine",
  "macAddress": "00-11-22",
  "os": "windows",
  "toolType": "opencode",
  "toolVersion": "x.y.z"
}
```

Gateway 返回：

```json
{"type": "register_ok"}
```

或：

```json
{"type": "register_rejected", "reason": "..."}
```

## 二、GatewayMessage 结构

owner：

```text
ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
```

核心字段：

| 字段 | 说明 |
| --- | --- |
| `type` | 消息类型 |
| `ak` | agent/cloud 路由主键 |
| `welinkSessionId` | Skill Server 会话 ID |
| `toolSessionId` | agent/cloud 工具会话 ID |
| `userId` | 服务端注入用户上下文，下发 agent 前剥离 |
| `source` | 服务端注入来源上下文，下发 agent 前剥离 |
| `traceId` | 跨服务追踪 ID |
| `messageId` | agent/cloud 回复消息 ID |
| `action` | invoke 动作 |
| `assistantAccount` | 助手账号 |
| `businessTag` | 云端 profile / SysConfig 路由标签 |
| `payload` | invoke 或 register 载荷 |
| `event` | OpenCode 或 cloud 标准事件 |
| `usage` | 用量 |
| `error` / `reason` | 错误信息 |
| `subagentSessionId` / `subagentName` | 子 agent 信息 |
| `suppressReply` | group 场景 suppress 分支 |
| `assistantScope` | personal / business 等 scope |

## 三、消息类型

| type | 方向 | 语义 |
| --- | --- | --- |
| `register` | agent -> GW | agent 注册 |
| `register_ok` | GW -> agent | 注册成功 |
| `register_rejected` | GW -> agent | 注册拒绝 |
| `heartbeat` | agent -> GW | 心跳 |
| `invoke` | SS -> GW -> agent/cloud | 用户动作或 lifecycle 调用 |
| `tool_event` | agent/cloud -> GW -> SS | 流式事件 |
| `tool_done` | agent/cloud -> GW -> SS | 执行完成 |
| `tool_error` | agent/cloud -> GW -> SS | 执行错误 |
| `session_created` | agent -> GW -> SS | 本地工具会话创建完成 |
| `agent_online` | GW -> SS | agent 在线 |
| `agent_offline` | GW -> SS | agent 离线 |
| `status_query` | SS/GW -> agent | 状态查询 |
| `status_response` | agent -> GW/SS | 状态回复 |
| `permission_request` | agent -> GW/SS | 权限请求透传 |
| `route_confirm` | SS -> GW | SS 确认拥有回源路由 |
| `route_reject` | SS -> GW | SS 拒绝回源路由 |
| `im_push` | 内部 | IM push |

## 四、invoke action

典型 invoke：

```json
{
  "type": "invoke",
  "ak": "agent-ak",
  "welinkSessionId": "12345",
  "userId": "user-001",
  "source": "skill-server",
  "traceId": "trace-001",
  "action": "chat",
  "assistantAccount": "assistant-001",
  "businessTag": "assistant_square",
  "payload": {
    "text": "hello",
    "toolSessionId": "987654321",
    "sendUserAccount": "user-001",
    "assistantAccount": "assistant-001",
    "messageId": "msg-001",
    "cloudProfile": "assistant_square",
    "cloudRequest": {}
  }
}
```

action：

| action | 触发 | payload 关键字段 |
| --- | --- | --- |
| `chat` | 用户发消息 | `text`, `toolSessionId`, `sendUserAccount`, `assistantAccount`, `messageId`, `businessExtParam` |
| `create_session` | personal scope 创建本地 agent session | `title`, `assistantAccount` |
| `close_session` | close | `toolSessionId` |
| `abort_session` | abort 当前轮次 | `toolSessionId` |
| `question_reply` | question 卡片回复 | `answer`, `toolCallId`, `questionId`, `toolSessionId` |
| `permission_reply` | permission 卡片回复 | `permissionId`, `response`, `toolSessionId` |

group 场景额外字段：

```text
imGroupId = sessionId
domain = businessDomain
domainType = group
businessSessionId = sessionId
suppressReply? = true/false
```

## 五、下行：SS -> GW -> agent/cloud

```text
miniapp/external REST
  -> Skill Server flow service
  -> scope strategy
  -> GatewayMessage(type=invoke)
  -> GatewayRelayService
  -> GatewayWSClient
  -> AI Gateway /ws/skill
  -> SkillRelayService
  -> local agent 或 cloud strategy
```

scope 决定：

| scope | toolSessionId 来源 | 目标 |
| --- | --- | --- |
| personal | 等 `session_created` | 本地 agent |
| business | SS 预生成 | 云端 agent |
| default assistant | SS 预生成 | 虚拟/默认云端助手 |

## 六、上行：agent/cloud -> GW -> SS

```text
local agent / cloud
  -> GatewayMessage(tool_event/tool_done/tool_error/session_created)
  -> AI Gateway
  -> Skill Server
  -> translator
  -> StreamMessage
  -> miniapp 或 externalWs
```

上行消息：

| type | 关键字段 | SS 处理 |
| --- | --- | --- |
| `tool_event` | `toolSessionId`, `messageId`, `event` | 翻译为 `StreamMessage` |
| `tool_done` | `toolSessionId`, `messageId`, `usage` | step/session 收尾 |
| `tool_error` | `toolSessionId`, `messageId`, `error`, `reason` | 错误下行或 rebuild 判断 |
| `session_created` | `welinkSessionId`, `toolSessionId` | 绑定 `SkillSession.toolSessionId`，retry pending |
| `permission_request` | permission 字段 | 翻译为 `permission.ask` |

## 七、路由控制面

GW 上行事件可能只有 `toolSessionId`，没有 `welinkSessionId`。SS 需要确认它是否拥有此回源路由：

```text
toolSessionId
  -> Redis mapping toolSessionId -> welinkSessionId
  -> DB fallback SkillSession.toolSessionId
  -> route_confirm 或 route_reject
```

`route_confirm` / `route_reject` 不是用户消息，不下发给 miniapp/externalWs。

## 八、维护规则

- 新增 `GatewayMessage.type` 必须同时检查 SS 路由、GW 路由、agent/cloud 路由和测试。
- 不要把 `GatewayMessage` 作为客户端协议暴露；客户端只看 `StreamMessage`。
- 下发 agent 前要剥离 `userId/source/gatewayInstanceId` 这类服务端路由上下文。
- `businessTag` 是 cloud route provider 的优先输入；payload `cloudProfile` 保留兼容。
