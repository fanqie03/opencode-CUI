# external / IM 群聊业务场景

## 场景边界

external IM 群聊是外部 IM 系统通过 external REST 接入 SS+GW 的群聊场景。

它不属于 miniapp，也不走旧的 `imInbound` 入口。外部 IM 系统已经在入站前完成群聊触发判断，例如是否 @ 机器人、是否命中特定助手、是否需要忽略该群消息。对 SS 来说，只要 external REST 已经进入，就代表这条群聊消息应该被处理。

当前该场景只承载本地 agent 和云端 agent；虚拟 agent 暂时只在 miniapp 对话中使用。云端 agent 继续细分为：

```text
云端 agent
  -> 标准协议助理
  -> 助手广场协议助理
```

整体链路：

```text
外部 IM 群
  -> 外部 IM 触发过滤
  -> Skill Server external REST
  -> Skill Server 群聊上下文注入、会话和 toolSessionId 管理
  -> AI Gateway
  -> local agent / cloud agent（标准协议 / 助手广场协议）
  -> AI Gateway 回流
  -> Skill Server 出站投递
  -> externalWs
  -> 外部 IM 群
```

## 参与方

| 参与方 | 职责 |
| --- | --- |
| 外部 IM 系统 | 判断群消息是否触发助手，发起群聊消息、交互回复、主动 rebuild，并接收 externalWs 回推 |
| Skill Server | 承接 external REST，注入群聊上下文，维护 SkillSession/toolSessionId，向 Gateway 投递 invoke，负责 externalWs 出站 |
| AI Gateway | 承接 SS invoke，路由到本地 agent 或云端 agent，并把 agent 事件回流给 SS |
| local agent / cloud agent | 实际处理群消息、问题回复、权限回复 |
| externalWs 客户端 | 由外部系统建连，注册 `source`，接收 SS 回推 |

## 核心标识

| 概念 | 字段 | 业务含义 |
| --- | --- | --- |
| 业务域 | `businessDomain` | external REST 入口的来源域；同时是 externalWs 出站 source 匹配键 |
| 会话类型 | `sessionType=group` | 表示 IM 群聊 |
| 业务会话 | `sessionId` | 外部 IM 群 ID |
| 助手账号 | `assistantAccount` | 外部侧选择的助手账号 |
| 发送人 | `senderUserAccount` | 本次群消息的真实发言人，信封层必填 |
| 群 ID 下游字段 | `imGroupId` | group 场景下等于 `sessionId` |
| SS 内部会话 | `welinkSessionId` / `SkillSession.id` | SS 内部持久化会话 ID |
| agent 会话 | `toolSessionId` | SS/GW/agent 之间的工具会话 ID |

业务上可以把 external IM 群聊的唯一性理解为：

```text
assistantAccount + sessionId
```

代码查找时会带上更完整的边界：

```text
businessDomain + sessionType=group + sessionId + assistant identity
```

群聊有一个关键不变量：

```text
SkillSession.userId = null
```

因此不能从 `SkillSession.userId` 推断发送人。所有 chat、pending、retry、rebuild 相关路径都必须保留真实 `senderUserAccount`。

## 助手类型与云端协议子类型

| 助手类型 | 当前群聊是否支持 | 说明 |
| --- | --- | --- |
| 本地 agent | 是 | 通过 GW 路由到本地插件/agent |
| 云端 agent：标准协议助理 | 是 | `cloudProfile=default`，请求走标准云端协议 |
| 云端 agent：助手广场协议助理 | 是 | `cloudProfile=assistant_square`，请求/响应走助手广场协议适配 |
| 虚拟 agent | 否 | 暂时只在 miniapp 对话 |

云端 agent 的协议选择在 SS/GW 之间通过 `cloudProfile` 串联：

```text
SS 解析 assistant
  -> BusinessScopeStrategy / DefaultAssistantScopeStrategy
  -> CloudRequestProfileRegistry 解析 cloud profile
  -> 选择 CloudRequestStrategy 构造 cloudRequest
  -> payload.cloudProfile 下发给 GW
  -> GW CloudResponseProfileRegistry 选择 response decoder
```

