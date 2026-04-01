# 多端用户消息实时同步

## 问题

两个 miniapp 实例同时在线、在同一个对话中时，miniapp A 发送消息后，miniapp B 只能通过 WebSocket 收到 AI 回复（assistant 角色的 StreamMessage），但看不到用户提问。刷新页面后通过 history API 才能加载到完整消息。

### 根因

1. **用户消息通过 REST API 发送**（`POST /api/skill/sessions/{sessionId}/messages`），发送方通过乐观更新写入本地 state，不经过 WebSocket。
2. **后端 `SkillMessageController.sendMessage` 持久化用户消息后没有 WebSocket 广播**，只转发给 AI-Gateway。
3. **`GatewayMessageRouter.handleToolEvent` 显式丢弃 user 角色 echo**（第 420-422 行），AI-Gateway 回传的用户消息 echo 不会广播。

结果：miniapp B 的 WebSocket 连接永远收不到用户消息，只能收到 AI 回复。

## 方案

在 `StreamMessage` 协议体系内新增 `message.user` 类型。`sendMessage` 持久化用户消息后，通过 `broadcastStreamMessage` 同步广播给同会话所有 WebSocket 连接，然后再转发给 AI-Gateway。

### 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 解决层 | 后端广播（非放行 echo / 非前端轮询） | 最直接，延迟最低，不依赖 AI-Gateway echo 时序 |
| 广播范围 | 所有连接（含发送者） | 前端已有 `knownUserMessageIdsRef` 去重机制，无需后端维护连接归属 |
| 消息格式 | `StreamMessage` 体系内新增 `message.user` 类型 | 保持 WebSocket 协议一致性，复用现有字段 |
| 持久化 | 广播时不做持久化 | 消息已在 `saveUserMessage` 入库，使用 `broadcastStreamMessage`（纯广播）而非 `publishProtocolMessage`（广播+缓冲） |
| 时序保证 | 同步先广播再转发 AI-Gateway | `broadcastStreamMessage` → `routeToGateway`，严格保证用户消息先于 AI 回复到达 |

### 时序流

```
SkillMessageController.sendMessage:
  1. saveUserMessage()              → 持久化到 DB
  2. broadcastStreamMessage(        → WebSocket 推送 message.user（纯广播，不持久化）
       sessionId, userId,
       StreamMessage.userMessage(...))
  3. routeToGateway()               → 转发给 AI-Gateway
  4. AI 回复流开始                   → text.delta / text.done 等（已有逻辑）
```

miniapp B 收到顺序：`message.user` → AI 回复流，与 miniapp A 视角一致。

## 改动清单

### 后端（skill-server）

#### 1. `StreamMessage.java` — 新增类型常量和工厂方法

```java
// Types 类中新增
public static final String MESSAGE_USER = "message.user";

// 静态工厂方法
public static StreamMessage userMessage(String messageId, Integer messageSeq,
                                        String content, String welinkSessionId) {
    return StreamMessage.builder()
            .type(Types.MESSAGE_USER)
            .messageId(messageId)
            .messageSeq(messageSeq)
            .role("user")
            .content(content)
            .welinkSessionId(welinkSessionId)
            .build();
}
```

#### 2. `SkillMessageController.sendMessage` — 持久化后广播

在 `saveUserMessage` 之后、`routeToGateway` 之前插入广播逻辑：

```java
// 已有：持久化用户消息
SkillMessage saved = messageService.saveUserMessage(numericSessionId, request.getContent());

// 新增：广播给同会话所有 WebSocket 连接（纯广播，不持久化）
messageRouter.broadcastStreamMessage(
    sessionIdStr, userId,
    StreamMessage.userMessage(
        saved.getMessageId(),      // "msg_{sessionId}_{seq}"
        saved.getSeq(),            // messageSeq
        saved.getContent(),        // 用户输入内容
        sessionIdStr               // welinkSessionId
    )
);

// 已有：转发给 AI-Gateway
routeToGateway(...);
```

#### 3. `GatewayMessageRouter.handleToolEvent` — 不变

user echo 丢弃逻辑保持不变，已由上游 `sendMessage` 中的广播覆盖。

### 前端（skill-miniapp）

#### 4. `protocol/types.ts` — 新增 StreamMessageType

```typescript
export type StreamMessageType =
  | 'text.delta'
  // ... 已有类型 ...
  | 'message.user'   // 新增
  | 'streaming';
```

#### 5. `hooks/useSkillStream.ts` — handleStreamMessage 新增分支

在 `handleStreamMessage` 中新增 `message.user` 处理：

```typescript
case 'message.user': {
  const messageId = msg.messageId;
  if (!messageId) break;

  // 利用已有去重机制：发送方的乐观更新消息已在 knownUserMessageIdsRef 中
  if (knownUserMessageIdsRef.current.has(messageId)) break;

  const userMsg: Message = {
    id: messageId,
    role: 'user',
    content: msg.content ?? '',
    contentType: 'plain',
    timestamp: msg.emittedAt ? new Date(msg.emittedAt).getTime() : Date.now(),
    messageSeq: msg.messageSeq,
  };

  setMessages(prev => sortMessages([...prev, userMsg]));
  break;
}
```

## 不改动的部分

- `GatewayMessageRouter` 中 user echo 丢弃逻辑
- 历史消息查询 API（`GET /messages/history`）
- `snapshot` 机制（连接建立时发送历史快照）
- `bufferService` 缓冲逻辑（用户消息不进缓冲）

## 验证方式

1. 打开两个 miniapp 实例，进入同一对话
2. 在 miniapp A 发送消息
3. 验证 miniapp B 实时收到用户消息（无需刷新）
4. 验证 miniapp A 不会出现重复消息（去重机制生效）
5. 验证数据库中用户消息只有一条（无重复持久化）
6. 验证消息顺序正确：用户消息在 AI 回复之前
