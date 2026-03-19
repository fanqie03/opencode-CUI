# OpenCode-CUI 各场景需求边界定义

> **范围约束**：本文档只关注 **skill-server** 和 **ai-gateway** 的职责。IM 平台（客户端+服务端）和 skill-miniapp 前端属于外部系统，不在分析范围内。

---

## 关键前提

> [!IMPORTANT]
> 1. **@感知由 IM 负责**：群聊中 @数字分身时，skill-server 不直接感知。IM 服务端识别到 @事件后，调用 skill-server 的 REST 接口发送消息。
> 2. **IM 不调会话创建接口**：IM 为每个单聊/群聊定义一个唯一标识字符串（`chatId`），通过发送消息接口传过来。
> 3. **发消息接口自动建会话**：如果对应的 WelinkSession 不存在，skill-server 的发送消息接口需要**自动创建** WelinkSession 并绑定 ToolSession，然后再处理消息。
> 4. **IM 不感知 AK**：IM 服务端只知道机器人的 `welinkId`（IM 平台标识），不知道 AK。skill-server 需要调用第三方接口将 `welinkId` 解析为对应的 `ak`。
> 5. **会话标识三元组**：会话通过 `chat_domain + chat_id + ak` 三元组唯一标识。`chat_domain` 表示来源场景（miniapp/im/meeting/doc），支持未来扩展到会议、云文档等场景。

---

## 系统边界概览

```
┌─────────────────────────────────┐
│         IM 平台（外部）          │
│  ┌─────────┐  ┌──────────────┐  │
│  │IM 客户端│  │  IM 服务端   │  │
│  └─────────┘  └──────────────┘  │
└───────┬──────────────┬──────────┘
        │              │
 场景一: WS      场景二/单聊: REST
 (miniapp)       (IM调我们的接口)
        │              │
┌───────┴──────────────┴──────────┐
│     我们的范围（本文档关注）        │
│  ┌──────────────────────────┐   │
│  │      skill-server        │   │
│  └────────────┬─────────────┘   │
│               │ WS (内部)       │
│  ┌────────────┴─────────────┐   │
│  │       ai-gateway          │   │
│  └────────────┬─────────────┘   │
└───────────────┼─────────────────┘
                │ WS (AK/SK)
         ┌──────┴──────┐
         │ OpenCode    │
         │  Agent      │
         └─────────────┘
```

---

## 场景一：技能小程序模式

### 数据流

```
skill-miniapp ──WS (Cookie)──→ skill-server ──WS (Token)──→ ai-gateway ──WS (AK/SK)──→ Agent
     ↑                              │
     │                              ↓
     └─────── WS (Redis pub/sub) ───┘
                     │
                     ↓ (用户手动触发)
              ImMessageService ──HTTP POST──→ IM 服务端 → 群聊
```

### skill-server 职责

| 编号 | 职责                                          | 关联规则   | 现状                                                  |
| ---- | --------------------------------------------- | ---------- | ----------------------------------------------------- |
| S1-1 | 管理前端 WS 连接（单连接 per user）           | G1         | ✅ `SkillStreamController`                             |
| S1-2 | 会话 CRUD（创建/查询/关闭/超时idle）          | G3, G5, G9 | ✅ `SkillSessionService`                               |
| S1-3 | 消息持久化（user/assistant/system/tool）      | G4         | ✅ `SkillMessageService` + `MessagePersistenceService` |
| S1-4 | AK 自动解析（调第三方 identities/check 接口） | G7         | ⚠️ 部分（缺第三方 check 调用）                         |
| S1-5 | Gateway 双向消息路由                          | —          | ✅ `GatewayRelayService` + `GatewayMessageRouter`      |
| S1-6 | 会话重建（toolSession not found → rebuild）   | —          | ✅ `SessionRebuildService`                             |
| S1-7 | 发送到群聊（用户手动触发，纯文本 POST）       | G8         | ✅ `ImMessageService.sendMessage`                      |
| S1-8 | 流式消息广播（Redis pub/sub → WS push）       | G1         | ✅ `RedisMessageBroker`                                |

