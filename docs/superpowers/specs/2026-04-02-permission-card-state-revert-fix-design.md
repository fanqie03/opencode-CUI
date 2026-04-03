# 主会话权限审批按钮状态回退修复方案

## 问题描述

在主会话（不起 subagent）中执行权限审批时：
1. 点击权限按钮后，按钮短暂变为"已处理"状态，随后恢复为可点击的待审批状态
2. 刷新页面后，多个权限卡片中部分显示已处理、部分显示待审批，状态不一致

## 根因分析

权限审批存在**两层 bug**：前端流式状态覆盖 + 后端持久化缺失。

### Bug 1：前端 StreamAssembler 双写不一致（流式过程中按钮回退）

权限状态存在两个数据源：**StreamAssembler**（流式消息的 source of truth）和 **React state**（UI 渲染源）。

当前流程：

1. `permission.ask` 到达 → `StreamAssembler` 创建 part，`permResolved: false` → 同步到 React state
2. 用户点击按钮 → `replyPermissionFn` **只更新 React state**（`permResolved: true`），**未更新 StreamAssembler**
3. 服务端返回 `permission.reply` → 主会话处理器**故意跳过**（`useSkillStream.ts:770-774`，注释："主会话: PermissionCard 通过 local state 显示已处理"）
4. Agent 恢复后发送新消息（`text.delta`、`tool.update` 等）→ 触发 `applyStreamedMessage` → 从 StreamAssembler 重建 parts（`assembler.getParts()`）→ **覆盖 React state 回 `permResolved: false`**

关键代码位置：

- `useSkillStream.ts:487` — `parts: parts.length > 0 ? [...parts] : existing?.parts`：每次 rebuild 从 assembler 取 parts 直接替换
- `useSkillStream.ts:770-774` — 主会话 `permission.reply` 被跳过，assembler 未更新
- `useSkillStream.ts:1072-1094` — `replyPermissionFn` 只更新 React state，未同步 assembler

### Bug 2：后端 permission.reply 未持久化到数据库（刷新后状态丢失）

Controller 的 `replyPermission` 端点（`SkillMessageController.java:391`）调用 `publishProtocolMessage`，但该方法**只做 WebSocket 广播 + 缓冲，不调用 `persistIfFinal`**：

```java
// GatewayMessageRouter.java:785-788
public void publishProtocolMessage(String sessionId, StreamMessage msg) {
    broadcastStreamMessage(sessionId, null, msg);  // WebSocket 推送 ✓
    bufferService.accumulate(sessionId, msg);        // 缓冲 ✓
    // 没有 persistIfFinal ✗
}
```

Permission 的 `tool_output`（response）和 `tool_status`（completed）**从未被直接写入数据库**。

现有的间接持久化机制 `synthesizePermissionReplyFromToolOutcome` 通过 `findLatestPendingPermissionPart`（按 `seq DESC LIMIT 1`）在 tool 完成时推断 permission 状态。但此机制存在缺陷：
- 只匹配**最近一个** pending permission，多个并发 permission 时可能匹配错误
- 依赖 `tool.update(completed)` 到达触发，如果 tool 事件缺失则 permission 永远不被持久化

刷新后前端通过 `history.ts:normalizePart` 根据 `raw.response` / `raw.status` 推导 `permResolved`——数据库中缺失这些字段时，permission 显示为待确认。

## 修复方案

### Bug 1 修复（前端）：StreamAssembler 同步

#### 改动 1：StreamAssembler 新增 `resolvePermission` 方法

**文件**：`skill-miniapp/src/protocol/StreamAssembler.ts`

新增公开方法，允许外部标记 permission part 为已处理：

```ts
resolvePermission(permissionId: string, response: string): boolean {
  const partId = this.findPermissionPartId(permissionId);
  if (!partId) return false;
  const part = this.parts.get(partId);
  if (!part) return false;
  part.permResolved = true;
  part.permissionResponse = response;
  return true;
}
```