profile 来源优先级：

```text
AssistantInfo.cloudProfile 显式配置
  -> cloud_protocol_profile:<businessTag> SysConfig 映射
  -> default
```

两类云端协议的业务差异：

| 云端协议子类型 | SS 请求构造 | GW 响应解码 | 群聊关键字段 |
| --- | --- | --- | --- |
| 标准协议助理 | `DefaultCloudRequestStrategy` | `DefaultSseEventDecoder` | `content`、`sendUserAccount`、`imGroupId`、`topicId`、`replyContext` |
| 助手广场协议助理 | `AssistantSquareCloudRequestStrategy` | `AssistantSquareSseEventDecoder` | `msgBody`、`sendW3Account`、`imGroupId`、`topicId`、`replyContext` |

命名注意：助手广场响应内部还有 `protocolType=standard` 的子 handler，这是助手广场 decoder 的内部派系，不等同于外层的“标准协议助理”。

## 统一入口

REST 入口：

```text
POST /api/external/invoke
```

信封字段：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `action` | 必填 | `chat` / `question_reply` / `permission_reply` / `rebuild` |
| `businessDomain` | 必填 | 业务域和 externalWs source 路由键 |
| `sessionType` | 必填 | 群聊固定为 `group` |
| `sessionId` | 必填 | 外部 IM 群 ID |
| `assistantAccount` | 必填 | 助手账号 |
| `senderUserAccount` | 必填 | 真实群成员发言人账号 |
| `businessExtParam` | 可选 | 业务扩展参数，透传到下游云端协议 |

## action: chat

`chat` 是外部 IM 已判定需要触发助手的群消息。

主要流程：

```text
1. 外部 IM 完成群消息触发过滤
2. external REST 校验信封和 payload.content
3. 解析助手身份
4. 对 group 场景注入 chatHistory 上下文
5. 按 businessDomain + group + sessionId + assistant identity 查 SkillSession
6. 根据 session/toolSessionId 状态分支处理
7. session ready 后构造 GatewayActions.CHAT
8. 通过 GatewayRelayService 发给 AI Gateway
9. 记录 invokeSource=EXTERNAL，用于 ws mode 下的出站策略选择
```

分支：

| 分支 | 条件 | 行为 |
| --- | --- | --- |
| 创建会话 | session 不存在 | `createSessionAsync` 异步创建 group session，群聊 session 的 `userId` 保持 null |
| 自动恢复 | session 存在但 `toolSessionId` 不 ready | 尝试自愈；失败则降级到 requestToolSession/rebuild 路径，并保留当前群消息 pending |
| 正常投递 | session 存在且 `toolSessionId` ready | 发送 `chat` invoke 到 Gateway，不按 direct 单聊方式保存用户消息 |

## 群聊上下文注入

群聊可以由 external REST payload 携带 `chatHistory`。SS 只在 `sessionType=group` 且上下文注入开启时，把最近的群聊历史按模板合入当前 prompt。

```text
chatHistory + 当前群消息
  -> ContextInjectionService
  -> group-chat-prompt template
  -> 最终 prompt
```

单聊不做这个注入。

## 下游群聊字段

群聊发给 GW/agent 的 chat payload 会带：

```text
text
toolSessionId
assistantAccount
sendUserAccount
imGroupId = sessionId
messageId
businessExtParam
```

同时 `InvokeCommand` 会带业务会话三字段：

```text
domain = businessDomain
domainType = group
businessSessionId = sessionId
```

云端协议里会进入：

```text
extParameters.platformExtParam.businessSessionDomain
extParameters.platformExtParam.businessSessionType
extParameters.platformExtParam.businessSessionId
```

因此群 ID 当前有两个并存位置：

```text
payload.imGroupId
extParameters.platformExtParam.businessSessionId
```

这是当前协议兼容期的命名冗余约定。

## suppressReply 群聊专属分支