### ai-gateway 职责

| 编号 | 职责                              | 现状 |
| ---- | --------------------------------- | ---- |
| G1-1 | skill-server 内部 WS + Token 认证 | ✅    |
| G1-2 | Agent WS + AK/SK HMAC 认证        | ✅    |
| G1-3 | Agent 注册/心跳/离线管理          | ✅    |
| G1-4 | 设备绑定验证 + 重复连接检测       | ✅    |
| G1-5 | 消息中继（skill-server ↔ Agent）  | ✅    |
| G1-6 | 在线 Agent 查询 API               | ✅    |
| G1-7 | AK/SK 凭证管理                    | ✅    |

> **场景一：skill-server 和 ai-gateway 基本完整。** 仅缺 G7 的第三方 AK check 调用。

---

## 场景二：数字分身进群模式

### 数据流

```
用户在群聊 @数字分身
        │
        ↓ (IM 服务端感知)
IM 服务端 ──REST 调用(Token)──→ skill-server 发消息接口
                                    │
                                    ├─ 第三方接口: welinkId → ak 解析
                                    │
                                    ├─ chat_domain('im') + chatId + ak 无对应 session? → 自动创建 WelinkSession + 绑定 ToolSession
                                    │
                                    ├─ 上下文注入（群聊历史 + 当前消息 → Prompt）
                                    │
                                    ├─ Gateway invoke → Agent
                                    │
                                    ↓ (Agent 回复)
                              skill-server ──REST(Token)──→ IM 服务端 → 群聊（@发送者）
```

> **vs 场景一的核心区别**：
> - 入口：不是 WS（miniapp 主动连接），而是 **IM 服务端调 skill-server 的 REST 接口**
> - 会话创建：不由客户端显式调用，而是 **发消息接口根据 chatId 自动 findOrCreate**
> - 回复出口：不是 WS push 给 miniapp，而是 **REST 调用 IM 服务端接口发到群聊**

### skill-server 职责

| 编号  | 职责                                                                                                                         | 关联规则      | 现状     | 说明                                      |
| ----- | ---------------------------------------------------------------------------------------------------------------------------- | ------------- | -------- | ----------------------------------------- |
| S2-1  | **接收消息 REST 接口**：供 IM 服务端调用，传入 chatId + welinkId + 消息内容 + 群聊历史（可选）+ 发送者信息                   | GA3, GA7      | ❌ 无     | IM 不传 AK，只传 welinkId                 |
| S2-1a | **welinkId → ak 解析**：调用第三方接口将 welinkId 转换为 ak                                                                  | GA7, G7       | ❌ 无     | 与场景一 G7 类似，每次消息进来都需执行    |
| S2-2  | **Token 认证**：验证 IM 服务端请求合法性                                                                                     | GA7           | ❌ 无     | 新增拦截器，校验请求 Token                |
| S2-3  | **自动会话管理**：chat_domain + chatId + ak 查找 session，找不到则自动创建 WelinkSession + 绑定 ToolSession，并关联 welinkId | GA5, G9       | ❌ 无     | 核心新逻辑，需新增 findOrCreate 语义      |
| S2-5  | **上下文注入**：群聊历史 + 当前消息组装为 Prompt                                                                             | GA8           | ❌ 无     | 新增可配置 prompt 模板                    |
| S2-6  | **Agent 调用**                                                                                                               | —             | ✅ 可复用 | `GatewayRelayService.sendInvokeToGateway` |
| S2-7  | **回复路由分支**：根据 chatDomain + chatType 走 IM REST 出站而非 WS push                                                     | GA4, GA7      | ❌ 无     | `GatewayMessageRouter` 增加判断           |
| S2-8  | ~~**消息格式转换**：Agent 输出 → 纯文本/图片~~                                                                               | GA10          | ⏳ 延后   | **本期延后**，Agent 文本直接透传          |
| S2-9  | **IM 出站发送**：Token 认证 + ~~@人字段填充 + 图片~~                                                                         | GA3, GA4, GA9 | ⚠️ 部分   | Token 已做，~~@人和图片本期延后~~         |
| S2-10 | **群聊不做消息持久化**：聊天记录由 IM 平台管理，skill-server 不存群聊消息                                                    | GA8           | ❌ 无     |                                           |
| S2-11 | **上下文超限自动重建**                                                                                                       | GA5a          | ❌ 无     | 检测 context overflow 触发 rebuild        |
| S2-12 | ~~**图片接收与传递**~~                                                                                                       | GA9           | ⏳ 延后   | **本期延后**                              |
| S2-13 | **长连接通信**                                                                                                               | GA7           | ❌ 无     | 可后置                                    |

