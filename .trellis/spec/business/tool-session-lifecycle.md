# toolSessionId 生命周期业务场景

## 场景边界

`toolSessionId` 是 SS/GW/agent 之间实际执行对话的工具会话 ID。它不是业务入口会话 ID，也不是前端或外部 IM 的会话 ID。

业务会话和工具会话的关系可以理解为：

```text
external:
  assistantAccount + business sessionId
    -> SkillSession
    -> toolSessionId

miniapp:
  userId + welinkSessionId
    -> SkillSession
    -> toolSessionId
```

`toolSessionId` 生命周期横跨：

- 创建和绑定。
- chat / question reply / permission reply 使用。
- 缺失或失效时的自动恢复。
- external 主动 rebuild。
- GW 上行路由确认。
- close / abort 收尾。

## source of truth

`toolSessionId` 的业务主事实来源是：

```text
SkillSession.toolSessionId
```

Redis 中存在辅助映射：

```text
toolSessionId -> welinkSessionId
```

但该映射主要用于 GW 上行回流时快速定位 SS 会话，不是业务主数据。

判断一个 session 是否 ready，本质条件是：

```text
SkillSession exists
AND SkillSession.toolSessionId not blank
```

## 获取方式矩阵

| 助手类型 | toolSessionId 来源 | 是否等待 `session_created` | 说明 |
| --- | --- | --- | --- |
| 本地 agent | GW/agent 回调绑定 | 是 | 本地 agent 侧真实创建工具会话后回传 |
| 云端 agent | SS 预生成 | 否 | 作为云端 topic/session 标识 |
| 虚拟 agent | SS 预生成 | 否 | 由 default assistant strategy 生成，wire 上仍走 business/cloud |

## 创建期

### 本地 agent

本地 agent 的创建期：

```text
创建 SkillSession
  -> toolSessionId 为空
  -> SS 发 create_session 到 Gateway
  -> Gateway 路由到本地 agent
  -> agent 创建工具会话
  -> Gateway 回 session_created
  -> SS 更新 SkillSession.toolSessionId
  -> retry pending message
```

业务含义：

- `toolSessionId` 为空并不一定是异常，本地 agent 创建期天然会经历这个状态。
- 只有收到 `session_created` 后，SS 才能认为工具会话 ready。
- 创建过程中到达的用户消息需要进入 pending，等待绑定后 retry。

### 云端 agent

云端 agent 的创建期：

```text
创建 SkillSession
  -> BusinessScopeStrategy 预生成 toolSessionId
  -> 写入 SkillSession.toolSessionId
  -> 不等待 session_created
  -> 后续 chat 直接使用该 toolSessionId
```

业务含义：

- 云端 agent 不依赖本地 agent 在线状态。
- `toolSessionId` 作为云端 topic/session 标识。
- 预生成 ID 必须满足云端协议的字段要求。

### 虚拟 agent

虚拟 agent 的创建期：

```text
miniapp 创建 session
  -> 命中 default_assistant_rule
  -> 注入虚拟 ak / assistantAccount / businessTag
  -> DefaultAssistantScopeStrategy 预生成 toolSessionId
  -> 写入 SkillSession.toolSessionId
```

业务含义：

- 虚拟 agent 当前只在 miniapp。
- 虚拟 agent 的 `toolSessionId` 获取方式与云端 agent 类似。
- 虚拟 agent 的业务身份来自默认助手规则，而不是用户显式选择的真实助手。

## 云端 toolSessionId 格式约束

云端 agent 和虚拟 agent 预生成的 `toolSessionId` 当前需要是纯数字字符串。

关键原因：

```text
assistant_square 协议中的 topicId 会 Long.parseLong(toolSessionId)
```

因此不能随意改成：

```text
cloud-xxx
```

或其他非数字格式。否则助手广场协议请求构造会失败。

## 使用期

普通 chat、question reply、permission reply 最终都会使用 `toolSessionId` 或目标 `subagentSessionId`。

### chat

chat 下发 Gateway 的核心字段：

```text
text
toolSessionId
assistantAccount
sendUserAccount
messageId
businessExtParam
```

external 群聊还必须包含：

```text
imGroupId = sessionId
```

### question reply

question reply 的核心字段：

```text
answer
toolCallId
toolSessionId
questionId?
assistantAccount
sendUserAccount
businessExtParam
```

如果有 `subagentSessionId`：

```text
targetToolSessionId = subagentSessionId
```

否则：

```text
targetToolSessionId = session.toolSessionId
```

### permission reply

permission reply 的核心字段：

