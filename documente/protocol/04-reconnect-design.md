# 会话恢复与断线重连方案

## 一、问题场景

```
用户发送消息 → OpenCode 正在流式输出 → 用户关闭窗口/切会话 → 切回来 → ???
                     ↑
              此时 AI 还在打字中，
              text delta 还在不断推过来
```

**要求：用户切回来后，立即看到所有已生成内容 + 无缝接续正在生成的内容。**

## 二、核心方案：三层数据架构

```
                  ┌────────────────────────────────┐
                  │   MySQL (最终态)                │
                  │   已完成的消息 + 部件            │
                  │   Part done → 写入 → 永久保存   │
                  └──────────────┬─────────────────┘
                                 │
                  ┌──────────────┴─────────────────┐
                  │   Redis (实时态)                 │
                  │   正在流式的 Part 累积内容        │
                  │   每条 delta → APPEND → 自动过期  │
                  └──────────────┬─────────────────┘
                                 │
                  ┌──────────────┴─────────────────┐
                  │   WebSocket (瞬时态)            │
                  │   实时 delta 推送               │
                  │   断开即丢失                     │
                  └────────────────────────────────┘
```

## 三、Redis 实时累积器

当 Skill Server 收到 OpenCode 事件时，**同时做两件事**：

```java
void handleToolEvent(String sessionId, JsonNode event) {
    StreamMessage msg = translate(event);

    // 1️⃣ 推 WebSocket（实时）
    broadcastToSession(sessionId, msg);

    // 2️⃣ 累积到 Redis（恢复用）
    accumulateToRedis(sessionId, msg);

    // 3️⃣ 完成时持久化到 MySQL（回看用）
    if (isFinalState(msg)) {
        persistToMySQL(sessionId, msg);
        clearRedisBuffer(sessionId, msg.partId);  // 清除 Redis 缓冲
    }
}
```

### Redis Key 设计

| Key                                | Value                                          | TTL | 用途             |
| ---------------------------------- | ---------------------------------------------- | --- | ---------------- |
| `stream:{sessionId}:status`        | `{status:"busy", messageId:2}`                 | 1h  | 会话是否在流式中 |
| `stream:{sessionId}:part:{partId}` | `{partType:"text", content:"已累积的文本..."}` | 1h  | Part 累积内容    |
| `stream:{sessionId}:parts_order`   | `["prt_1","prt_2","prt_3"]` (List)             | 1h  | Part 顺序        |

### 累积规则

| StreamMessage type    | Redis 操作                          | 说明               |
| --------------------- | ----------------------------------- | ------------------ |
| `text.delta`          | **APPEND** content                  | 文本追加           |
| `text.done`           | **SET** full content → 然后 **DEL** | 持久化后清除       |
| `thinking.delta`      | **APPEND** content                  | 思维链追加         |
| `tool.update`         | **SET** 覆盖                        | 状态覆盖           |
| `question`            | **SET**                             | 问题内容           |
| `step.done`           | **DEL** all parts                   | 步骤完成，全部清除 |
| `session.status=idle` | **DEL** status key                  | 会话结束           |

## 四、前端恢复协议（三阶段）

```
客户端 resume                          Skill Server
   │                                       │
   │──── { action:"resume",  ─────────────→│
   │      sessionId: 1 }                   │
   │                                       │
   │  ┌─── 阶段1: DB 快照 ────────────────→│ 查 MySQL
   │  │  { type:"snapshot",                │
   │  │    messages: [                     │
   │  │      {role:USER, content:"..."},   │
   │  │      {role:ASSISTANT, parts:[...]} │  ← 已完成的消息
   │  │    ] }                             │
   │←─┘                                   │
   │                                       │
   │  ┌─── 阶段2: Redis 进行中 ───────────→│ 查 Redis
   │  │  { type:"streaming",               │
   │  │    status: "busy",                 │
   │  │    parts: [                        │
   │  │      {partId:"prt_5", type:"text", │
   │  │       content:"已生成的..."  },     │  ← 正在流式的内容
   │  │      {partId:"prt_6", type:"tool", │
   │  │       toolName:"bash", status:"running"}│
   │  │    ] }                             │
   │←─┘                                   │
   │                                       │
   │  ┌─── 阶段3: 实时流恢复 ────────────→ │ 订阅 Redis/WS
   │  │  { type:"text.delta",              │
   │  │    partId:"prt_5",                 │
   │  │    content:"继续生成的内容..." }     │  ← 实时 δ 继续推
   │←─┘                                   │
```

### 前端处理代码

```typescript
async function resumeSession(sessionId: string) {
  // 1️⃣ 加载历史（DB 快照）
  const history = await api.getMessages(sessionId)
  setMessages(history.data)

  // 2️⃣ 连接 WebSocket
  const ws = connectWebSocket(sessionId)

  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data)

    if (msg.type === 'snapshot') {
      // 已完成消息（通常和 HTTP 获取的一样，做覆盖）
      setMessages(msg.messages)
    }
    else if (msg.type === 'streaming') {
      // 🔑 关键: 恢复正在进行的 Parts
      if (msg.status === 'busy') {
        setIsStreaming(true)
        // 在最后一条 ASSISTANT 消息上追加进行中的 Parts
        for (const part of msg.parts) {
          appendPartToLastMessage(part.partId, part)
        }
      }
    }
    else {
      // 3️⃣ 正常处理实时流
      handleStreamMessage(msg)
    }
  }
}
```

## 五、完整时序图

```
  用户         前端          Skill Server       Redis        MySQL      OpenCode
   │            │                │                │            │            │
   │──发消息──→ │────chat──────→ │─────invoke────→│            │      ────→ │
   │            │                │                │            │            │
   │            │  text.delta ←──│←─tool_event────│            │      ←──── │
   │  看到"你"  │←───────────── │──APPEND────────→│            │            │
   │            │  text.delta ←──│←───────────────│            │      ←──── │
   │  看到"你好"│←───────────── │──APPEND────────→│            │            │
   │            │                │                │            │            │
   ├──关闭窗口──┤ (WS 断开)      │                │            │            │
   │            ✕                │                │            │            │
   │                             │  text.delta ←──│            │      ←──── │
   │           （用户看不到）      │──APPEND────────→│            │            │
   │                             │  text.delta ←──│            │      ←──── │
   │                             │──APPEND────────→│            │            │
   │                             │                │            │            │
   ├──重新打开──┤                │                │            │            │
   │            │──resume──────→ │                │            │            │
   │            │                │──GET messages──→│      ────→ │            │
   │  历史快照  │←──snapshot─────│←────────────── │      ←──── │            │
   │            │                │──GET stream:*──→│            │            │
   │  进行中内容│←──streaming────│←────────────── │            │            │
   │            │                │                │            │            │
   │            │  text.delta ←──│←───────────────│            │      ←──── │
   │  无缝续上  │←───────────── │                │            │            │
```

## 六、边界 Case 处理

| 场景                       | 处理方式                                                        |
| -------------------------- | --------------------------------------------------------------- |
| 切回来时 OpenCode 刚好完成 | Redis buffer 已清空 → snapshot 包含完整内容 → 无 streaming 阶段 |
| 切走超过 1 小时            | Redis TTL 过期 → 但 OpenCode 早已完成 → DB 有完整数据           |
| 切走几秒钟就切回来         | Redis 有少量累积 → streaming 阶段补上 → 然后实时流继续          |
| 网络闪断后自动重连         | 前端 WS 自动重连 → 发 resume → 三阶段恢复                       |
| 多标签页同时看同一会话     | 每个 WS 独立订阅 → Redis 和 DB 共享 → 各标签页独立渲染          |
