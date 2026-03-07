# OpenCode CUI 端到端协议全景

> 版本：1.1  
> 日期：2026-03-08

---

## 系统架构

```mermaid
graph LR
    subgraph "用户侧"
        A["Miniapp<br/>(WeLink H5)"]
    end

    subgraph "服务端"
        B["Skill Server<br/>(Java/Spring)"]
        C["AI-Gateway<br/>(Java/Spring)"]
    end

    subgraph "桌面端"
        D["PC Agent<br/>(OpenCode Plugin)"]
        E["OpenCode<br/>(本地运行时)"]
    end

    A -- "层① REST + WebSocket" --> B
    B -- "层② WebSocket + REST" --> C
    C -- "层③ WebSocket" --> D
    D -- "层④ SDK API + Event Hook" --> E
```

---

## 四层协议总览

| 层  | 链路                      | 下行（指令方向） | 上行（事件方向）         | 认证             |
| --- | ------------------------- | ---------------- | ------------------------ | ---------------- |
| ①   | Miniapp ↔ Skill Server    | 8 REST API       | 19 种 StreamMessage (WS) | WeLink Cookie    |
| ②   | Skill Server ↔ AI-Gateway | 6 种 invoke (WS) | 6 种事件 (WS) + 3 REST   | 内部 Token       |
| ③   | AI-Gateway ↔ PC Agent     | 7 种消息 (WS)    | 7 种消息 (WS)            | AK/SK 签名       |
| ④   | PC Agent ↔ OpenCode       | 7 SDK 调用       | 17 种事件 + 12 种 Part   | 无（本机进程间） |

---

## 完整流程图

### 流程 1：创建会话

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    User->>MA: 点击"新建会话"
    MA->>SS: POST /api/skill/sessions<br/>{ak, title, imGroupId}
    SS->>SS: 创建 SkillSession<br/>(toolSessionId=null)
    SS->>SS: 订阅 Redis session:{id}
    SS-->>MA: 200 {welinkSessionId, status: ACTIVE}

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"create_session",<br/>welinkSessionId:"42", payload:{title}}
    GW->>GW: ak → agentId 路由
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"create_session",<br/>welinkSessionId:"42", payload:{title}}

    PA->>OC: [SDK] client.session.create({body:{title}})
    OC-->>PA: Session{id: "ses_abc"}
    PA->>GW: [WS] session_created<br/>{welinkSessionId:"42", toolSessionId:"ses_abc"}
    GW->>SS: [WS] session_created<br/>{welinkSessionId:"42", toolSessionId:"ses_abc"}
    SS->>SS: 更新 SkillSession.toolSessionId = "ses_abc"
```

---

### 流程 2：发送消息（含 AI 流式回复）

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    User->>MA: 输入消息
    MA->>SS: POST /api/skill/sessions/{id}/messages<br/>{content: "帮我创建React项目"}
    SS->>SS: 持久化 user message
    SS-->>MA: 200 {id, messageSeq}

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"chat",<br/>payload:{toolSessionId, text}}
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"chat",<br/>payload:{toolSessionId, text}}
    PA->>OC: [SDK] client.session.prompt({path:{id}, body:{parts}})

    loop AI 流式处理
        OC-->>PA: [Event] message.part.updated<br/>{part:{type:"text", text:"好的，"}, delta:"好的，"}
        PA->>GW: [WS] tool_event<br/>{toolSessionId, event:{...}}
        GW->>SS: [WS] tool_event<br/>{toolSessionId, event:{...}}
        SS->>SS: 翻译为 StreamMessage
        SS-->>MA: [WS] text.delta<br/>{welinkSessionId, partId, content:"好的，"}
        MA-->>User: 流式显示文本
    end

    OC-->>PA: [Event] session.idle
    PA->>GW: [WS] tool_done<br/>{toolSessionId, usage:{...}}
    GW->>SS: [WS] tool_done<br/>{toolSessionId, usage:{...}}
    SS-->>MA: [WS] step.done<br/>{welinkSessionId, tokens, cost}
```

---

### 流程 3：AI 提问（question tool）+ 用户回答

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    Note over OC: AI 调用 question 工具

    OC-->>PA: [Event] message.part.updated<br/>{part:{type:"tool", tool:"question",<br/>state:{status:"running",<br/>input:{question:"选择框架", options:["Vite","CRA"]}}}}
    PA->>GW: [WS] tool_event<br/>{toolSessionId, event:{...}}
    GW->>SS: [WS] tool_event
    SS->>SS: 识别 question tool → 翻译
    SS-->>MA: [WS] question<br/>{toolCallId:"call_2", question:"选择框架",<br/>options:["Vite","CRA"]}
    MA-->>User: 显示问题卡片

    User->>MA: 选择 "Vite"
    MA->>SS: POST /api/skill/sessions/{id}/messages<br/>{content:"Vite", toolCallId:"call_2"}
    SS->>SS: 有 toolCallId → 走 question_reply

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"question_reply",<br/>payload:{toolSessionId, toolCallId:"call_2", answer:"Vite"}}
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"question_reply",<br/>payload:{toolSessionId, toolCallId:"call_2", answer:"Vite"}}
    PA->>OC: [SDK] client.session.prompt({path:{id},<br/>body:{parts:[{type:"text", text:"Vite"}]}})

    Note over OC: OpenCode 自动识别为 question 回答
    OC-->>PA: [Event] message.part.updated<br/>{part:{type:"tool", tool:"question",<br/>state:{status:"completed", output:"Vite"}}}
    PA->>GW: [WS] tool_event
    GW->>SS: [WS] tool_event
    SS-->>MA: [WS] tool.update<br/>{toolCallId:"call_2", status:"completed"}