```text
permissionId
response
toolSessionId
assistantAccount
sendUserAccount
businessExtParam
```

同样，`subagentSessionId` 存在时优先作为目标 `toolSessionId`。

## pending message 语义

本地 agent 创建期或 rebuild 期间，当前用户消息可能无法立即投递到 Gateway。此时 SS 会缓存 pending message。

pending 不是简单缓存一段文本，它必须保留重新构造 invoke 所需的业务上下文。

external 群聊尤其关键：

```text
sendUserAccount = 真实群成员
imGroupId = sessionId
assistantAccount
businessExtParam
domain
domainType = group
businessSessionId = sessionId
```

不能在 retry 时从 `SkillSession.userId` 推断 sender，因为 group session 的：

```text
SkillSession.userId = null
```

## retry 语义

retry pending message 不是把原始文本原样重发，而是基于 pending 上下文重新构造 chat invoke。

retry 时需要重新补齐：

- `toolSessionId`
- `assistantAccount`
- `sendUserAccount`
- `imGroupId`
- `businessExtParam`
- `domain`
- `domainType`
- `businessSessionId`
- group 场景的 `suppressReply`

业务含义：

```text
pending 保存的是“待恢复的业务消息上下文”，不是“原始 HTTP/WebSocket 报文”。
```

## 缺失恢复边界

### external chat

external `chat` 是 external 场景里可创建和恢复会话的用户消息 action。

分支：

| 条件 | 行为 |
| --- | --- |
| session 不存在 | 创建 SkillSession |
| session 存在但 `toolSessionId` 缺失 | 触发自愈 / rebuild，并保存 pending |
| session ready | 直接投递 chat invoke |

### external reply

external reply 类 action 包括：

```text
question_reply
permission_reply
```

边界：

- 要求已有 ready session。
- 不创建新 session。
- 不触发 `toolSessionId` 自动恢复。
- 找不到 ready session 时失败。

### miniapp `/messages`

miniapp `/messages` 同时承载：

- 普通 chat。
- question reply。

当 route 时发现 `toolSessionId` 缺失：

```text
保存用户消息
  -> 发现 toolSessionId 缺失
  -> rebuildToolSession
  -> pending message
  -> session_created 后 retry
```

因此 miniapp 的 question reply 和 external reply 的恢复边界不完全相同，这是入口实现差异，不是助手类型差异。

### miniapp permission reply

miniapp permission reply 走独立接口：

```text
POST /api/skill/sessions/{sessionId}/permissions/{permId}
```

边界：

- 要求已有 `toolSessionId`。
- 缺失时返回错误。
- 不在 permission reply 中自动恢复。

## rebuild 类型

### 自动 rebuild

自动 rebuild 由 SS 内部触发。

典型触发：

```text
chat 入站
  -> SkillSession 存在
  -> toolSessionId 缺失或不可用
```

特点：

- 通常带有 pending message。
- 恢复完成后需要 retry 当前消息。
- 用户不显式感知 rebuild action。

### 主动 rebuild

主动 rebuild 由 external 显式发起：

```text
POST /api/external/invoke
action = rebuild
```

特点：

- 是外部系统要求 SS 重建或刷新会话链路。
- 它本身不是一条用户消息。
- 不一定携带当前用户内容。

处理分支：

| 条件 | 行为 |
| --- | --- |
| session 不存在 | 创建 session |
| session 存在且 strategy 可直接生成 `toolSessionId` | 加锁后生成新 `toolSessionId` 并更新 session |
| session 存在但不能直接生成 | requestToolSession，等待 Gateway/agent 重新创建 |

## GW 上行路由确认

GW 上行事件可能只带：

```text
toolSessionId
```

而不带：

```text
welinkSessionId
```

SS 解析流程：

```text
toolSessionId
  -> 查 Redis mapping: toolSessionId -> welinkSessionId
  -> 查不到再查 DB: SkillSession.toolSessionId
  -> 找到后回 route_confirm
  -> 找不到后回 route_reject
```

业务含义：

- `route_confirm` / `route_reject` 不是用户消息 reply。
- 它们是 GW 到 SS 上行路由的控制面机制。
- 用于多实例场景下让 GW 确认某个 `toolSessionId` 应该回到哪个 SS 会话。

## SS owner 和 toolSessionId 的区别

需要区分两层概念：

```text
toolSessionId:
  agent/cloud 工具会话标识

SS owner:
  哪个 SS 实例负责处理该 SkillSession 的回流
```

GW 回流后：

```text
toolSessionId
  -> welinkSessionId
  -> session route owner
  -> 当前实例处理 / relay 到其他实例 / takeover
```

