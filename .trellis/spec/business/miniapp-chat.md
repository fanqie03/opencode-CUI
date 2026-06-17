# miniapp 对话业务场景

## 场景边界

miniapp 对话是用户在产品内通过 miniapp 前端发起的会话场景。它不属于 external IM，也不走旧的 `imInbound` 入口。

与 external IM 单聊、群聊相比，miniapp 的核心差异是：

- 入口来自 miniapp 前端 REST，而不是外部 IM 回调。
- 会话归属按 cookie `userId` 校验，而不是按外部 IM 会话唯一性查找。
- 下行实时回复走 miniapp WebSocket 和 Redis user stream，不走 externalWs。
- 虚拟 agent 当前只在 miniapp 对话中使用。

miniapp 默认业务域：

```text
businessSessionDomain = miniapp
businessSessionType = direct
```

整体链路：

```text
miniapp 前端
  -> Skill Server session/message REST
  -> Skill Server 会话、历史和 toolSessionId 管理
  -> AI Gateway
  -> local agent / cloud agent / virtual agent
  -> AI Gateway 回流
  -> Skill Server 翻译 StreamMessage
  -> Redis user stream
  -> /ws/skill/stream
  -> miniapp 前端
```

## 参与方

| 参与方 | 职责 |
| --- | --- |
| miniapp 前端 | 创建/切换会话，发送 chat、question reply、permission reply，查询历史，通过 WebSocket 接收实时消息 |
| Skill Server | 承接 miniapp REST，维护 `SkillSession`、消息历史、`toolSessionId`，向 Gateway 投递 invoke，并向用户 WebSocket 推送 `StreamMessage` |
| AI Gateway | 承接 SS invoke，路由到本地 agent 或云端 agent，并把 agent 事件回流给 SS |
| local agent | 本地插件 agent，负责处理 personal scope 会话 |
| cloud agent | 云端助手，按标准协议或助手广场协议处理 business scope 会话 |
| virtual agent | 由默认助手规则注入的虚拟助手，目前只在 miniapp 对话使用 |
| Redis user stream | SS 多实例下的 miniapp 用户级消息扇出通道 |

## 核心标识

| 概念 | 字段 | 业务含义 |
| --- | --- | --- |
| 用户 | `userId` cookie | miniapp 会话归属和访问控制依据 |
| SS 会话 | `SkillSession.id` / `welinkSessionId` | miniapp 对话主会话 ID，由 SS 生成 |
| 业务域 | `businessSessionDomain=miniapp` | 标识 miniapp 业务域 |
| 业务类型 | `businessSessionType=direct` | miniapp 默认 direct 类型，不等同 external IM 单聊 |
| 业务会话 ID | `businessSessionId` | 可选业务上下文；例如 miniapp 从 IM 场景打开时可携带关联 IM 标识 |
| 助手应用 | `ak` | 本地 agent 或云端助手路由标识 |
| 助手账号 | `assistantAccount` | 助手账号；虚拟 agent 可由规则注入 |
| agent 会话 | `toolSessionId` | SS/GW/agent 之间的工具会话 ID |

miniapp 的主唯一性可以理解为：

```text
userId + SkillSession.id
```

它不是 external IM 的：

```text
assistantAccount + sessionId
```

所有会话查询、消息发送、历史查询、关闭和中断动作，都要先校验 cookie `userId` 是否拥有该 `SkillSession`。

## 助手类型与云端协议子类型

miniapp 支持三类助手：

| 助手类型 | 当前 miniapp 是否支持 | 说明 |
| --- | --- | --- |
| 本地 agent | 是 | `personal` scope，经 GW 路由到本地插件 agent |
| 云端 agent：标准协议助手 | 是 | `business` scope，`cloudProfile=default`，请求/响应走标准云端协议 |
| 云端 agent：助手广场协议助手 | 是 | `business` scope，`cloudProfile=assistant_square`，请求/响应走助手广场协议适配 |
| 虚拟 agent | 是 | 通过 `default_assistant_rule(domain,type)` 注入虚拟 `ak`、`assistantAccount`、`businessTag`，当前只在 miniapp 使用 |

