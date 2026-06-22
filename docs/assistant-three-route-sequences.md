 # 三类助理链路时序图

本文用于梳理后续改造目标：入口先分流为个人助理、云端助理、默认助理，再由不同策略处理会话、invoke、回流和错误恢复。

## 约定

- `assistantAccount` / `partnerAccount` 是真实分身或助理账号身份。
- `ak` 不是入口必填字段。个人助理链路最终需要解析出 `resolvedAk` 才能路由到本地 Agent。
- 云端助理 `ak` 可为空。云端路由以 `assistantAccount` 为主，GW 自己查询实例接口拿远端配置。
- 默认助理由 `(businessSessionDomain, businessSessionType)` 命中默认规则，本质走云端语义，但保留 virtual AK + callback config 兼容路径。
- `cloudProfile` 继续由 `businessTag` / `bizRobotTag` 决定，不使用 `dataProtocol` 选择 profile。
- 云端助理和默认助理没有会话重建概念，不走 `create_session` / `session_created` / pending replay。
- 图中的 `RoutePlan` 是建议新增的入口分流结果，不是当前已有对象。

## 0. 入口分流总览

### 0.1 双向复用架构

```mermaid
flowchart TD
  U["User Request"] --> CA["Channel Adapter\nExternal / Miniapp"]
  CA --> CMD["AssistantInteractionCommand"]
  CMD --> ORCH["AssistantInteractionService"]
  ORCH --> RR["AssistantRouteResolver"]
  RR --> PLAN["AssistantRoutePlan"]
  PLAN --> FS["AssistantFlowStrategy\nPersonal / Remote / Default"]
  FS --> GW["ai-gateway"]

  GW --> EVT["AssistantEventEnvelope"]
  EVT --> EH["AssistantEventHandlingService"]
  EH --> CTX["SessionRouteContext"]
  CTX --> ES["AssistantEventStrategy\nPersonal / Remote / Default"]
  ES --> SM["StreamMessage"]
  SM --> OD["OutboundDeliveryDispatcher"]
  OD --> C["Miniapp / External / IM"]
```

职责边界：

- `Channel Adapter` 只处理入口差异，把 External / Miniapp 请求转换为统一 `AssistantInteractionCommand`。
- `AssistantRouteResolver` 只负责一次性定性：`PERSONAL`、`REMOTE`、`DEFAULT`。
- `AssistantFlowStrategy` 负责 user -> agent：会话准备、toolSessionId、online check、rebuild 语义、invoke 构造。
- `AssistantEventStrategy` 负责 agent -> user：事件翻译、`session_created`、错误恢复、是否允许 rebuild。
- `OutboundDeliveryDispatcher` 负责最终投递：Miniapp WS、External WS、IM REST。

核心原则：

- External 和 Miniapp 不各自实现助理分流、会话生命周期和 invoke 构造；它们只做入口适配。
- 三类助理的差异不散落在 Controller、SessionManager、GatewayRelay 和 Router 中，而是集中到 FlowStrategy / EventStrategy。
- 正向和反向都基于同一个 `RoutePlan` / `SessionRouteContext` 判断助理类型，避免每一步重复用 `ak`、`businessTag`、`toolSessionId` 猜类型。

### 0.2 分流判定

```mermaid
flowchart TD
  A["入口请求\nExternal / Miniapp"] --> B["AssistantRouteResolver"]
  B --> C{"命中默认规则?\n(domain,type)"}
  C -- "是" --> D["RoutePlan: DEFAULT\nassistantAccount from rule\nak optional/virtual\nbusinessTag from rule"]
  C -- "否" --> E{"有 assistantAccount?"}
  E -- "是" --> F["查询实例信息\npartnerAccount=assistantAccount"]
  F --> G{"isRemote=true?"}
  G -- "是" --> H["RoutePlan: REMOTE\nassistantAccount required\nak optional\nbizRobotTag -> cloudProfile"]
  G -- "否" --> I["RoutePlan: PERSONAL\nresolvedAk required\nownerWelinkId"]
  E -- "否, 但有 ak" --> J["兼容解析 AK"]
  J --> I
  E -- "否, 且无 ak" --> K["400: identity missing"]
```

## 1. 三条主链路

### 1.1 个人助理链路