因此 `toolSessionId` 生命周期不能只看创建，还要看回流路由归属。

## close 收尾

close 用于关闭会话：

```text
DELETE /api/skill/sessions/{sessionId}
```

或 external 场景的对应关闭/生命周期动作。

行为：

- 存在助手身份和 `toolSessionId` 时，可发送 `close_session` 到 Gateway。
- 随后关闭 `SkillSession`。
- 关闭后不应继续接收新的 chat/reply。

miniapp 默认/虚拟助手命中规则的会话当前有特殊逻辑：

```text
close lifecycle invoke 会跳过 default assistant rule session
```

## abort 收尾

abort 用于中断当前轮次，但保留会话可复用：

```text
POST /api/skill/sessions/{sessionId}/abort
```

行为：

```text
1. 如果存在助手身份和 toolSessionId，则发送 abort_session
2. 本地把 streaming buffer 收尾
3. 持久化可收尾 final part
4. 标记 session idle
5. 清理 buffer
```

业务含义：

- `abort` 不等同于 close。
- `abort` 后 session 可以继续发下一条消息。
- `IDLE` 不代表 `toolSessionId` 失效，只代表当前轮次空闲。

## 生命周期状态模型

可以用以下状态理解 `toolSessionId` 生命周期：

```text
NO_SESSION
  -> SESSION_WITHOUT_TOOL
  -> TOOL_CREATING
  -> TOOL_READY
  -> TOOL_REBUILDING
  -> IDLE / CLOSED
```

状态含义：

| 状态 | 含义 |
| --- | --- |
| `NO_SESSION` | SS 还没有 `SkillSession` |
| `SESSION_WITHOUT_TOOL` | 有业务会话，但没有 `toolSessionId` |
| `TOOL_CREATING` | 已向 GW/agent 请求创建工具会话 |
| `TOOL_READY` | `SkillSession.toolSessionId` 已绑定 |
| `TOOL_REBUILDING` | 旧链路缺失/失效，正在恢复 |
| `IDLE` | 当前轮次结束，会话可复用 |
| `CLOSED` | 会话关闭，不应继续对话 |

注意：

```text
IDLE != toolSessionId 失效
```

## 业务不变量

- `SkillSession.toolSessionId` 是业务主事实来源。
- Redis `toolSessionId -> welinkSessionId` 是上行路由辅助，不是主数据。
- 本地 agent 的 `toolSessionId` 必须等 `session_created`。
- 云端 agent 和虚拟 agent 的 `toolSessionId` 由 SS 预生成。
- 云端/虚拟 agent 预生成的 `toolSessionId` 必须保持可解析为 Long 的数字字符串。
- pending message 必须保留重构 invoke 所需业务上下文。
- external 群聊 retry 不能从 `SkillSession.userId` 推断 sender。
- retry 是重构 invoke，不是原始报文原样转发。
- external reply 类 action 不负责创建 session 或自动恢复。
- miniapp `/messages` 缺 `toolSessionId` 时可触发 rebuild。
- miniapp permission reply 缺 `toolSessionId` 时失败。
- `route_confirm` / `route_reject` 是控制面路由机制，不是业务回复。
- `abort` 保留 session 可复用；`close` 关闭 session。

## 代码证据

- `SkillSession.toolSessionId`：工具会话 ID 的持久化字段。
- `SkillSessionFlowService.routeCreateSession`：按 scope strategy 决定预生成或发送 `create_session`。
- `BusinessScopeStrategy.generateToolSessionId`：云端 agent 预生成数字型 `toolSessionId`。
- `DefaultAssistantScopeStrategy.generateToolSessionId`：虚拟/默认助手预生成数字型 `toolSessionId`。
- `PersonalScopeStrategy.generateToolSessionId`：本地 agent 返回 null，等待 `session_created`。
- `GatewayMessageRouter.handleSessionCreated`：绑定 `toolSessionId` 并 retry pending messages。
- `GatewayMessageRouter.retryPendingMessages`：基于 pending 上下文重构 chat invoke。
- `GatewayMessageRouter.resolveSessionId`：用 `toolSessionId` 查 Redis/DB 并发送 `route_confirm` / `route_reject`。
- `GatewayRelayService.rebuildToolSession`：触发工具会话重建。
- `SkillMessageFlowService.routeToGateway`：miniapp `/messages` 缺 `toolSessionId` 时触发 rebuild。
- `SkillMessageFlowService.replyPermission`：permission reply 要求 `toolSessionId` 存在。