### 本地 agent

本地 agent 走 `personal` scope：

```text
create session
  -> 不预生成 toolSessionId
  -> 发送 create_session 到 Gateway
  -> 等待 session_created
  -> 绑定 toolSessionId
```

约束：

- 需要 agent 在线检查。
- `toolSessionId` 来自 agent/GW 的 `session_created` 回调。
- 缺 `toolSessionId` 时，chat 可触发 rebuild。

### 云端 agent

云端 agent 走 `business` scope：

```text
create session
  -> SS 预生成 Snowflake 型 toolSessionId
  -> 不等待 session_created
  -> chat / reply 按 cloud profile 构造 cloudRequest
```

profile 来源优先级：

```text
AssistantInfo.cloudProfile 显式配置
  -> cloud_protocol_profile:<businessTag> SysConfig 映射
  -> default
```

两类云端协议的业务差异：

| 云端协议子类型 | SS 请求构造 | GW 响应解码 | 关键字段形态 |
| --- | --- | --- | --- |
| 标准协议助手 | `DefaultCloudRequestStrategy` | `DefaultSseEventDecoder` | `content`、`assistantAccount`、`sendUserAccount`、`topicId`、`replyContext` |
| 助手广场协议助手 | `AssistantSquareCloudRequestStrategy` | `AssistantSquareSseEventDecoder` | `msgBody`、`sendW3Account`、`assistantAccount`、`topicId`、`replyContext` |

### 虚拟 agent

虚拟 agent 通过默认助手规则命中：

```text
default_assistant_rule:<domain>:<type>
  -> ak
  -> assistantAccount
  -> businessTag
```

处理特点：

- 用户创建会话时可以不传真实 `ak` / `assistantAccount`。
- SS 根据 `businessSessionDomain` 和 `businessSessionType` 查规则。
- 命中规则后，SS 注入虚拟 `ak`、`assistantAccount`、`businessTag`。
- `toolSessionId` 由 SS 预生成。
- wire 上仍按 `assistantScope=business` 进入 GW 云端路径。
- 删除检查会跳过默认/虚拟助手规则命中的会话。

## 入口分组

### 会话管理入口

| 接口 | 用途 |
| --- | --- |
| `POST /api/skill/sessions` | 创建 miniapp 会话 |
| `GET /api/skill/sessions` | 查询当前用户会话列表 |
| `GET /api/skill/sessions/{sessionId}` | 查询单个会话并校验访问权 |
| `DELETE /api/skill/sessions/{sessionId}` | 关闭会话 |
| `POST /api/skill/sessions/{sessionId}/abort` | 中断当前轮次，但保留会话可复用 |

### 对话交互入口

| 接口 | 用途 |
| --- | --- |
| `POST /api/skill/sessions/{sessionId}/messages` | 普通 chat；带 `toolCallId` 时为 question reply |
| `POST /api/skill/sessions/{sessionId}/permissions/{permId}` | permission reply |

### 历史恢复入口

| 接口 / 通道 | 用途 |
| --- | --- |
| `GET /api/skill/sessions/{sessionId}/messages/history` | 正式历史查询接口，使用 `beforeSeq + size` 游标模式 |
| `/ws/skill/stream` | 实时增量和 streaming 状态推送 |
| WebSocket `resume` action | 恢复当前 streaming 状态和 snapshot，不替代历史查询 |

### 废弃接口

```text
GET /api/skill/sessions/{sessionId}/messages
```

该 page/size 历史接口可以废弃，不再作为 miniapp 主业务流程的一部分。miniapp 正式历史恢复入口应使用：

```text
GET /api/skill/sessions/{sessionId}/messages/history
```

### 旁路动作

```text
POST /api/skill/sessions/{sessionId}/send-to-im
```

`send-to-im` 用于从 miniapp 把选中文本发回关联 IM。它不是 miniapp 主对话链路。

## 创建会话

入口：

```text
POST /api/skill/sessions
```