```

---

### 流程 4：权限请求 + 用户批准

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    Note over OC: AI 需要执行 bash 命令

    OC-->>PA: [Event] permission.updated<br/>{sessionID, permissionID:"perm_1",<br/>toolName:"bash", input:{command:"npm install"}}
    PA->>GW: [WS] tool_event<br/>{toolSessionId, event:{...}}
    GW->>SS: [WS] tool_event
    SS-->>MA: [WS] permission.ask<br/>{permissionId:"perm_1", permType:"bash",<br/>metadata:{command:"npm install"}}
    MA-->>User: 显示权限审批弹窗

    User->>MA: 点击"允许本次"
    MA->>SS: POST /api/skill/sessions/{id}/permissions/perm_1<br/>{response:"once"}

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"permission_reply",<br/>payload:{toolSessionId, permissionId:"perm_1", response:"once"}}
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"permission_reply",<br/>payload:{toolSessionId, permissionId:"perm_1", response:"once"}}
    PA->>OC: [SDK] client.postSessionIdPermissionsPermissionId({<br/>path:{id, permissionID:"perm_1"}, body:{response:"once"}})

    OC-->>PA: [Event] permission.replied<br/>{permissionID:"perm_1", response:"once"}
    PA->>GW: [WS] tool_event
    GW->>SS: [WS] tool_event
    SS-->>MA: [WS] permission.reply<br/>{permissionId:"perm_1", response:"once"}

    Note over OC: 开始执行命令...
```

---

### 流程 5：中止执行

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    User->>MA: 点击"停止"
    MA->>SS: POST /api/skill/sessions/{id}/abort
    SS-->>MA: 200 {status: "aborted"}

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"abort_session",<br/>payload:{toolSessionId}}
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"abort_session",<br/>payload:{toolSessionId}}
    PA->>OC: [SDK] client.session.abort({path:{id}})

    OC-->>PA: [Event] session.status {status:"idle"}
    PA->>GW: [WS] tool_event
    GW->>SS: [WS] tool_event
    SS-->>MA: [WS] session.status {sessionStatus:"idle"}
```

---

### 流程 6：关闭会话

```mermaid
sequenceDiagram
    actor User as 用户
    participant MA as Miniapp
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    User->>MA: 点击"删除会话"
    MA->>SS: DELETE /api/skill/sessions/{id}
    SS-->>MA: 200 {status:"closed"}
    SS->>SS: SkillSession.status = CLOSED
    SS->>SS: 取消 Redis 订阅

    SS->>GW: [WS] invoke<br/>{type:"invoke", ak, action:"close_session",<br/>payload:{toolSessionId}}
    GW->>PA: [WS] invoke<br/>{type:"invoke", action:"close_session",<br/>payload:{toolSessionId}}
    PA->>OC: [SDK] client.session.delete({path:{id}})
    OC-->>PA: [Event] session.deleted
```

---

### 流程 7：Agent 上线/下线

```mermaid
sequenceDiagram
    participant SS as Skill Server
    participant GW as AI-Gateway
    participant PA as PC Agent

    Note over PA: PC Agent 启动

    PA->>GW: [WS 握手] ws://gateway/ws/agent?ak=...&ts=...&nonce=...&sign=...
    GW->>GW: AK/SK 签名校验
    GW-->>PA: 握手成功
    PA->>GW: [WS] register<br/>{type:"register", deviceName, os, toolType, toolVersion}
    GW->>GW: 注册 Agent, 分配 agentId
    GW->>SS: [WS] agent_online<br/>{type:"agent_online", ak}
    SS-->>SS: 更新 Agent 状态 → 通知前端

    loop 心跳保活
        PA->>GW: [WS] heartbeat<br/>{type:"heartbeat"}
        GW->>GW: 更新 last_seen_at
    end

    Note over PA: PC Agent 断开
    GW->>GW: 检测连接断开
    GW->>SS: [WS] agent_offline<br/>{type:"agent_offline", ak}
    SS-->>SS: 更新 Agent 状态 → 通知前端