### ai-gateway 职责

| 编号 | 职责                          | 现状   | 说明   |
| ---- | ----------------------------- | ------ | ------ |
| G2-1 | invoke 转发给 Agent           | ✅ 复用 | 无变化 |
| G2-2 | Agent 回复中继到 skill-server | ✅ 复用 | 无变化 |
| G2-3 | 在线 Agent 查询               | ✅ 复用 | 无变化 |

> **场景二：ai-gateway 无需改动。skill-server 核心新增是 S2-1（接收消息接口）+ S2-1a（welinkId→ak）+ S2-3（自动会话管理）。群内成员管理由 IM 负责，不在我们范围内。**

---

## 单聊场景

### 数据流

```
用户在 IM 单聊窗口发消息
        │
        ↓
IM 服务端 ──REST 调用(Token)──→ skill-server 发消息接口（同一个接口）
                                    │
                                    ├─ 第三方接口: welinkId → ak 解析
                                    │
                                    ├─ chat_domain('im') + chatId + ak 无对应 session? → 自动创建 + 绑定
                                    │
                                    ├─ 直接发给 Agent（无上下文注入）
                                    │
                                    ↓ (Agent 回复)
                              skill-server ──REST(Token)──→ IM 服务端 → 用户单聊
```

> **vs 场景二**：数据流完全相同，区别仅在于 ① 无上下文注入 ② 发送目标是单聊而非群聊

### skill-server 职责

| 编号  | 职责                                                    | 关联规则 | 与场景二的关系                                             |
| ----- | ------------------------------------------------------- | -------- | ---------------------------------------------------------- |
| S3-1  | **接收消息 REST 接口**（传入 chatId + welinkId + 消息） | D5       | **共用** S2-1（同一个接口）                                |
| S3-1a | **welinkId → ak 解析**                                  | D5       | **共用** S2-1a                                             |
| S3-2  | **Token 认证**                                          | D5       | **共用** S2-2                                              |
| S3-3  | **自动会话管理**                                        | D7, D9   | **共用** S2-3（findOrCreate by chat_domain + chatId + ak） |
| S3-4  | **Agent 调用**                                          | —        | **共用** S2-6                                              |
| S3-5  | **回复路由**                                            | D5       | **共用** S2-7                                              |
| S3-6  | ~~**消息格式转换**~~                                    | D10      | **本期延后**（共用 S2-8）                                  |
| S3-7  | **IM 出站发送**（~~@人/图片延后~~）                     | D5       | Token 已做，共用 S2-9                                      |
| S3-8  | **ToolSession 手动重建**（创建新会话按钮）              | D6, D7   | 复用 `SessionRebuildService`                               |
| S3-9  | **上下文超限自动重建**                                  | D7       | **共用** S2-11                                             |
| S3-10 | ~~**图片消息支持**~~                                    | D10      | **本期延后**（共用 S2-12）                                 |
| S3-11 | **消息持久化**                                          | D8       | **共用** S2-13                                             |
| S3-12 | **长连接通信**                                          | D5       | **共用** S2-14                                             |

### ai-gateway 职责

| 编号 | 职责           | 现状   | 说明   |
| ---- | -------------- | ------ | ------ |
| G3-1 | Agent 消息中继 | ✅ 复用 | 无变化 |
| G3-2 | AK/SK 凭证管理 | ✅ 复用 | 无变化 |

> **单聊：ai-gateway 无需改动。skill-server 职责与场景二几乎完全共用。**

---

## 跨场景共用矩阵

