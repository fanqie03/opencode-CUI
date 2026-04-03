# IM Inbound 新会话 / ToolSession 重建首条消息不回复修复方案

> 基于提交链 `7805dbe2298f11a3ff2bf95b9c2cfe78a0ee661b^..895d82fb63ba6fbb2c42e415c8c9b22c6e3fa2c3` 梳理。
> 注意：按 Git 常规 `A..B` 计算只有 3 个提交；这里按“包含起点 `7805dbe`”的口径统计，共 4 个提交。

---

## 问题背景

IM inbound 有两类“第一次发消息不回复”的故障：

1. **新会话首条消息不回复**
   - 用户第一次给某个 IM 会话发消息时，`skill-server` 会先创建 `SkillSession`，再异步向 Gateway 发送 `CREATE_SESSION`。
   - 首条用户消息会先作为 pending message 缓存，等 Gateway 回 `session_created` 后再补发 `CHAT`。
   - 如果 `session_created` 后的绑定链路出错，首条消息就不会真正转发到 OpenCode。

2. **toolSession 重建场景首条消息不回复**
   - 已有会话在 `CHAT` 过程中，如果 Gateway/Agent 返回 `session_not_found`，`skill-server` 会触发重建。
   - 重建后需要把“刚才那条导致失败的用户消息”重新发给新建的 toolSession。
   - 如果 pending message 丢失、被覆盖、跨实例不可见，或群聊场景无法从 DB 回补，就会出现“第一条消息没回”的现象。

---

## 根因拆解

### 场景 1：新会话首条消息不回复

调用链如下：

```text
ImInboundController.receiveMessage
  -> session == null
  -> ImSessionManager.createSessionAsync(..., pendingMessage)
  -> GatewayRelayService.rebuildToolSession(...)
  -> SessionRebuildService.rebuildToolSession(..., pendingMessage)
  -> Gateway 返回 session_created
  -> GatewayMessageRouter.handleSessionCreated(...)
  -> 绑定 toolSessionId
  -> retryPendingMessage(s)
  -> 真正发送首条 CHAT
```

原始问题出在 `handleSessionCreated`：

- `sessionService.updateToolSessionId(...)` 之后，又调用了 `sessionRouteService.updateToolSessionId(...)`
- 后者会写已经废弃的 `session_route` 表
- 该表在当前库中已不存在，触发 `SQLSyntaxErrorException`
- 异常导致 `retryPendingMessage()` 被整个跳过，首条消息没有补发到 OpenCode

本质上，这是一个**废弃链路仍在关键路径上执行**的问题。

### 场景 2：重建场景首条消息不回复

这条链路的根因是逐层暴露出来的：

1. **pending message 只存在本机 Caffeine**
   - 发起重建的实例把待重发消息放到本地内存
   - 但 `session_created` 可能被路由到另一台 `skill-server`
   - 另一台实例取不到这条 pending message，导致重建成功但消息未重发

2. **Redis 版本兼容问题**
   - 迁移到 Redis 后，最初用的是 `GETDEL`
   - 当前环境低于 Redis 6.2，不支持该命令
   - 需要退回到 `GET + DEL`

3. **群聊场景无法从 DB 回补消息**
   - 单聊场景可以通过 `findLastUserMessage()` 从 DB 取最近一条用户消息
   - 群聊不落库用户消息，DB 查询返回 `null`
   - 一旦 `session_not_found` 发生，重建后就没有可补发的消息

4. **单值缓存会覆盖并发消息**
   - 如果 pending message 用 Redis String 保存，只能保留一条文本
   - 重建期间多条消息并发进入时，后写会覆盖前写
   - 尤其群聊多人并发时，消息会丢失

---

## 设计目标

这 4 个提交想解决的是一组连续问题，最终目标可以归纳为：

- 新会话创建完成后，首条消息一定能补发
- `session_not_found` 重建完成后，当前失败消息一定能补发
- 多实例部署下，pending message 对所有实例可见
- 单聊和群聊都能恢复消息
- 多条待重发消息按 FIFO 顺序重放
- 兼容 Redis 6.2 以下环境
- 不再依赖已废弃的 `session_route` MySQL 表