```mermaid
sequenceDiagram
  autonumber
  participant Client as Client
  participant SS as skill-server
  participant Resolver as AssistantRouteResolver
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Agent as Local Agent Plugin

  Client->>SS: chat / create session\nak optional, assistantAccount optional
  SS->>Resolver: resolve(input)
  Resolver-->>SS: RoutePlan(PERSONAL, resolvedAk, ownerWelinkId)
  SS->>DB: find/create session by business key + assistant identity
  alt first chat or no toolSessionId
    SS->>GW: invoke create_session\nak=resolvedAk, welinkSessionId
    GW->>Agent: create_session
    Agent-->>GW: session_created(toolSessionId)
    GW-->>SS: session_created
    SS->>DB: bind toolSessionId
    SS->>GW: retry pending chat
  else session ready
    SS->>GW: invoke chat/question_reply/permission_reply/abort_session
  end
  GW->>Agent: dispatch by ak
```

### 1.2 云端助理链路

```mermaid
sequenceDiagram
  autonumber
  participant Client as Client
  participant SS as skill-server
  participant Resolver as AssistantRouteResolver
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Inst as Instance Info API
  participant Remote as Remote Assistant

  Client->>SS: chat / reply\nassistantAccount required, ak optional
  SS->>Resolver: query instance info
  Resolver-->>SS: RoutePlan(REMOTE, assistantAccount, optional ak, bizRobotTag)
  SS->>DB: find/create session by business key + assistantAccount
  SS->>SS: generate local toolSessionId
  SS->>SS: build cloudRequest\nbizRobotTag -> cloudProfile
  SS->>GW: invoke\nassistantKind=REMOTE, assistantAccount, optional ak, cloudProfile
  GW->>Inst: query by assistantAccount
  Inst-->>GW: isRemote + remoteProperty[]
  GW->>GW: select remoteProperty by action
  GW->>Remote: call remote url with configured headers
  Remote-->>GW: stream/webhook response
```

### 1.3 默认助理链路

```mermaid
sequenceDiagram
  autonumber
  participant Client as Client
  participant SS as skill-server
  participant Rule as DefaultAssistantRule
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Inst as Instance Info API
  participant Callback as Legacy Callback Config
  participant Remote as Remote Assistant

  Client->>SS: request with domain/type\nno ak and no assistantAccount
  SS->>Rule: lookup(domain,type)
  Rule-->>SS: assistantAccount, virtualAk, businessTag
  SS->>DB: create/find session\nak optional/virtual, assistantAccount from rule
  SS->>SS: generate local toolSessionId
  SS->>SS: build cloudRequest\nbusinessTag -> cloudProfile
  SS->>GW: invoke\nassistantKind=DEFAULT, assistantAccount, optional virtualAk, cloudProfile
  alt rule assistantAccount is real and instance query succeeds
    GW->>Inst: query by assistantAccount
    Inst-->>GW: remoteProperty[]
    GW->>Remote: call by remoteProperty
  else legacy fallback
    GW->>Callback: getConfig(virtualAk, action scope, cloudProfile)
    Callback-->>GW: url/auth/protocol
    GW->>Remote: call legacy callback route
  end
```

## 2. External 场景

External 当前入口是 `POST /api/external/invoke`，现有 action 包含 `chat`、`question_reply`、`permission_reply`、`rebuild`。目标设计中如果要支持中止对话，需要新增或明确 `abort` action。

### 2.1 External 首次聊天 - 单聊 direct

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Ext->>SS: invoke chat\nsessionType=direct\nsessionId=user/dialog id\nassistantAccount\nsenderUserAccount
  SS->>Router: resolve route
  Router-->>SS: PERSONAL / REMOTE / DEFAULT
  SS->>DB: find session by domain+direct+sessionId+assistant identity
  alt no session
    SS->>Strategy: create session
    Strategy->>DB: insert session
  end
  alt PERSONAL
    Strategy->>GW: create_session if no toolSessionId
    GW->>Target: Local Agent by resolvedAk
  else REMOTE
    Strategy->>Strategy: local toolSessionId\nimGroupId=null
    Strategy->>GW: chat with assistantAccount
    GW->>Target: remoteProperty from instance API
  else DEFAULT
    Strategy->>Strategy: local toolSessionId\nimGroupId=null
    Strategy->>GW: chat with default rule identity
    GW->>Target: instance API or legacy callback fallback
  end