| 能力                                             | 场景一 | 场景二 | 单聊  | 归属                                  |
| ------------------------------------------------ | :----: | :----: | :---: | ------------------------------------- |
| WS 入站（miniapp）                               |   ✅    |   —    |   —   | skill-server 已有                     |
| **REST 接收消息接口**（IM 调用）                 |   —    |   ✅    |   ✅   | skill-server **新增**                 |
| **welinkId → ak 解析**（调第三方）               | ⚠️ 部分 |   ✅    |   ✅   | skill-server **新增**                 |
| Token 认证（IM 通信）                            |   —    |   ✅    |   ✅   | skill-server **新增**                 |
| **自动 findOrCreate session + 绑定 toolSession** |   —    |   ✅    |   ✅   | skill-server **新增**                 |
| 会话 CRUD                                        |   ✅    |   ✅    |   ✅   | skill-server 复用 + 扩展              |
| 消息持久化                                       |   ✅    |   ❌    |   ✅   | skill-server（群聊不持久化）          |
| Agent 调用（via Gateway）                        |   ✅    |   ✅    |   ✅   | 复用                                  |
| 回复 WS 广播（miniapp）                          |   ✅    |   —    |   —   | skill-server 已有                     |
| **回复 IM 出站**（REST 发到 IM）                 | ⚠️ 手动 |   ✅    |   ✅   | skill-server 增强（~~@人/图片延后~~） |
| ~~**消息格式转换**~~（Agent→IM）                 | ⚠️ 简单 |   ⏳    |   ⏳   | **本期延后**                          |
| **上下文注入** + Prompt 模板                     |   —    |   ✅    |   —   | skill-server **新增**                 |
| 上下文超限自动重建                               |   —    |   ✅    |   ✅   | skill-server **新增**                 |
| 长连接通信                                       |   —    |   ✅    |   ✅   | skill-server **新增**（可后置）       |
| ~~图片消息收发~~                                 |   —    |   ⏳    |   ⏳   | **本期延后**                          |
| Gateway 消息中继                                 |   ✅    |   ✅    |   ✅   | ai-gateway 复用                       |
| Agent 注册/认证                                  |   ✅    |   ✅    |   ✅   | ai-gateway 复用                       |

---

## 结论

### ai-gateway

> **三个场景完全复用现有代码**。唯一待确认：AK/SK 生成接口。

### skill-server

场景二和单聊**共用一个核心流程**：

```
IM 服务端 ── REST(chatId + welinkId + 消息) ──→ skill-server
    → welinkId → ak（第三方接口）
    → chatDomain('im') + chatId + ak 有 session? 复用 : 自动创建 + 绑定 toolSession
    → [场景二: 注入群聊历史 | 单聊: 直接发]
    → Gateway invoke → Agent → 纯文本透传 → REST → IM 服务端
```

**按模块划分需要新增/增强的能力：**

| 模块                                | 场景      | 核心说明                                                      |
| ----------------------------------- | --------- | ------------------------------------------------------------- |
| **REST 接收消息接口**               | 二 + 单聊 | 一个端点，chatId 区分，自动 findOrCreate session              |
| **welinkId → ak 解析**              | 二 + 单聊 | 调第三方接口，可做缓存                                        |
| **自动会话创建 + ToolSession 绑定** | 二 + 单聊 | chat_domain + chatId + ak → findOrCreate + 自动绑 toolSession |
| **消息格式转换器**                  | 二 + 单聊 | ~~Agent 输出 → 纯文本/图片~~（**本期延后**，直接透传）        |
| **IM 出站**                         | 二 + 单聊 | Token 认证 + 纯文本发送（~~@人 + 图片本期延后~~）             |
| **会话类型扩展**                    | 二 + 单聊 | `chat_domain + chat_type` + 回复路由分支                      |
| **上下文注入引擎**                  | 仅场景二  | Prompt 模板 + 群聊历史注入                                    |
| **上下文超限重建**                  | 二 + 单聊 | 错误检测 + 自动 rebuild                                       |
| **长连接通信**                      | 二 + 单聊 | 可后置优化                                                    |
