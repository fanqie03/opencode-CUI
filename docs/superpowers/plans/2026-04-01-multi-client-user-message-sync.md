# 多端用户消息实时同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当多个 miniapp 实例在同一对话中时，一端发送的用户消息实时同步到其他端，无需刷新。

**Architecture:** 在 `SkillMessageController.sendMessage` 持久化用户消息后、转发 AI-Gateway 之前，通过 `GatewayMessageRouter.broadcastStreamMessage` 广播一条 `message.user` 类型的 `StreamMessage`。前端新增 `message.user` 处理分支，利用已有 `knownUserMessageIdsRef` 去重。

**Tech Stack:** Java 21 / Spring Boot 3.4 / Lombok / TypeScript / React 18

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `skill-server/.../model/StreamMessage.java` | Modify:165,184 | 新增 `MESSAGE_USER` 常量 + `userMessage()` 工厂方法 |
| `skill-server/.../controller/SkillMessageController.java` | Modify:57,114-117 | 注入 `GatewayMessageRouter`，在持久化后广播 |
| `skill-miniapp/src/protocol/types.ts` | Modify:8-27 | `StreamMessageType` 新增 `'message.user'` |
| `skill-miniapp/src/hooks/useSkillStream.ts` | Modify:463-586 | `handleStreamMessage` 新增 `message.user` 分支 |

---

### Task 1: 后端 — StreamMessage 新增 `message.user` 类型

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:165,184`

- [ ] **Step 1: 在 Types 类中新增 MESSAGE_USER 常量**

在 `StreamMessage.java` 第 165 行 `ERROR` 常量后、第 167 行 `SNAPSHOT` 常量前，新增：

```java
public static final String MESSAGE_USER = "message.user";
```

- [ ] **Step 2: 新增 userMessage 静态工厂方法**

在 `StreamMessage.java` 第 212 行 `agentOffline()` 方法后、第 214 行 `@JsonProperty` 前，新增：

```java
/**
 * 创建 message.user 消息（多端同步用户消息）。
 */
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

- [ ] **Step 3: 验证编译通过**

Run: `cd skill-server && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java
git commit -m "feat(skill-server): add MESSAGE_USER type and userMessage() factory to StreamMessage"
```

---

### Task 2: 后端 — SkillMessageController 持久化后广播

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:57-82,114-117`

- [ ] **Step 1: 注入 GatewayMessageRouter 依赖**

在 `SkillMessageController.java` 第 63 行 `objectMapper` 字段后新增字段：

```java
private final GatewayMessageRouter messageRouter;
```

在构造函数参数列表中新增参数 `GatewayMessageRouter messageRouter`，并在构造函数体中赋值 `this.messageRouter = messageRouter;`。

需要新增 import：

```java
import com.opencode.cui.skill.service.GatewayMessageRouter;
```

- [ ] **Step 2: 在 sendMessage 中持久化后、路由前插入广播**

在 `SkillMessageController.java` 第 114 行 `saveUserMessage` 之后、第 117 行 `routeToGateway` 之前，插入：

```java
// 广播用户消息到同会话所有 WebSocket 连接（纯广播，不持久化——消息已由 saveUserMessage 入库）
messageRouter.broadcastStreamMessage(
        sessionId, session.getUserId(),
        StreamMessage.userMessage(
                message.getMessageId(),
                message.getSeq(),
                message.getContent(),
                sessionId));
```

- [ ] **Step 3: 验证编译通过**

Run: `cd skill-server && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git commit -m "feat(skill-server): broadcast message.user after persisting user message for multi-client sync"
```

---

### Task 3: 前端 — types.ts 新增 StreamMessageType

**Files:**
- Modify: `skill-miniapp/src/protocol/types.ts:8-27`

- [ ] **Step 1: 在 StreamMessageType 联合类型中新增 'message.user'**

在 `types.ts` 第 25 行 `'agent.offline'` 之后、第 26 行 `'error'` 之前，新增一行：

```typescript
  | 'message.user'
```

修改后该区域为：

```typescript
  | 'agent.online'
  | 'agent.offline'
  | 'message.user'
  | 'error'
  | 'snapshot'
  | 'streaming';
```

- [ ] **Step 2: 验证 TypeScript 编译通过**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无错误输出

- [ ] **Step 3: Commit**

```bash
git add skill-miniapp/src/protocol/types.ts
git commit -m "feat(skill-miniapp): add message.user to StreamMessageType"
```

---

### Task 4: 前端 — useSkillStream 处理 message.user

**Files:**
- Modify: `skill-miniapp/src/hooks/useSkillStream.ts:463-586`

- [ ] **Step 1: 在 handleStreamMessage 的 switch 中新增 message.user 分支**

在 `useSkillStream.ts` 第 510 行 `case 'question'` 块结束后（`}`）、第 512 行 `case 'step.start'` 之前，插入：

```typescript
      case 'message.user': {
        const messageId = msg.messageId;
        if (!messageId) break;

        // 发送方的乐观更新消息已在 knownUserMessageIdsRef 中，自动跳过
        if (knownUserMessageIdsRef.current.has(messageId)) break;

        const userMsg: Message = {
          id: messageId,
          role: 'user',
          content: msg.content ?? '',
          contentType: 'plain',
          timestamp: msg.emittedAt ? new Date(msg.emittedAt).getTime() : Date.now(),
          messageSeq: msg.messageSeq,
        };

        setMessages((prev) => upsertMessage(prev, userMsg));
        break;
      }
```

说明：
- `knownUserMessageIdsRef` 在每次 `messages` 变化时重建（第 300-306 行），包含所有已知 user 消息的 id
- 发送方在 `sendMessageFn`（第 711 行）中 REST 返回后会用 `normalizeHistoryMessage(saved)` 替换乐观消息，此时 `knownUserMessageIdsRef` 已包含该 id
- 其他端因为没有发送过该消息，`knownUserMessageIdsRef` 中不包含该 id，因此会正常添加
- 使用 `upsertMessage` 而非直接 push 以防止重复（例如 snapshot 恢复场景）

- [ ] **Step 2: 需要确认 Message 类型的 import**

`Message` 类型已在文件顶部 import（来自 `../protocol/types`），无需额外 import。

- [ ] **Step 3: 验证 TypeScript 编译通过**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无错误输出

- [ ] **Step 4: Commit**

```bash
git add skill-miniapp/src/hooks/useSkillStream.ts
git commit -m "feat(skill-miniapp): handle message.user in handleStreamMessage for multi-client sync"
```

---

### Task 5: 端到端验证

- [ ] **Step 1: 后端完整编译验证**

Run: `cd skill-server && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端完整编译验证**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无错误输出

- [ ] **Step 3: 手动端到端测试**

验证步骤：
1. 启动 skill-server 和 skill-miniapp
2. 打开两个浏览器窗口（或标签页），均进入同一对话
3. 在窗口 A 发送消息
4. **验证**：窗口 B 实时收到用户消息（无需刷新）
5. **验证**：窗口 A 不出现重复消息
6. **验证**：AI 回复在用户消息之后到达两端
7. **验证**：刷新页面后，历史消息与实时消息一致
8. 检查数据库，确认用户消息只有一条记录（无重复持久化）

- [ ] **Step 4: Final commit（如有修正）**

```bash
git add -A
git commit -m "fix: adjustments from e2e testing"
```