---

## 最终方案

### 1. 收敛 `toolSessionId` 绑定链路

`toolSessionId` 的写入只保留一条主链：

- `session_created` 到达后，仅调用 `sessionService.updateToolSessionId(...)`
- 不再写废弃的 `session_route` 表
- `SessionRouteService` 只保留 Redis ownership / relay 相关能力，不再承担 MySQL 回填职责

这一步解决的是：

- 新会话首条消息在 `session_created` 后被异常短路的问题
- `toolSessionId` 数据来源分裂的问题

### 2. 用 Redis 承载重建态缓存

把原先仅限单机可见的状态迁移到 Redis：

| Key | 用途 | 结构 |
|------|------|------|
| `ss:pending-rebuild:{sessionId}` | 暂存待重发用户消息 | 先是 String，最终改为 Redis List |
| `ss:rebuild-counter:{sessionId}` | 重建次数限流 | `INCR + EXPIRE` |

其中：

- pending message TTL 为 **5 分钟**
- rebuild counter TTL 为 `rebuildCooldownSeconds`
- Redis 失败时统一降级放行，不因为缓存异常阻塞主流程

### 3. 新会话首条消息补发机制

新会话的最终时序如下：

```text
1. IM inbound 收到第一条消息
2. createSessionAsync 创建 SkillSession
3. requestToolSession / rebuildToolSession 把首条消息作为 pending message 缓存
4. 向 Gateway 发送 CREATE_SESSION
5. Gateway 回 session_created
6. GatewayMessageRouter.handleSessionCreated 绑定 toolSessionId
7. consume pending message(s)
8. 向 Gateway 补发 CHAT
```

关键点：

- `retryPendingMessage()` 从 `try-catch` 中拆出来
- 只要 `toolSessionId` 绑定成功，就一定进入补发流程

### 4. 重建场景补发机制

当 `tool_error` 告知 `session_not_found` 时：

```text
1. GatewayMessageRouter.handleToolError
2. SessionRebuildService.handleSessionNotFound
3. clearToolSessionId
4. 准备 pending message
5. 发送 CREATE_SESSION
6. session_created 到达
7. drain pending message(s)
8. FIFO 重发 CHAT
```

待重发消息的来源分两种：

- **单聊**：可以从 DB 的 `findLastUserMessage()` 兜底恢复
- **群聊 / Case C**：依赖发送前预缓存，不依赖 DB

### 5. IM inbound 在发送 CHAT 前预缓存当前消息

这是第 4 个提交的关键补洞。

在 `ImInboundController` 的 Case C 中：

- 只要 session 已存在且准备直接发 `CHAT`
- 就先调用 `rebuildService.appendPendingMessage(sessionId, prompt)`
- 再把 `CHAT` 转发给 Gateway

这样即使出现下面的时序：

```text
1. skill-server 已准备发 CHAT
2. Gateway / Agent 返回 session_not_found
3. 旧 toolSession 作废
4. 开始重建
```

当前这条用户消息也已经被提前放进 Redis，可以在 `session_created` 后补发。

这一步是**群聊场景能够恢复首条失败消息**的关键，因为群聊本身没有 DB 用户消息可回溯。

### 6. pending message 从单值升级为 Redis List

最终 pending cache 采用 Redis List：

- 写入：`RPUSH`
- 读取：`LRANGE 0 -1`
- 清理：`DEL`
- 重发顺序：FIFO

这样可以覆盖两类问题：

- 重建期间多条消息连续进入
- 群聊多人并发导致的消息覆盖

### 7. Redis 低版本兼容

在“单值缓存 → Redis”阶段，最初消费逻辑使用 `GETDEL`。

由于线上 Redis 低于 6.2，后续改为：

- `GET`
- 如果存在则再 `DEL`

这个改动不是功能设计变化，而是**环境兼容修正**。

---

## 4 个提交的演进关系