- 复用已有 `findPermissionPartId` 查找逻辑
- 返回 `boolean` 表示是否找到并更新
- 不改变任何现有方法的行为

#### 改动 2：`replyPermissionFn` 中同步更新 Assembler

**文件**：`skill-miniapp/src/hooks/useSkillStream.ts`

在 `replyPermissionFn` 的 `setMessages` 调用之前，遍历 `assemblersRef` 更新 assembler：

```ts
// 同步更新 StreamAssembler，防止后续 applyStreamedMessage 重建 parts 时覆盖 permResolved
for (const assembler of assemblersRef.current.values()) {
  if (assembler.resolvePermission(permissionId, response)) {
    break; // permissionId 全局唯一，找到即止
  }
}
```

插入位置：`setMessages((prev) => ...)` 之前（约第 1072 行前）。

### Bug 2 修复（后端）：Controller 直接持久化 permission reply

#### 改动 3：`MessagePersistenceService` 新增公开方法

**文件**：`skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`

将已有的 `updatePermissionReplyByPermissionId` 逻辑暴露为公开方法：

```java
/**
 * 由 Controller 调用：用户点击权限审批按钮后，直接将 response 持久化到数据库。
 * 确保刷新后 permission 状态不丢失。
 */
@Transactional
public boolean persistPermissionReply(Long sessionId, String permissionId, String response) {
    SkillMessagePart existing = partRepository.findByPartId(sessionId, permissionId);
    if (existing == null) {
        log.debug("No existing permission part to update: sessionId={}, permissionId={}",
                sessionId, permissionId);
        return false;
    }
    existing.setToolStatus("completed");
    existing.setToolOutput(response);
    existing.setUpdatedAt(null); // 让 SQL 使用 NOW()
    partRepository.upsert(existing);
    log.info("Persisted permission reply from controller: sessionId={}, permissionId={}, response={}",
            sessionId, permissionId, response);
    return true;
}
```

#### 改动 4：Controller 注入 `MessagePersistenceService` 并调用

**文件**：`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

1. 注入 `MessagePersistenceService`
2. 在 `replyPermission` 方法中，`publishProtocolMessage` 之后调用 `persistPermissionReply`

```java
gatewayRelayService.publishProtocolMessage(sessionId, replyMessage);

// 直接持久化 permission reply，确保刷新后状态不丢失
persistenceService.persistPermissionReply(numericSessionId, permId, request.getResponse());
```

### 修复后完整流程

1. 用户点击 → **前端更新 assembler**（`resolvePermission`）→ 更新 React state → 调 API
2. 后续 `applyStreamedMessage` rebuild → assembler 中已是 `permResolved: true` → 不会覆盖
3. API 到达 Controller → **后端直接持久化** `tool_output` 和 `tool_status` 到数据库
4. 刷新页面 → 历史 API 返回 `response` 字段 → `normalizePart` 推导 `permResolved: true`

## 影响范围

| 文件 | 改动 |
|------|------|
| `StreamAssembler.ts`（前端） | +1 公开方法（~8 行） |
| `useSkillStream.ts`（前端） | +5 行（assembler 同步逻辑） |
| `MessagePersistenceService.java`（后端） | +1 公开方法（~15 行） |
| `SkillMessageController.java`（后端） | +1 字段注入 + 1 行调用 |

不影响 subagent 权限处理路径（subagent 通过 `handleSubagentMessage` 独立处理）。
不影响 `synthesizePermissionReplyFromToolOutcome` 间接机制（幂等：upsert 不会冲突）。

## 验证方式

1. 主会话发起多个权限请求 → 逐一点击审批 → 确认所有按钮保持"已处理"状态不回退
2. 审批后刷新页面 → 确认所有权限卡片状态与点击时一致（全部显示"已处理"）
3. Subagent 场景权限审批行为不受影响（回归测试）
4. 后端日志确认 `Persisted permission reply from controller` 输出