群聊 chat 可能设置 `suppressReply=true`：

```text
sessionType=group
  -> 通过 ak 查询 plugin toolType/channel
  -> 如果命中 suppress whitelist
  -> InvokeCommand.suppressReply = true
```

单聊无论是否命中 channel，都不设置 `suppressReply`。群聊 pending retry 时也会重新按 group session 和 channel whitelist 计算 `suppressReply`。

## toolSessionId 自动恢复

自动恢复不是独立 action，而是 `chat` 入站时的隐式会话维护能力。

触发条件：

```text
SkillSession 存在
但 toolSessionId 为空或未就绪
```

恢复路径：

```text
1. 根据业务域、会话类型和助手信息选择 scope strategy
2. 如果 strategy 能生成 toolSessionId，则进入 business/self-heal 路径
3. 通过 Redis 分布式锁避免并发重复恢复
4. 二次读取 DB，避免覆盖其他实例已恢复的 toolSessionId
5. 生成并持久化新的 toolSessionId
6. 恢复成功后继续投递当前 chat
```

失败或不支持直接生成时：

```text
1. 构造 PendingChatRequest 保住当前群消息
2. pending.sendUserAccount = 真实发言人
3. pending.imGroupId = 群 ID
4. 调用 requestToolSession 走 rebuild 链路
5. 等 tool session 恢复后 retry pending 消息
```

## action: question_reply

`question_reply` 是群聊用户回答助手发出的 question 卡片。

payload 要求：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `content` | 必填 | 用户答案 |
| `toolCallId` | 必填 | 对应 question/tool call |
| `subagentSessionId` | 可选 | 存在时优先作为目标 toolSessionId |

处理规则：

```text
1. 查找已有 group SkillSession
2. 要求 session 存在且 toolSessionId ready
3. 检查 agent 在线状态
4. 构造 GatewayActions.QUESTION_REPLY
5. payload.imGroupId = 群 ID
6. 发送到 Gateway
7. 记录 question reply 历史
8. 记录 invokeSource=EXTERNAL
```

重要约束：

- `question_reply` 不创建新 session。
- `question_reply` 不触发 toolSessionId 自动恢复。
- session 不存在或未 ready 时返回 `404`。
- 云端 agent 下，`question_reply` 必须按对应 cloud profile 构造云端请求；Gateway 侧要求 reply action 走 webhook callback。

## action: permission_reply

`permission_reply` 是群聊用户回答权限卡片。

payload 要求：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `permissionId` | 必填 | 对应 permission ask |
| `response` | 必填 | 只能是 `once` / `always` / `reject` |
| `subagentSessionId` | 可选 | 存在时优先作为目标 toolSessionId |

处理规则：

```text
1. 查找已有 group SkillSession
2. 要求 session 存在且 toolSessionId ready
3. 检查 agent 在线状态
4. 构造 GatewayActions.PERMISSION_REPLY
5. payload.imGroupId = 群 ID
6. 发送到 Gateway
7. 在 SS 内部发布 permission.reply 协议消息
8. 更新/缓冲权限卡状态
9. 记录 permission reply 历史
10. 记录 invokeSource=EXTERNAL
```

重要约束：

- `permission_reply` 不创建新 session。
- `permission_reply` 不触发 toolSessionId 自动恢复。
- session 不存在或未 ready 时返回 `404`。
- `permission_reply` 必须更新当前会话里的权限卡状态。
- 云端 agent 下，`permission_reply` 必须按对应 cloud profile 构造云端请求；Gateway 侧要求 reply action 走 webhook callback。

## action: rebuild

`rebuild` 是外部系统主动要求 SS 重建或刷新群聊会话链路。

处理分支：

| 条件 | 行为 |
| --- | --- |
| session 不存在 | `createSessionAsync` 异步创建 group session |
| session 存在且当前 strategy 能生成 `toolSessionId` | 加锁后生成新的 `toolSessionId` 并更新 session |
| session 存在但不能直接生成 `toolSessionId` | 调用 `requestToolSession`，让 Gateway/agent 侧重新创建 tool session |

