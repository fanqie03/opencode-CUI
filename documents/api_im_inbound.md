# IM 入站消息接口文档

## 接口概述

| 项目             | 说明                         |
| ---------------- | ---------------------------- |
| **接口名称**     | 接收 IM 入站消息             |
| **请求路径**     | `POST /api/inbound/messages` |
| **Content-Type** | `application/json`           |
| **认证方式**     | 集成token                    |

---

## 请求参数

### Request Body (JSON)

| 字段               | 类型   | 必填 | 说明                                               |
| ------------------ | ------ | ---- | -------------------------------------------------- |
| `businessDomain`   | String | ✅    | 业务域，当前仅支持 `"im"`                          |
| `sessionType`      | String | ✅    | 会话类型：`"direct"`（单聊）或 `"group"`（群聊）   |
| `sessionId`        | String | ✅    | IM 平台的会话 ID（单聊为对方用户 ID，群聊为群 ID） |
| `assistantAccount` | String | ✅    | 智能助手的 IM 平台账号标识                         |
| `content`          | String | ✅    | 消息文本内容                                       |
| `msgType`          | String | ❌    | 消息类型，默认 `"text"`，当前仅支持文本消息        |
| `imageUrl`         | String | ❌    | 图片 URL（预留字段，当前未启用）                   |
| `chatHistory`      | Array  | ❌    | 群聊历史消息列表（仅群聊场景需要传入）             |

### chatHistory 元素结构

| 字段            | 类型   | 必填 | 说明               |
| --------------- | ------ | ---- | ------------------ |
| `senderAccount` | String | ✅    | 发送者账号         |
| `senderName`    | String | ✅    | 发送者显示名称     |
| `content`       | String | ✅    | 消息内容           |
| `timestamp`     | long   | ✅    | 消息时间戳（毫秒） |

---

## 请求示例

### 单聊场景

```json
{
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "user-abc-123",
  "assistantAccount": "assistant-bot-001",
  "content": "帮我查一下今天的会议安排"
}
```

### 群聊场景

```json
{
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "group-xyz-456",
  "assistantAccount": "assistant-bot-001",
  "content": "请总结一下上面的讨论",
  "chatHistory": [
    {
      "senderAccount": "user-001",
      "senderName": "张三",
      "content": "我觉得方案A更好",
      "timestamp": 1710000000000
    },
    {
      "senderAccount": "user-002",
      "senderName": "李四",
      "content": "方案B的成本更低",
      "timestamp": 1710000060000
    }
  ]
}
```

---

## 响应参数

### 统一响应格式

| 字段       | 类型   | 说明                                  |
| ---------- | ------ | ------------------------------------- |
| `code`     | int    | 状态码：`0` 表示成功，非 `0` 为错误码 |
| `errormsg` | String | 错误信息，成功时为 `null`             |
| `data`     | Object | 响应数据，当前接口成功时为 `null`     |

---

## 响应示例

### 成功

```json
{
  "code": 0,
  "data": null
}
```

### 参数校验失败（HTTP 400）

```json
{
  "code": 400,
  "errormsg": "sessionId is required"
}
```

### 助手账号无效（HTTP 200）

```json
{
  "code": 404,
  "errormsg": "Invalid assistant account"
}
```

---

## 错误码说明

| code | HTTP 状态码 | errormsg                           | 触发条件                                                  |
| ---- | ----------- | ---------------------------------- | --------------------------------------------------------- |
| 0    | 200         | —                                  | 消息接收成功                                              |
| 400  | 400         | `Request body is required`         | 请求体为空                                                |
| 400  | 400         | `businessDomain is required`       | 缺少 businessDomain                                       |
| 400  | 400         | `Only IM inbound is supported`     | businessDomain 不是 `"im"`                                |
| 400  | 400         | `sessionType is required`          | 缺少 sessionType                                          |
| 400  | 400         | `Invalid sessionType`              | sessionType 不是 `direct` 或 `group`                      |
| 400  | 400         | `sessionId is required`            | 缺少 sessionId                                            |
| 400  | 400         | `assistantAccount is required`     | 缺少 assistantAccount                                     |
| 400  | 400         | `content is required`              | 缺少 content                                              |
| 400  | 400         | `Only text messages are supported` | msgType 不是 text                                         |
| 404  | 200         | `Invalid assistant account`        | assistantAccount 解析失败（无法获取 AK 或 ownerWelinkId） |

---

## 业务流程

```
IM 平台
  │
  ▼  POST /api/inbound/messages
┌─────────────────────────────────────────┐
│ ImInboundController.receiveMessage()    │
│                                         │
│ 1. 参数校验                             │
│ 2. 解析 assistantAccount → (ak, owner)  │
│ 3. 上下文注入（群聊拼接 chatHistory）    │
│ 4. 查找 session                         │
│    ├─ 不存在 → 异步创建 session → 返回  │
│    ├─ 无 toolSessionId → 请求重建 → 返回│
│    └─ 就绪 → 转发到 Agent Gateway       │
│       ├─ 单聊: 持久化用户消息轮次       │
│       └─ 群聊: 跳过持久化               │
└─────────────────────────────────────────┘
```

---

## 注意事项

1. **异步响应**：接口立即返回 `code: 0`，不等待 AI 回复。AI 回复通过 IM 出站服务异步推送。
2. **会话自动创建**：首次收到消息时会自动创建 skill session 并向 Gateway 申请 tool session，过程完全异步。
3. **单聊消息持久化**：单聊场景下会自动持久化用户消息轮次，用于上下文管理；群聊不做持久化。
4. **幂等性**：同一 session 的并发创建请求通过 Redis 分布式锁保证幂等。
