# Design: skill 会话删除支持多端同步

## 架构：主流程事务内创建异步任务 + 事件驱动分支 + 高频超时回收

```
                         ┌─────────────────────┐
                         │  Controller: DELETE  │
                         │  /api/skill/sessions │
                         │       /{id}          │
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────────┐
                         │  FlowService            │
                         │  .deleteSession()       │
                         │                         │
                         │  1. [ACTIVE] abort      │
                         │  2. DELETE session      │──► 主流程（同步，事务内）
                         │  3. INSERT asyncTask    │
                         │  4. publishEvent()      │
                         └──────────┬──────────────┘
                                    │
          ┌───────────────────────┼───────────────────────┼─────────────────────────┐
          │                       │                       │                         │
  ┌───────▼──────┐   ┌───────────▼──────────┐   ┌────────▼──────┐   ┌─────────────▼──────────┐
  │ @EventListener│   │ @EventListener       │   │ @EventListener│   │ @EventListener         │
  │ SessDeleted   │   │ SessDeleted          │   │ RedisMsgBroker│   │                         │
  │ SyncNotifier  │   │ GatewayNotifier      │   │ + StreamBuf   │   │                         │
  │ WS 推送       │   │ Gateway 通知          │   │ 清理缓存       │   │                         │
  └──────────────┘   └──────────────────────┘   └───────────────┘   └────────────────────────┘

                        ┌───────────────────┐
                        │ AsyncTaskService  │
                        │ cron 扫描 PENDING  │
                        │ 分批删除消息+分片  │
                        │ 更新 COMPLETED    │
                        └────────┬──────────┘
                                 │
                        ┌────────┴──────────────┐
                        │ @Scheduled cron        │──► PENDING 兜底扫描（凌晨 2:00）
                        │ @Scheduled fixedDelay  │──► 超时回收（5min）+ 分布式锁
                        │ TaskLeaseManager       │──► Redis 取消信号（跨 JVM 中断）
                        └───────────────────────┘
```

## 主流程（SkillSessionFlowService.deleteSession）

同步、快速返回：

```
deleteSession(session, userId):
  1. if session.status == ACTIVE → abortSession(session)
  2. int messageCount = messageRepository.countBySessionId(sessionId)
  3. sessionRepository.deleteById(sessionId)                     // 仅删主表
  4. sessionRouteService.closeRoute(sessionId)
  5. asyncTaskService.createTask(DELETE_SESSION_MESSAGES,        // 事务内创建，失败回滚
         payload)
  6. eventPublisher.publishEvent(new SessionDeletedEvent(         // 发布事件
         session, userId, Instant.now(), messageCount))
  7. return success
```

> `countBySessionId` 已存在于 `SkillMessageRepository`，无需新增。
>
> 异步任务创建直接在 `deleteSession()` 主流程中调用，与 session 删除在同一 `@Transactional` 内。任务创建失败则异常向上传播，整个事务回滚，session 不会被删除。

## 异步任务表（新增）

### DDL（V16__async_task.sql）

```sql
CREATE TABLE skill_async_task (
    id          BIGINT PRIMARY KEY,
    task_type   VARCHAR(64)  NOT NULL,       -- DELETE_SESSION_MESSAGES / ...
    payload     JSON         NOT NULL,        -- { "sessionId": 123, "messageCount": 50 }
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / COMPLETED / FAILED
    retry_count INT          DEFAULT 0,
    error_msg   VARCHAR(512),
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME
);
CREATE INDEX idx_async_task_status_created ON skill_async_task(status, created_at);
```

### Model

```java
public enum AsyncTaskStatus { PENDING, PROCESSING, COMPLETED, FAILED }

public enum AsyncTaskType {
    DELETE_SESSION_MESSAGES   // 删除会话关联的消息和分片
}
```

## 分支逻辑

### 事件监听器（即时触发 + 定时兜底）

**listener/ 目录下两个独立的 @EventListener 组件**（均为非关键旁路逻辑，失败不影响主流程）：
- `SessionDeletedSyncNotifier` — 监听 `SessionDeletedEvent`，调用 `MultiDeviceSyncService.push(SyncMode.WS, SyncType.SESSION_DELETED, ...)` 推送 `session.deleted` 到所有设备
- `SessionDeletedGatewayNotifier` — 监听 `SessionDeletedEvent`，发送 `close_session` invoke 到 Gateway

> 异步任务创建已从监听器中移出，改在 `SkillSessionFlowService.deleteSession()` 主流程中直接调用，与 session 硬删除在同一事务内，保证原子性。

**缓存清理（各 cache owner 自行监听 SessionDeletedEvent）**：
- `RedisMessageBroker.onSessionDeleted` — 清理 `ss:stream-seq:{sessionId}` + `ss:tool-session:{toolSessionId}`
- `StreamBufferService.onSessionDeleted` — 清理 stream buffer
- 历史消息缓存（`skill:history:latest:*`）不清理，避免 SCAN key 风险