主动 rebuild 和自动恢复的区别：

| 类型 | 触发方 | 触发时机 |
| --- | --- | --- |
| 主动 rebuild | 外部系统显式发送 `action=rebuild` | 外部系统判断需要重建群聊会话 |
| 自动恢复 | SS 内部触发 | `chat` 入站时发现 `toolSessionId` 缺失 |

## 出站回推

external 群聊的出站不是按 `sessionId` 或 `imGroupId` 查 websocket，而是按入口 `businessDomain` 匹配 externalWs 的 `source`。

本实例持有连接时：

```text
SkillSession.businessSessionDomain
  -> ExternalWsDeliveryStrategy
  -> ExternalStreamHandler.pushToOne(domain, payload)
  -> connectionPool[source=domain]
  -> externalWs 客户端
```

本实例没有连接时：

```text
SkillSession.businessSessionDomain
  -> ExternalWsRegistry.findInstancesWithConnection(domain)
  -> Redis relay 到持有连接的 SS 实例
  -> 远端 SS pushToOne(domain, payload)
  -> externalWs 客户端
```

因此外部系统必须保证：

```text
REST 入站 businessDomain
和
externalWs 握手 source
使用同一个值
```

## 错误语义

| 场景 | 返回 |
| --- | --- |
| 信封缺必填字段 | `400` |
| `chat` 缺 `payload.content` | `400` |
| `question_reply` 缺 `content` 或 `toolCallId` | `400` |
| `permission_reply` 缺 `permissionId` 或 response 非法 | `400` |
| assistant 明确不存在 | `410` |
| assistant 状态未知或无效 | `404` |
| reply 类 action 找不到 ready session | `404` |
| agent 离线 | `503` |

## 业务不变量

- external IM 群聊不走 `imInbound`。
- external IM 群聊当前不承载虚拟 agent。
- external IM 系统负责群聊触发过滤；SS 不判断是否 @ 机器人或是否应该忽略该群消息。
- external IM 群聊支持本地 agent 和云端 agent；云端 agent 必须继续区分标准协议助理和助手广场协议助理。
- `cloudProfile` 是 SS 请求构造和 GW 响应解码之间的协议衔接字段。
- `businessDomain` 是 external 群聊的业务域，也是 externalWs 出站 source 路由键。
- `sessionType` 必须是 `group`。
- `senderUserAccount` 是信封层必填字段，必须是真实群成员发言人。
- group session 的 `userId` 保持 null，不能用于 sender fallback。
- group 场景下 `imGroupId = sessionId`。
- `question_reply` 和 `permission_reply` 只能作用于已存在且 ready 的 session。
- `permission_reply` 除了通知 Gateway，还必须更新本地权限卡状态。
- `chat` 是唯一会触发当前消息处理和 toolSessionId 自动恢复的用户消息 action。
- 主动 `rebuild` 是会话维护动作，不等同于普通群消息。

## 代码证据

- `ExternalInboundController`：external REST 入口、action 和 `sessionType=group` 校验。
- `InboundProcessingService`：`chat`、`question_reply`、`permission_reply`、`rebuild` 主处理逻辑，包含 `imGroupId` 和 `suppressReply`。
- `ContextInjectionService`：仅 group 场景注入 `chatHistory`。
- `ImSessionManager`：group session `userId` 为空，pending 保留真实 sender。
- `PendingChatRequest`：group 场景禁止从 `session.userId` 推断 sender。
- `ExternalWsDeliveryStrategy`：按 `SkillSession.businessSessionDomain` 做 externalWs 出站。
- `CloudRequestProfileRegistry`：SS 侧按 assistant/profile 选择云端请求协议。
- `DefaultCloudRequestStrategy`：标准协议助理请求构造。
- `AssistantSquareCloudRequestStrategy`：助手广场协议助理请求构造。
- `CloudResponseProfileRegistry`：GW 侧按 `cloudProfile` 选择响应 decoder。