```

### 2.2 External 首次聊天 - 群聊 group

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Ext->>SS: invoke chat\nsessionType=group\nsessionId=group id\nassistantAccount\nsenderUserAccount
  SS->>Router: resolve route
  Router-->>SS: PERSONAL / REMOTE / DEFAULT
  SS->>DB: find session by domain+group+sessionId+assistant identity
  alt no session
    SS->>Strategy: create session
    Strategy->>DB: insert session
  end
  alt PERSONAL
    Strategy->>GW: create_session if no toolSessionId\npending chat includes imGroupId=sessionId
    GW->>Target: Local Agent by resolvedAk
  else REMOTE
    Strategy->>Strategy: local toolSessionId\nimGroupId=sessionId
    Strategy->>GW: chat with assistantAccount
    GW->>Target: remoteProperty from instance API
  else DEFAULT
    Strategy->>Strategy: local toolSessionId\nimGroupId=sessionId
    Strategy->>GW: chat with default rule identity
    GW->>Target: instance API or legacy callback fallback
  end
```

### 2.3 External 后续聊天

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway

  Ext->>SS: invoke chat\nsame business session
  SS->>Router: resolve route
  SS->>DB: find existing session
  alt PERSONAL and toolSessionId missing
    Strategy->>GW: create_session rebuild path\nallowed only for personal
  else REMOTE or DEFAULT and toolSessionId missing
    Strategy->>DB: generate/update local toolSessionId\nno rebuild
  end
  Strategy->>GW: invoke chat
```

### 2.4 External question_reply

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Ext->>SS: invoke question_reply\ncontent + toolCallId\noptional subagentSessionId
  SS->>Router: resolve route
  SS->>DB: find ready session
  alt session missing or not ready
    SS-->>Ext: 404 session not ready
  else PERSONAL
    SS->>GW: question_reply\nresolvedAk + toolSessionId/subagentSessionId
    GW->>Target: Local Agent
  else REMOTE
    SS->>GW: question_reply\nassistantAccount + cloudProfile
    GW->>Target: remote question route
  else DEFAULT
    SS->>GW: question_reply\ndefault identity + cloudProfile
    GW->>Target: instance route or legacy callback
  end
```

### 2.5 External permission_reply

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Ext->>SS: invoke permission_reply\npermissionId + response\noptional subagentSessionId
  SS->>Router: resolve route
  SS->>DB: find ready session
  alt session missing or not ready
    SS-->>Ext: 404 session not ready
  else PERSONAL
    SS->>GW: permission_reply\nresolvedAk + toolSessionId/subagentSessionId
    GW->>Target: Local Agent
  else REMOTE
    SS->>GW: permission_reply\nassistantAccount + cloudProfile
    GW->>Target: remote permission route
  else DEFAULT
    SS->>GW: permission_reply\ndefault identity + cloudProfile
    GW->>Target: instance route or legacy callback
  end
```

### 2.6 External 中止对话

```mermaid
sequenceDiagram
  autonumber
  participant Ext as External Caller
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Ext->>SS: invoke abort\n目标新增 action
  SS->>Router: resolve route
  SS->>DB: find session
  alt PERSONAL
    SS->>GW: abort_session\nresolvedAk + toolSessionId
    GW->>Target: abort local Agent operation
  else REMOTE
    SS->>GW: abort_remote\nassistantAccount + toolSessionId
    GW->>Target: cancel active remote stream if present
  else DEFAULT
    SS->>GW: abort_default\nvirtualAk/assistantAccount + toolSessionId
    GW->>Target: cancel active legacy/default remote stream if present
  end
  SS-->>Ext: accepted
```

当前缺口：External 控制器没有 `abort` action；GW 云端路径也没有独立的 cloud cancel action。

## 3. Miniapp 场景

### 3.1 Miniapp 新建会话

```mermaid
sequenceDiagram
  autonumber
  participant Mini as Miniapp
  participant SS as skill-server
  participant Router as AssistantRouteResolver
  participant Rule as DefaultAssistantRule
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Mini->>SS: POST /api/skill/sessions\nak optional\nassistantAccount optional\ndomain/type optional
  SS->>Router: resolve route
  alt PERSONAL
    Router-->>SS: resolvedAk
    SS->>DB: create session with resolvedAk
    SS->>GW: create_session
    GW->>Target: Local Agent by resolvedAk
  else REMOTE
    Router-->>SS: assistantAccount + optional ak + bizRobotTag
    SS->>DB: create session with assistantAccount
    SS->>DB: set local toolSessionId
    SS-->>Mini: session ready
  else DEFAULT
    Router->>Rule: lookup(domain,type)
    Rule-->>Router: assistantAccount, virtualAk, businessTag
    SS->>DB: create session with default identity
    SS->>DB: set local toolSessionId
    SS-->>Mini: session ready
  end