### 异步任务执行架构（策略模式 + 分布式锁 + Redis 取消信号）

```
AsyncTaskService.createTask(type, payload)
  → INSERT task (PENDING)，不立即执行

@Scheduled cron 定时扫描 PENDING 任务
  → asyncTaskExecutor 多线程提交
    → TaskContainer.process(task)
      → 按 taskType 路由到对应 TaskProcessor

TaskProcessor.process(task):
  1. Redisson RLock.tryLock() — 分布式锁防并发
  2. 乐观锁 PENDING → PROCESSING
  3. 循环执行业务逻辑 + TaskLeaseManager.isCancelled() 检查
  4. COMPLETED / FAILED
  5. finally unlock() + clearCancel()
```

**TaskProcessor 接口**：
```java
public interface TaskProcessor {
    AsyncTaskType getTaskType();
    void process(AsyncTask task);
}
```

**TaskContainer**：收集所有 `TaskProcessor`，`getTaskType()` 自注册路由表。

**DeleteSessionMessagesTaskProcessor**：分批删除 part → 分批删除 message（每批 LIMIT 可配置，默认 1000），循环内检查 `TaskLeaseManager.isCancelled()` 响应取消信号。

**TaskLeaseManager**：公共组件，基于 Redis 实现跨 JVM 任务中断。`signalCancel(taskId)` 设置取消标记（TTL 5min），`isCancelled(taskId)` 供 Processor 轮询检查，`clearCancel(taskId)` 清理。

### 定时任务（双 @Scheduled 调度）

```java
// PENDING 兜底扫描（默认每日凌晨 2:00）
@Scheduled(cron = "${skill.session.cleanup.async-task-cron:0 0 2 * * ?}")
processPendingTasks():
  → 查询 PENDING 任务
  → asyncTaskExecutor 多线程提交到 TaskContainer

// 超时回收（默认每 5 分钟，独立高频）
@Scheduled(fixedDelayString = "${...async-task-stale-recovery-interval-ms:300000}")
recoverStaleProcessingTasks():
  → Redisson 分布式锁（ss:async-task-stale-recovery-lock）
  → 查询 PROCESSING 任务，比较 updatedAt 与 staleTimeoutMinutes
  → TaskLeaseManager.signalCancel(taskId) 发送 Redis 取消信号
  → incrementRetryAndReset → PENDING（未超 maxRetry）
  → updateStatusAndError → FAILED（已达 maxRetry）
```

## 事件定义

```java
// SessionDeletedEvent — 会话已删除，主流程发布
public record SessionDeletedEvent(
    SkillSession session,      // 会话完整快照
    String deletedBy,          // 操作者 cookie userId
    Instant deletedAt,         // 删除时间
    int messageCount           // 被删消息数量
) {}
```

## 各层改动汇总

### 新增文件
| 文件 | 说明 |
|------|------|
| `db/migration/V16__async_task.sql` | 异步任务表 |
| `model/AsyncTask.java` | 任务实体 |
| `model/enums/AsyncTaskStatus.java` | PENDING/PROCESSING/COMPLETED/FAILED |
| `model/enums/AsyncTaskType.java` | DELETE_SESSION_MESSAGES |
| `model/event/SessionDeletedEvent.java` | 会话删除事件 record |
| `repository/AsyncTaskRepository.java` + XML | 任务 CRUD |
| `service/AsyncTaskService.java` | 任务创建 + PENDING 扫描 + 独立高频超时回收 |
| `service/task/TaskLeaseManager.java` | Redis 取消信号管理器（跨 JVM 任务中断公共组件） |
| `service/listener/SessionDeletedSyncNotifier.java` | WebSocket 推送 session.deleted（多端同步） |
| `service/listener/SessionDeletedGatewayNotifier.java` | 通知 Gateway 释放 Agent 资源 |

### 修改文件
| 文件 | 改动 |
|------|------|
| `SyncType.java` | 新增 `SESSION_DELETED("session.deleted")` 枚举项 |
| `SkillSessionRepository.java` + XML | 新增 `deleteById` |
| `SkillMessagePartRepository.java` + XML | 新增 `deleteBySessionId`（批量删除用） |
| `SkillMessageRepository.java` + XML | 新增 `deleteBySessionId` |
| `SkillSessionService.java` | 新增 `deleteSession(sessionId)` |
| `SkillSessionFlowService.java` | 新增 `deleteSession(session)` |
| `SkillSessionController.java` | close 迁 `POST /{id}/close` + 新增 `DELETE /{id}` |
| `api.ts` | closeSession 路径 + 新增 deleteSession |
| `useSkillSession.ts` | 暴露 deleteSession |
| WS handler | 监听 session.deleted |

## 兼容性

- `DELETE /api/skill/sessions/{id}` 行为变更：关闭 → 硬删除（breaking change）
- 前端需同步上线
- Gateway 无改动
