# IM Inbound 预缓存消息累积导致重建后重复发送修复

## 问题描述

使用 IM inbound 接口时，如果 toolSessionId 在 OpenCode 端不存在（例如被手动删除），触发重建后会把之前多轮对话的消息全部重发给 OpenCode，导致用户收到大量重复的 AI 回复。

### 根因

`ImInboundController` Case C 在每次 CHAT 发送前调用 `rebuildService.appendPendingMessage()` 将消息追加到 Redis List（`ss:pending-rebuild:{sessionId}`）作为预缓存安全网。但 CHAT 成功后（`handleToolDone`），该 List **从未被清理**。每次 `appendPendingMessage` 还会刷新整个 List 的 5 分钟 TTL，导致消息在持续对话期间无限累积。

当 toolSession 失效触发重建时，`retryPendingMessages` 将 Redis List 中所有累积的消息一口气重发。

### 附带问题

1. **重建路径无并发保护**：`rebuildFromStoredUserMessage` 没有分布式锁，多个并发请求同时触发重建时，会发送多次 `CREATE_SESSION`，导致 OpenCode 上产生孤儿 toolSession。
2. **单聊消息重复**：`rebuildFromStoredUserMessage` 从 DB 查最近用户消息并追加到 Redis List，但该消息已在 Case C 预缓存中存在，导致同一条消息被发两次。

## 修复方案

三处改动，均在 `skill-server` 后端，前端无改动。

### 改动 1：handleToolDone 清理预缓存

**文件**：`GatewayMessageRouter.java` — `handleToolDone` 方法

**改动**：在方法末尾新增一行调用 `rebuildService.clearPendingMessages(sessionId)`。

**效果**：CHAT 成功完成后，清理该 session 的 Redis 预缓存 List，防止消息累积。

### 改动 2：rebuildFromStoredUserMessage 加分布式锁

**文件**：`SessionRebuildService.java` — `rebuildFromStoredUserMessage` 方法

**改动**：在方法入口加 Redis 分布式锁（`ss:rebuild-lock:{sessionId}`，TTL 15s），获取失败则跳过（当前消息已在 Redis List 中，重建完成后统一重发）。

**锁 key**：`ss:rebuild-lock:{sessionId}`
**TTL**：15 秒
**释放**：try-finally 中安全释放（仅释放自己持有的锁）

**效果**：多个并发请求触发 session_not_found 时，只有一个执行重建（clearToolSessionId + CREATE_SESSION），其余请求跳过。所有请求的消息已通过 Case C 预缓存在 Redis List 中，重建完成后由 `retryPendingMessages` 统一重发。

### 改动 3：rebuildFromStoredUserMessage 跳过重复 DB 追加

**文件**：`SessionRebuildService.java` — `rebuildFromStoredUserMessage` 方法

**改动**：在查询 DB 前，先用 `peekPendingMessages` 检查 Redis List 是否已有消息。如果已有（来自 Case C 预缓存），跳过 DB 查询，不再追加。

**新增方法**：`peekPendingMessages(sessionId)` — 只读不消费，返回 Redis List 当前内容。

**效果**：避免单聊场景下 Case C 预缓存和 DB 查询追加同一条消息导致的重复发送。群聊不受影响（群聊不存 DB，`findLastUserMessage` 返回 null）。

## 涉及文件

| 文件 | 改动类型 |
|------|----------|
| `skill-server/.../service/GatewayMessageRouter.java` | 修改 `handleToolDone` |
| `skill-server/.../service/SessionRebuildService.java` | 修改 `rebuildFromStoredUserMessage`，新增 `peekPendingMessages` |

## 不改动的部分

- `ImInboundController` Case C 的 `appendPendingMessage` 保留（群聊安全网）
- `retryPendingMessages` 消费逻辑不变
- Case B 路径（重建期间新消息进入）不变
- 前端无改动

## 场景验证矩阵

| 场景 | 预期行为 |
|------|----------|
| 正常单聊多轮对话 | 每次 tool_done 清理 List，不累积 |
| 正常群聊多轮对话 | 同上 |
| 删除 toolSession 后单聊发消息 | 只重发当前消息，不重发历史 |
| 删除 toolSession 后群聊发消息 | 同上（预缓存保留当前消息） |
| 并发多请求同时触发 session_not_found | 只执行一次重建，消息统一重发，无孤儿 toolSession |
| 重建期间（toolSessionId=null）发送多条消息 | 走 Case B，全部进入 pending-rebuild List，session_created 后逐条重发 |
| 5 分钟无对话后 Redis List 自动过期 | TTL 机制不变，过期后 List 自动清除 |