```

### 3.2 Miniapp 对话

```mermaid
sequenceDiagram
  autonumber
  participant Mini as Miniapp
  participant SS as skill-server
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway

  Mini->>SS: POST /sessions/{id}/messages\ncontent
  SS->>DB: load session and route kind
  SS->>DB: save user message
  SS-->>Mini: broadcast user echo
  alt PERSONAL
    Strategy->>SS: online check by resolvedAk
    alt no toolSessionId
      Strategy->>GW: create_session + pending chat
    else ready
      Strategy->>GW: chat to local Agent
    end
  else REMOTE
    Strategy->>GW: chat\nassistantAccount + cloudProfile\nno online check, no rebuild
  else DEFAULT
    Strategy->>GW: chat\ndefault identity + cloudProfile\nno online check, no rebuild
  end
```

### 3.3 Miniapp question_reply

```mermaid
sequenceDiagram
  autonumber
  participant Mini as Miniapp
  participant SS as skill-server
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway

  Mini->>SS: POST /sessions/{id}/messages\ncontent + toolCallId\noptional questionId/subagentSessionId
  SS->>DB: load session and route kind
  SS->>DB: save user reply message
  alt PERSONAL
    Strategy->>GW: question_reply\nresolvedAk + toolSessionId/subagentSessionId
  else REMOTE
    Strategy->>GW: question_reply\nassistantAccount + cloudProfile
  else DEFAULT
    Strategy->>GW: question_reply\ndefault identity + cloudProfile
  end
```

### 3.4 Miniapp permission_reply

```mermaid
sequenceDiagram
  autonumber
  participant Mini as Miniapp
  participant SS as skill-server
  participant Strategy as Flow Strategy
  participant DB as SkillSession DB
  participant GW as ai-gateway

  Mini->>SS: POST /sessions/{id}/permissions/{permId}\nresponse + optional subagentSessionId
  SS->>DB: load session and route kind
  alt PERSONAL
    Strategy->>GW: permission_reply\nresolvedAk + toolSessionId/subagentSessionId
  else REMOTE
    Strategy->>GW: permission_reply\nassistantAccount + cloudProfile
  else DEFAULT
    Strategy->>GW: permission_reply\ndefault identity + cloudProfile
  end
  SS-->>Mini: broadcast permission_reply echo
```

### 3.5 Miniapp 中止对话

```mermaid
sequenceDiagram
  autonumber
  participant Mini as Miniapp
  participant SS as skill-server
  participant DB as SkillSession DB
  participant GW as ai-gateway
  participant Target as Agent or Remote

  Mini->>SS: POST /sessions/{id}/abort
  SS->>DB: load session and route kind
  alt PERSONAL
    SS->>GW: abort_session\nresolvedAk + toolSessionId
    GW->>Target: abort local Agent operation
  else REMOTE
    SS->>GW: abort_remote\nassistantAccount + toolSessionId
    GW->>Target: cancel active remote stream if present
  else DEFAULT
    SS->>GW: abort_default\nvirtualAk/assistantAccount + toolSessionId
    GW->>Target: cancel active default/legacy stream if present
  end
  SS-->>Mini: status=aborted
```

当前缺口：现有 miniapp abort 对默认助理直接跳过 GW；云端 business action 也没有清晰的 cancel strategy。

## 4. 回流链路

### 4.1 本地个人助理回复链路

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Local Agent Plugin
  participant GW as ai-gateway
  participant SS as skill-server
  participant Router as GatewayMessageRouter
  participant Delivery as OutboundDeliveryDispatcher
  participant Client as Miniapp / External / IM

  Agent-->>GW: session_created / tool_event / permission_request / tool_done / tool_error
  GW-->>SS: GatewayMessage\nwelinkSessionId or toolSessionId
  SS->>Router: route by session affinity
  alt session_created
    Router->>SS: bind toolSessionId
    Router->>SS: retry pending messages
  else tool_event
    Router->>Router: PersonalScopeStrategy\nOpenCodeEventTranslator
    Router->>Delivery: StreamMessage
  else permission_request
    Router->>Delivery: permission_ask
  else tool_done
    Router->>Delivery: idle status\npersist final message
  else tool_error
    alt personal session invalid
      Router->>SS: rebuild allowed
    else normal error
      Router->>Delivery: error
    end
  end
  Delivery-->>Client: Miniapp WS / External WS / IM REST
```

