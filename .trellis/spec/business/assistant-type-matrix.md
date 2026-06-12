# 助手类型矩阵业务场景

## 场景边界

助手类型矩阵是横跨 external IM 单聊、external IM 群聊、miniapp 对话的业务视图。它不定义新的聊天入口，而是回答同一个入口场景遇到不同助手类型时，SS+GW 应该如何分流、如何获取 `toolSessionId`、如何构造 invoke、以及如何处理下行和 reply。

当前聊天入口只有：

```text
external / IM 单聊
external / IM 群聊
miniapp 对话
```

当前助手类型只有：

```text
本地 agent
云端 agent
虚拟 agent
```

其中云端 agent 继续细分为：

```text
云端 agent
  -> 标准协议助手
  -> 助手广场协议助手
```

## 核心原则

```text
助手类型决定 invoke 构造和 toolSessionId 生命周期；
业务场景决定出站投递通道和会话唯一性。
```

这意味着：

- 不应该按“云端 agent”或“本地 agent”决定回 externalWs 还是 miniapp WS。
- external 单聊/群聊的下行由 external 场景决定。
- miniapp 的下行由 miniapp 场景决定。
- 助手类型只影响 `scope strategy`、`toolSessionId` 生成/绑定方式、云端协议 profile、reply payload 结构。

## 总体矩阵

| 场景 | 本地 agent | 云端 agent：标准协议 | 云端 agent：助手广场协议 | 虚拟 agent |
| --- | --- | --- | --- | --- |
| external IM 单聊 | 支持 | 支持 | 支持 | 不支持 |
| external IM 群聊 | 支持 | 支持 | 支持 | 不支持 |
| miniapp 对话 | 支持 | 支持 | 支持 | 支持 |

## 本地 agent

本地 agent 的业务含义是：用户对话最终由在线的本地插件 agent 处理。

代码策略：

```text
scope = personal
strategy = PersonalScopeStrategy
```

适用场景：

| 场景 | 是否支持 |
| --- | --- |
| external IM 单聊 | 支持 |
| external IM 群聊 | 支持 |
| miniapp 对话 | 支持 |

生命周期：

```text
创建 SkillSession
  -> 发送 create_session 到 Gateway
  -> Gateway 路由到本地 agent
  -> agent 创建工具会话
  -> Gateway 回 session_created
  -> SS 绑定 toolSessionId
  -> 后续 chat / reply 使用该 toolSessionId
```

关键特征：

- `toolSessionId` 不由 SS 预生成。
- 必须等待 `session_created`。
- 需要本地 agent 在线检查。
- `requiresSessionCreatedCallback=true`。
- `requiresOnlineCheck=true`。
- chat 时如果发现 `toolSessionId` 缺失，会走 rebuild / requestToolSession。
- 事件翻译默认按 OpenCode 协议处理，也能根据事件 `protocol=cloud` 分派到 cloud translator。

在三个入口里的差异：

| 场景 | 业务差异 |
| --- | --- |
| external IM 单聊 | 会话唯一性按 `assistantAccount + sessionId` 理解；下行走 externalWs |
| external IM 群聊 | 需要保留真实 `senderUserAccount`；`imGroupId=sessionId`；可注入 `chatHistory` |
| miniapp 对话 | 会话归属按 cookie `userId`；下行走 `/ws/skill/stream` |

## 云端 agent

云端 agent 的业务含义是：用户对话最终由云端助手服务处理，不依赖本地 agent 在线。

代码策略：

```text
scope = business
strategy = BusinessScopeStrategy
```

适用场景：

| 场景 | 是否支持 |
| --- | --- |
| external IM 单聊 | 支持 |
| external IM 群聊 | 支持 |
| miniapp 对话 | 支持 |

生命周期：

```text
创建 SkillSession
  -> BusinessScopeStrategy 预生成 toolSessionId
  -> 不等待 session_created
  -> chat / reply 时构造 cloudRequest
  -> payload.cloudProfile 下发给 Gateway
  -> Gateway 按 cloudProfile 选择云端响应 decoder
```

关键特征：

- `toolSessionId` 由 SS 预生成。
- 不依赖 `session_created`。
- 不做本地 agent 在线检查。
- `requiresSessionCreatedCallback=false`。
- `requiresOnlineCheck=false`。
- 依赖 `assistantAccount`、`sendUserAccount` 等账号字段完整。
- `businessExtParam` 和 `platformExtParam` 会进入云端扩展参数。

### 云端协议子类型

| 云端协议子类型 | profile | SS 请求策略 | GW 响应 decoder | 典型字段 |
| --- | --- | --- | --- | --- |
| 标准协议助手 | `default` | `DefaultCloudRequestStrategy` | `DefaultSseEventDecoder` | `content`、`sendUserAccount`、`assistantAccount`、`topicId` |
| 助手广场协议助手 | `assistant_square` | `AssistantSquareCloudRequestStrategy` | `AssistantSquareSseEventDecoder` | `msgBody`、`sendW3Account`、`assistantAccount`、`topicId` |

profile 来源优先级：

```text
AssistantInfo.cloudProfile
  -> cloud_protocol_profile:<businessTag>
  -> default
```

命名注意：

- “标准协议助手”指外层 `cloudProfile=default`。
- 助手广场响应内部可能还有 `protocolType=standard` 的 handler，这是助手广场 decoder 内部派系，不等同于外层标准协议助手。

在三个入口里的差异：