```

---

### 流程 8：健康检查

```mermaid
sequenceDiagram
    participant GW as AI-Gateway
    participant PA as PC Agent
    participant OC as OpenCode

    GW->>PA: [WS] status_query<br/>{type:"status_query"}
    PA->>OC: [SDK] client.app.health()
    OC-->>PA: 200 OK
    PA->>GW: [WS] status_response<br/>{type:"status_response", opencodeOnline:true}
```

---

## 各层协议消息映射

### 下行映射（指令方向：用户 → AI）

| 用户操作 | 层① Miniapp                                      | 层② Skill→GW              | 层③ GW→Agent              | 层④ Agent→OpenCode                       |
| -------- | ------------------------------------------------ | ------------------------- | ------------------------- | ---------------------------------------- |
| 创建会话 | `POST /sessions`                                 | `invoke.create_session`   | `invoke.create_session`   | `session.create()`                       |
| 发消息   | `POST /sessions/{id}/messages`                   | `invoke.chat`             | `invoke.chat`             | `session.prompt()`                       |
| 回答提问 | `POST /sessions/{id}/messages`<br/>(+toolCallId) | `invoke.question_reply`   | `invoke.question_reply`   | `session.prompt()`                       |
| 权限批准 | `POST /sessions/{id}/permissions/{permId}`       | `invoke.permission_reply` | `invoke.permission_reply` | `postSessionIdPermissionsPermissionId()` |
| 中止     | `POST /sessions/{id}/abort`                      | `invoke.abort_session`    | `invoke.abort_session`    | `session.abort()`                        |
| 关闭会话 | `DELETE /sessions/{id}`                          | `invoke.close_session`    | `invoke.close_session`    | `session.delete()`                       |
| 健康检查 | —                                                | REST `GET /agents/status` | `status_query`            | `app.health()`                           |

### 上行映射（事件方向：AI → 用户）

| OpenCode 事件 | 层④ Event                                          | 层③ Agent→GW | 层② GW→Skill    | 层① StreamMessage                  |
| ------------- | -------------------------------------------------- | ------------ | --------------- | ---------------------------------- |
| 文本生成      | `message.part.updated`<br/>(part.type=text)        | `tool_event` | `tool_event`    | `text.delta` / `text.done`         |
| 思维链        | `message.part.updated`<br/>(part.type=reasoning)   | `tool_event` | `tool_event`    | `thinking.delta` / `thinking.done` |
| 工具调用      | `message.part.updated`<br/>(part.type=tool)        | `tool_event` | `tool_event`    | `tool.update`                      |
| AI 提问       | `message.part.updated`<br/>(tool=question)         | `tool_event` | `tool_event`    | `question`                         |
| 权限请求      | `permission.updated`                               | `tool_event` | `tool_event`    | `permission.ask`                   |
| 权限响应      | `permission.replied`                               | `tool_event` | `tool_event`    | `permission.reply`                 |
| 会话状态      | `session.status`                                   | `tool_event` | `tool_event`    | `session.status`                   |
| 标题更新      | `session.updated`                                  | `tool_event` | `tool_event`    | `session.title`                    |
| 推理开始      | `message.part.updated`<br/>(part.type=step-start)  | `tool_event` | `tool_event`    | `step.start`                       |
| 推理结束      | `message.part.updated`<br/>(part.type=step-finish) | `tool_event` | `tool_event`    | `step.done`                        |
| 会话错误      | `session.error`                                    | `tool_event` | `tool_error`    | `session.error`                    |
| 执行完成      | `session.idle`                                     | `tool_done`  | `tool_done`     | `step.done`                        |
| Agent 上线    | —                                                  | `register`   | `agent_online`  | `agent.online`                     |
| Agent 下线    | —                                                  | 连接断开     | `agent_offline` | `agent.offline`                    |

---

## ID 流转全景

```mermaid
graph LR
    subgraph "Skill Server 创建"
        WID["welinkSessionId"]
    end

    subgraph "OpenCode 创建"
        TID["toolSessionId"]
    end

    subgraph "预分配"
        AK["ak (Access Key)"]
    end

    subgraph "Gateway 内部"
        AID["agentId"]
    end

    WID -->|"层①②③ 全链路透传"| WID
    TID -->|"层②③④ 全链路路由"| TID
    AK -->|"层①② 定位 Agent"| AID
    AID -->|"仅 Gateway 内部"| AID
```

| ID                | 创建者       | 感知范围                         | 用途                         |
| ----------------- | ------------ | -------------------------------- | ---------------------------- |
| `welinkSessionId` | Skill Server | 全链路（Skill→GW→Agent→回传）    | 层① 会话标识，其他层原样透传 |
| `toolSessionId`   | OpenCode SDK | Agent→GW→Skill（回传）           | 层②③④ 会话路由               |
| `ak`              | 预分配       | Miniapp / Skill Server / Gateway | 定位 Agent 连接              |
| `agentId`         | Gateway      | 仅 Gateway 内部                  | 内部路由，对外不暴露         |