### 4.2 云端助理和默认助理回复链路

```mermaid
sequenceDiagram
  autonumber
  participant Remote as Remote Assistant
  participant GW as ai-gateway
  participant Cloud as CloudAgentService
  participant SS as skill-server
  participant Router as GatewayMessageRouter
  participant Delivery as OutboundDeliveryDispatcher
  participant Client as Miniapp / External / IM

  Remote-->>Cloud: SSE / WS / webhook response
  Cloud->>Cloud: decode by cloudProfile
  Cloud-->>GW: GatewayMessage\nprotocol=cloud
  GW-->>SS: tool_event / tool_done / tool_error
  SS->>Router: route by welinkSessionId/toolSessionId
  Router->>Router: CloudEventTranslator
  alt tool_event
    Router->>Delivery: text.delta / text.done / question / permission_ask
  else tool_done
    Router->>Delivery: idle status
  else tool_error
    Router->>Delivery: error
    Note over Router: no session rebuild for REMOTE or DEFAULT
  end
  Delivery-->>Client: Miniapp WS / External WS / IM REST
```

## 5. 需要从图落到实现的分流点

### 5.1 正向 user -> agent

1. 新增统一命令模型：`AssistantInteractionCommand`，覆盖 chat、question_reply、permission_reply、abort、create_session。
2. 新增 `AssistantRouteResolver`，在入口生成 `AssistantRoutePlan`，后续只看 `RoutePlan.kind`，不再每一步用 `ak`、`businessTag`、`toolSessionId` 反复猜类型。
3. 新增或收敛 `AssistantInteractionService`，承接 External / Miniapp Adapter 的统一命令。
4. `PersonalAssistantFlowStrategy` 独占 online check、`create_session`、`session_created`、pending replay、rebuild。
5. `RemoteAssistantFlowStrategy` 以 `assistantAccount` 为主身份，`ak` 可空，SS 只发身份和 `cloudProfile`，GW 自己查实例接口拿 `remoteProperty`。
6. `DefaultAssistantFlowStrategy` 以默认规则为入口，走云端语义，保留 virtual AK + callback config fallback。
7. session 查询和锁不能继续只以 AK 作为唯一助理维度。目标应支持 `(domain,type,businessSessionId,assistantAccount)`，personal 可额外带 `resolvedAk`。
8. External 需要补中止动作；GW 云端路径需要补 cancel/correlation 管理，否则 remote/default abort 仍会落到 unknown action。

### 5.2 反向 agent -> user

1. 新增统一事件模型：`AssistantEventEnvelope`，承接 `tool_event`、`tool_done`、`tool_error`、`session_created`、`permission_request`。
2. 新增 `AssistantEventHandlingService`，从 `GatewayMessageRouter` 中抽出事件编排职责。
3. 新增 `SessionRouteContext`，通过 `welinkSessionId/toolSessionId` 找 session，并还原助理类型与投递来源。
4. `PersonalAssistantEventStrategy` 处理 `session_created`、pending replay、OpenCode event 翻译和 personal rebuild。
5. `RemoteAssistantEventStrategy` 只走 Cloud event 翻译，不处理 `session_created`，不 rebuild。
6. `DefaultAssistantEventStrategy` 与 remote 一样走 Cloud event 语义，但允许 legacy callback 配置来源。
7. `OutboundDeliveryDispatcher` 继续作为最终投递策略，保留 Miniapp / External / IM 差异；上游只产出统一 `StreamMessage`。

### 5.3 实施顺序建议

1. 先落模型：`RoutePlan`、`InteractionCommand`、`EventEnvelope`、`SessionRouteContext`。
2. 再落分流：`AssistantRouteResolver` 和实例信息查询缓存。
3. 再改 SS 正向：External / Miniapp 入口适配到统一 `AssistantInteractionService`。
4. 再改 GW：remote/default 云端路由优先级，GW 自查实例接口，legacy callback fallback。
5. 再改 SS 反向：从 `GatewayMessageRouter` 抽出 `AssistantEventHandlingService` 和 EventStrategy。
6. 最后补测试：三类助理、两个入口、正向五类动作、反向回流、无 AK 云端、默认助手 legacy fallback。