请求字段：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `ak` | 条件必填 | 普通本地/云端 agent 需要；虚拟 agent 可由规则注入 |
| `assistantAccount` | 条件必填 | 普通本地/云端 agent 使用；虚拟 agent 可由规则注入 |
| `title` | 可选 | 会话标题 |
| `businessSessionDomain` | 可选 | miniapp 默认 `miniapp` |
| `businessSessionType` | 可选 | miniapp 默认 `direct` |
| `businessSessionId` | 可选 | 业务侧会话上下文 |

流程：

```text
1. 校验 cookie userId
2. 判断请求是否显式携带 ak / assistantAccount
3. 未携带时，按 businessSessionDomain + businessSessionType 查 default_assistant_rule
4. 命中默认助手规则时，注入虚拟 ak / assistantAccount / businessTag
5. 创建 SkillSession
6. 根据 scope strategy 判断 toolSessionId 获取方式
7. personal scope 发送 create_session 到 Gateway
8. business / default assistant scope 预生成 toolSessionId
```

分支：

| 分支 | 条件 | 行为 |
| --- | --- | --- |
| 本地 agent | `personal` scope | 创建 SS 会话，发 `create_session` 到 GW，等待 `session_created` 绑定 `toolSessionId` |
| 云端 agent | `business` scope | 创建 SS 会话，预生成 `toolSessionId` |
| 虚拟 agent | 命中 `default_assistant_rule` | 注入虚拟助手信息，预生成 `toolSessionId` |
| 无助手身份且无规则 | 未传 `ak/assistantAccount` 且规则未命中 | 返回 `400` |

## 查询会话列表

入口：

```text
GET /api/skill/sessions
```

用途：

- 加载 miniapp 左侧会话列表。
- 恢复当前用户已有对话。
- 支持切换 session 前的列表刷新。

查询依据：

```text
cookie userId
```

可选过滤：

```text
status
ak
businessSessionDomain
businessSessionType
businessSessionId
assistantAccount
page
size
```

约束：

- 只返回当前 cookie `userId` 可访问的会话。
- 这是会话列表入口，不承载消息历史。

## 查询单个会话

入口：

```text
GET /api/skill/sessions/{sessionId}
```

用途：

- 进入指定会话。
- 刷新会话状态。
- 校验当前用户是否拥有该会话。

约束：

- `sessionId` 必须能解析成 SS 内部会话 ID。
- cookie `userId` 必须匹配会话归属。

## 查询历史记录

正式入口：

```text
GET /api/skill/sessions/{sessionId}/messages/history
```

查询方式：

```text
beforeSeq + size
```

用途：

- 打开会话时加载历史。
- 切换会话时恢复上下文。
- WebSocket 重连后先补历史，再处理 pending stream message。

边界：

- 历史主体来自 HTTP cursor history。
- WebSocket `resume` 只负责恢复当前 streaming 状态和 snapshot。
- `GET /api/skill/sessions/{sessionId}/messages` page/size 接口不再进入主流程。

## action: chat

入口：

```text
POST /api/skill/sessions/{sessionId}/messages
```

当请求不带 `toolCallId` 时，表示普通 chat。

主要流程：

```text
1. 校验 content 非空
2. 解析 sessionId
3. 校验 cookie userId 对该 session 有访问权
4. 校验 session 未关闭
5. 非默认/虚拟助手场景检查 assistant 是否已删除
6. 保存 user message
7. 立即向 miniapp 推送 message.user
8. 根据 scope strategy 路由到 Gateway
9. GW 回流 tool_event / tool_done / tool_error
10. SS 翻译成 StreamMessage
11. 通过 Redis user stream 推给 /ws/skill/stream
```

发送到 GW 的 chat payload 主要包含：

```text
text
toolSessionId
sendUserAccount
assistantAccount
messageId
businessExtParam
```

其中 `sendUserAccount` 来自：

```text
cookie userId 优先
  -> session.userId 兜底
```

## action: question_reply

入口仍是：

```text
POST /api/skill/sessions/{sessionId}/messages
```

当请求携带 `toolCallId` 时，表示 question reply。

请求字段：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `content` | 必填 | 用户答案 |
| `toolCallId` | 必填 | 对应 question/tool call |
| `questionId` | 可选 | personal scope 快路径可透传给 plugin |
| `subagentSessionId` | 可选 | 存在时优先作为目标 `toolSessionId` |
| `businessExtParam` | 可选 | 透传到云端协议扩展参数 |