| 场景 | 业务差异 |
| --- | --- |
| external IM 单聊 | `sendUserAccount=senderUserAccount`，通常无 `imGroupId` |
| external IM 群聊 | `sendUserAccount=真实群成员`，`imGroupId=sessionId` |
| miniapp 对话 | `sendUserAccount=cookie userId`，通常无 `imGroupId` |

## 虚拟 agent

虚拟 agent 的业务含义是：用户没有显式选择真实助手时，SS 按业务域和类型命中默认助手规则，注入一个虚拟助手身份。

代码策略：

```text
scope = default_assistant
strategy = DefaultAssistantScopeStrategy
```

适用场景：

| 场景 | 是否支持 |
| --- | --- |
| external IM 单聊 | 不支持 |
| external IM 群聊 | 不支持 |
| miniapp 对话 | 支持 |

命中方式：

```text
businessSessionDomain + businessSessionType
  -> default_assistant_rule
  -> ak / assistantAccount / businessTag
```

生命周期：

```text
创建 miniapp session
  -> 未传真实 ak / assistantAccount
  -> 查 default_assistant_rule
  -> 注入虚拟 ak / assistantAccount / businessTag
  -> DefaultAssistantScopeStrategy 预生成 toolSessionId
  -> 后续按 cloud request 进入 Gateway
```

关键特征：

- 只在 miniapp 使用。
- 不要求用户传真实助手身份。
- 不走本地 agent 在线检查。
- 不等待 `session_created`。
- wire 上仍写 `assistantScope=business`，让 Gateway 进入云端路径。
- 使用 `businessTag` 解析 cloud profile。
- 下行仍回 miniapp WS。
- 删除检查会跳过默认助手规则命中的会话。

虚拟 agent 和云端 agent 的实现路径很像，但业务语义不同：

```text
云端 agent = 真实助手配置
虚拟 agent = 默认规则注入的助手身份
```

## toolSessionId 获取方式矩阵

| 助手类型 | 获取方式 | 是否等 `session_created` | 是否需要在线检查 |
| --- | --- | --- | --- |
| 本地 agent | GW/agent 回调绑定 | 是 | 是 |
| 云端 agent | SS 预生成 | 否 | 否 |
| 虚拟 agent | SS 预生成 | 否 | 否 |

业务含义：

- 本地 agent 的工具会话真实存在于本地 agent 侧，因此 SS 不能预先假定 `toolSessionId`。
- 云端 agent 和虚拟 agent 的 `toolSessionId` 是云端 topic/session 标识，SS 可以预生成并下发。

## 下行投递矩阵

助手类型不直接决定下行通道，业务场景决定下行通道。

| 场景 | 下行通道 | 路由依据 |
| --- | --- | --- |
| external IM 单聊 | externalWs | `businessDomain == externalWs source` |
| external IM 群聊 | externalWs | `businessDomain == externalWs source` |
| miniapp 对话 | `/ws/skill/stream` | `sessionId -> userId -> WebSocket subscribers` |

因此不应描述为“云端 agent 走 externalWs”或“本地 agent 走 miniapp WS”。正确描述是：

```text
external 场景走 externalWs；
miniapp 场景走 miniapp WS；
助手类型只改变 Gateway invoke 和事件翻译。
```

## reply 行为矩阵

| 助手类型 | question reply | permission reply |
| --- | --- | --- |
| 本地 agent | 发 `QUESTION_REPLY` 给 GW/agent，可带 `questionId` 快路径 | 发 `PERMISSION_REPLY` 给 GW/agent，并更新本地权限卡 |
| 云端 agent | 构造成 cloud `replyContext.type=question_reply` | 构造成 cloud `replyContext.type=permission_reply` |
| 虚拟 agent | 同云端路径，由 default assistant strategy 构造 | 同云端路径，由 default assistant strategy 构造 |

场景差异：

- external 场景里，reply 作用于已有 ready session，不负责创建新 session。
- miniapp `/messages` 场景里，如果缺 `toolSessionId`，当前路由会进入 rebuild 逻辑。
- `subagentSessionId` 存在时优先作为 reply 目标 `toolSessionId`。

## 业务不变量

- 聊天入口场景只有 external 单聊、external 群聊、miniapp。
- 助手类型只有本地 agent、云端 agent、虚拟 agent。
- 虚拟 agent 只在 miniapp。
- 云端 agent 必须继续区分标准协议助手和助手广场协议助手。
- `toolSessionId` 获取方式由助手类型决定。
- 下行投递通道由业务场景决定。
- external 的业务唯一性按 `assistantAccount + sessionId` 理解。
- miniapp 的业务唯一性按 `userId + SkillSession.id` 理解。
- 群聊 sender 只能来自 `senderUserAccount`，不能从 session `userId` 推断。
- `cloudProfile` 是 SS 请求构造和 GW 响应解码之间的协议衔接字段。

## 代码证据

- `AssistantScopeDispatcher`：按 assistant scope 和默认助手规则选择策略。
- `PersonalScopeStrategy`：本地 agent 策略，等待 `session_created` 并要求在线检查。
- `BusinessScopeStrategy`：云端 agent 策略，预生成 `toolSessionId` 并构造 cloud request。
- `DefaultAssistantScopeStrategy`：虚拟/默认助手策略，按规则注入云端请求。
- `CloudRequestProfileRegistry`：解析 `default` 和 `assistant_square` profile。
- `DefaultCloudRequestStrategy`：标准协议助手请求构造。
- `AssistantSquareCloudRequestStrategy`：助手广场协议助手请求构造。
- `GatewayRelayService`：按 scope strategy 构造并发送 invoke。
- `GatewayMessageRouter`：按上行事件翻译并路由到对应业务场景下行通道。