| 提交 | 核心问题 | 改动摘要 |
|------|----------|----------|
| `7805dbe` | 新会话 `session_created` 后首条消息未补发 | 移除对废弃 `session_route` 表的写入；`retryPendingMessage()` 从异常块中拆出；清理 `SessionRouteService` 的 MySQL 遗留代码 |
| `5a75ad3` | 多实例下 pending message 丢失 | `SessionRebuildService` 的 pending cache / rebuild counter 从 Caffeine 迁移到 Redis |
| `948f911` | Redis 低版本不支持 `GETDEL` | `consumePendingMessage()` 改为 `GET + DEL` |
| `895d82f` | 群聊重建无 DB 消息可恢复；并发消息被覆盖 | pending cache 从 String 升级为 Redis List；Case C 发 `CHAT` 前预缓存；`retryPendingMessage` 升级为 `retryPendingMessages`，按 FIFO 批量重发 |

---

## 涉及文件

### 直接修改

| 文件 | 作用 |
|------|------|
| `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` | `session_created` 后绑定 + retry；`tool_error` 触发重建 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java` | pending cache、重建计数、消息恢复、重发逻辑 |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java` | Case C 发 `CHAT` 前预缓存当前消息 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java` | 删除 MySQL 遗留职责，仅保留 Redis route ownership 能力 |

### 被删除的遗留文件

| 文件 | 删除原因 |
|------|----------|
| `skill-server/src/main/java/com/opencode/cui/skill/model/SessionRoute.java` | 废弃的 `session_route` 表模型 |
| `skill-server/src/main/java/com/opencode/cui/skill/repository/SessionRouteRepository.java` | 废弃表访问层 |
| `skill-server/src/main/resources/mapper/SessionRouteMapper.xml` | 废弃表 Mapper |

---

## 方案价值

这 4 个提交完成后，系统具备了下面这套能力：

- 新会话首条消息可以在 `session_created` 后可靠补发
- `session_not_found` 重建场景可以恢复失败消息
- 多实例部署下，重建状态不再依赖本机内存
- 群聊场景不再受“用户消息不落库”限制
- 多条待重发消息可以顺序回放

---

## 当时方案的已知边界

这 4 个提交修复了“第一次发消息不回复”的主问题，但从后续提交可以看出，当时方案仍有两个边界没有完全收口：

1. **pending List 只依赖 TTL 过期，没有在 `tool_done` 后立即清理**
   - 会导致持续对话时消息在 List 中累积
   - 后续在 `2026-04-01-im-inbound-pending-message-accumulation-fix-design.md` 中补了 `handleToolDone -> clearPendingMessages(sessionId)`

2. **重建入口还没有分布式锁，也没有避免单聊 DB 回补与 Case C 预缓存重复追加**
   - 并发 `session_not_found` 时仍可能重复触发重建
   - 单聊场景也可能出现同一消息被追加两次
   - 后续同样在 `2026-04-01-im-inbound-pending-message-accumulation-fix-design.md` 中补了锁和 `peekPendingMessages()`

也就是说，这 4 个提交完成的是**首轮可用修复**，后续又围绕“重复发送”和“并发重建”做了第二轮收口。

---

## 建议验证矩阵

| 场景 | 预期 |
|------|------|
| 新建单聊会话发送第一条消息 | `session_created` 后自动补发首条 `CHAT`，用户能收到回复 |
| 已有会话但 `toolSessionId` 为空 | 自动请求重建，消息在重建后补发 |
| 单聊触发 `session_not_found` | 可从 DB / pending cache 恢复当前消息 |
| 群聊触发 `session_not_found` | 依赖 Case C 预缓存恢复当前消息，不依赖 DB |
| 多实例下 A 发起重建、B 收到 `session_created` | B 仍能从 Redis 读到 pending message 并补发 |
| Redis 低版本环境 | 消费 pending message 不依赖 `GETDEL`，兼容运行 |
| 重建期间连续多条消息进入 | Redis List 保留多条消息，按 FIFO 顺序重放 |