处理规则：

```text
1. 保存用户回复消息
2. 判断 action=QUESTION_REPLY
3. targetToolSessionId = subagentSessionId 或 session.toolSessionId
4. 构造 question_reply payload
5. 发送到 Gateway
6. 记录 question reply 历史
```

重要约束：

- `question_reply` 依赖已有 session。
- session 关闭时返回 `409`。
- 缺 `toolSessionId` 时当前 `/messages` 路由会触发 rebuild。
- 云端 agent 下，`question_reply` 会按对应 cloud profile 构造 `replyContext`。

## action: permission_reply

入口：

```text
POST /api/skill/sessions/{sessionId}/permissions/{permId}
```

请求字段：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `response` | 必填 | 只能是 `once` / `always` / `reject` |
| `subagentSessionId` | 可选 | 存在时优先作为目标 `toolSessionId` |
| `businessExtParam` | 可选 | 透传到云端协议扩展参数 |

处理规则：

```text
1. 校验 response 合法
2. 解析 sessionId
3. 校验 cookie userId 对该 session 有访问权
4. 校验 session 未关闭
5. 校验 session 有助手身份
6. 根据 scope strategy 执行必要的在线检查
7. 要求 session.toolSessionId 非空
8. targetToolSessionId = subagentSessionId 或 session.toolSessionId
9. 构造 GatewayActions.PERMISSION_REPLY
10. 发送到 Gateway
11. 本地发布 permission.reply StreamMessage
12. 记录 permission reply 历史
```

重要约束：

- `response` 只能是 `once`、`always`、`reject`。
- `permission_reply` 必须更新 miniapp 当前权限卡状态。
- 云端 agent 下，`permission_reply` 会按对应 cloud profile 构造 `replyContext`。

## toolSessionId 自动恢复

miniapp 也具备 `toolSessionId` 自动恢复能力。

触发条件：

```text
POST /messages
  -> session 存在
  -> session.toolSessionId 为空
```

恢复流程：

```text
1. 当前消息先保存为用户消息
2. routeToGateway 发现无 toolSessionId
3. 调用 rebuildToolSession
4. 缓存 pending message
5. 发送 create_session 到 Gateway
6. Gateway 回 session_created
7. SS 绑定新 toolSessionId
8. retry pending message
```

业务语义：

- 自动恢复是 chat/question reply 入站时的隐式会话维护能力。
- 它不是 miniapp 前端显式 action。
- 用于内部 agent 会话断裂时补回链路，避免用户消息直接丢失。

## close session

入口：

```text
DELETE /api/skill/sessions/{sessionId}
```

处理规则：

```text
1. 解析 sessionId
2. 校验 cookie userId 访问权
3. 如果应发送 lifecycle invoke，则发 close_session 到 Gateway
4. 标记 SkillSession 为 CLOSED
```

约束：

- 普通助手存在 `toolSessionId` 时可发送 `close_session`。
- 默认/虚拟助手命中规则的会话当前不发送 close lifecycle invoke。

## abort session

入口：

```text
POST /api/skill/sessions/{sessionId}/abort
```

处理规则：

```text
1. 解析 sessionId
2. 校验 cookie userId 访问权
3. session 已关闭时返回 409
4. 如果存在助手身份和 toolSessionId，则发送 abort_session 到 Gateway
5. 本地把 streaming buffer 收尾
6. 持久化可收尾的 final part
7. 标记 session idle
8. 清理 buffer
```

业务语义：

- abort 停止当前轮次，但会话保留可复用。
- miniapp 前端仍可继续在同一 session 里发下一条消息。

## 下行实时回复

miniapp 下行不走 externalWs。

链路：

```text
Gateway 上行事件
  -> GatewayMessageRouter
  -> scope strategy translateEvent
  -> StreamMessageEmitter
  -> MiniappDeliveryStrategy
  -> Redis publishToUser(userId)
  -> SkillStreamHandler 订阅 user stream
  -> /ws/skill/stream
  -> miniapp 前端
```

路由关键：

```text
sessionId -> session.userId
userId -> WebSocket subscribers
```

WebSocket 行为：

- 连接时从 cookie 解析 `userId`。
- 每个用户一组订阅者。
- 新连接会发送当前活跃 session 的 streaming state。
- 客户端可发 `ping` 保活。
- 客户端可发 `resume` 恢复指定 session 的 streaming state。

## send-to-im 旁路

入口：

```text
POST /api/skill/sessions/{sessionId}/send-to-im
```

用途：

- miniapp 内选中文本后，发送回关联 IM 会话。

处理规则：

```text
1. 校验 content 非空且长度不超过限制
2. 校验 cookie userId 对 session 有访问权
3. 从 session.businessSessionId 解析 IM 目标和发送人
4. 校验 cookie userId 与解析出的 senderAccount 一致
5. 调用 IM 出站服务发送文本
```

边界：

- 它不是 miniapp 主对话链路。
- 请求体只包含 `content`。
- 目标 IM 会话和发送人由后端从 session 上下文解析。

## 错误语义

| 场景 | 返回 |
| --- | --- |
| 缺 cookie `userId` | `400` |
| sessionId 非法 | `400` |
| 创建会话缺 `ak/assistantAccount` 且未命中默认助手规则 | `400` |
| 发送消息缺 `content` | `400` |
| permission response 非法 | `400` |
| session 已关闭后继续发送消息或 permission reply | `409` |
| 当前用户无 session 访问权 | `403` |
| assistant 明确不存在 | `410` |
| 本地 agent 离线 | `503` |
| permission reply 缺 `toolSessionId` | `500` |

## 业务不变量

- miniapp 不走 external REST。
- miniapp 不走 externalWs。
- miniapp 的主会话唯一性是 `userId + SkillSession.id`。
- miniapp 的实时下行按 `userId` 投递。
- `GET /api/skill/sessions/{sessionId}/messages/history` 是正式历史查询入口。
- `GET /api/skill/sessions/{sessionId}/messages` page/size 接口可废弃，不进入主业务流程。
- miniapp 支持本地 agent、云端 agent、虚拟 agent。
- 虚拟 agent 当前只在 miniapp 对话中使用。
- 云端 agent 必须继续区分标准协议助手和助手广场协议助手。
- 本地 agent 的 `toolSessionId` 来自 `session_created`。
- 云端 agent 和虚拟 agent 的 `toolSessionId` 由 SS 预生成。
- `permission_reply` 除了通知 Gateway，还必须更新本地权限卡状态并记录历史。
- WebSocket `resume` 恢复 streaming 状态，不替代历史查询。
- `send-to-im` 是 miniapp 到 IM 的旁路动作，不是对话主链路。

## 代码证据

- `SkillSessionController`：miniapp 会话创建、列表、单会话查询、close、abort。
- `SkillMessageController`：miniapp 消息发送、cursor history、permission reply、send-to-im。
- `SkillMessageFlowService`：chat、question reply、permission reply 的业务路由。
- `SkillSessionFlowService`：创建会话、默认助手规则、toolSessionId 生成、close/abort lifecycle。
- `AssistantScopeDispatcher`：按 `(domain, domainType, AssistantInfo)` 选择 personal、business、default assistant strategy。
- `PersonalScopeStrategy`：本地 agent scope，等待 `session_created`，需要在线检查。
- `BusinessScopeStrategy`：云端 agent scope，预生成 `toolSessionId`，按 cloud profile 构造请求。
- `DefaultAssistantScopeStrategy`：虚拟/默认助手 scope，按默认助手规则注入云端请求。
- `CloudRequestProfileRegistry`：解析 `cloudProfile=default` / `assistant_square`。
- `DefaultCloudRequestStrategy`：标准协议助手请求构造。
- `AssistantSquareCloudRequestStrategy`：助手广场协议助手请求构造。
- `MiniappDeliveryStrategy`：miniapp 按 userId 投递到 Redis user stream。
- `SkillStreamHandler`：`/ws/skill/stream` 连接、订阅、resume 和实时推送。
