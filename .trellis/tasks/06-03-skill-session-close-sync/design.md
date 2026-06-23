# Design: skill 会话删除支持多端同步

## 架构：主流程快速返回 + 异步任务批量删除 + 事件驱动分支

```
                         ┌─────────────────────┐
                         │  Controller: DELETE  │
                         │  /api/skill/sessions │
                         │       /{id}          │
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────┐
                         │  FlowService        │
                         │  .deleteSession()   │
                         │                     │
                         │  1. [ACTIVE] abort  │
                         │  2. DELETE session  │──► 主流程（同步，快速返回）
                         │  3. INSERT 异步任务  │
                         │  4. publishEvent()  │
                         └──────────┬──────────┘
                                    │
          ┌─────────────────────────┼─────────────────────────┐
          │                         │                         │
  ┌───────▼──────┐   ┌─────────────▼──────────┐   ┌──────────▼──────┐
  │ @EventListener│   │ @EventListener         │   │ @EventListener  │
  │ WS 推送       │   │ 即时触发异步任务        │   │ Gateway 通知     │
  │ session       │   │ (AsyncTaskCreated)     │   │ close_session    │
  │ .deleted      │   └───────────┬────────────┘   └─────────────────┘
  └──────────────┘               │
                        ┌───────▼──────────┐
                        │ AsyncTaskService │
                        │ 查询 PENDING 任务 │
                        │ 批量删除消息+分片 │
                        │ 更新 COMPLETED   │
                        └──────────────────┘
                                  ↑
                        ┌─────────┴──────────┐
                        │ @Scheduled 定时任务 │──► 兜底（处理事件丢失）
                        │ 每 N 秒扫描 PENDING │
                        └────────────────────┘
```

## 主流程（SkillSessionFlowService.deleteSession）

同步、快速返回：

```
deleteSession(session, userId):
  1. if session.status == ACTIVE → abortSession(session)
  2. int messageCount = messageRepository.countBySessionId(sessionId)
  3. sessionRepository.deleteById(sessionId)                     // 仅删主表
  4. sessionRouteService.closeRoute(sessionId)
  5. asyncTaskRepository.insert(AsyncTask.builder()              // 创建异步任务
         .taskType("DELETE_SESSION_MESSAGES")
         .payload(JSON: sessionId, messageCount)
         .status(PENDING)
         .build())
  6. eventPublisher.publishEvent(new SessionDeletedEvent(         // 发布事件
         session, userId, Instant.now(), messageCount))
  7. return success
```

> `countBySessionId` 已存在于 `SkillMessageRepository`，无需新增。

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

**SessionDeletedEventListener**（三个 listener）：
- `onSessionDeletedWs` — WS 推送 `session.deleted` 到所有设备
- `onSessionDeletedGateway` — 发送 `close_session` invoke 到 Gateway
- `onSessionDeletedTask` — **不直接删数据**，改为插入 `AsyncTask` 并立即尝试执行

**AsyncTaskEventListener**（新增）：
- `onAsyncTaskCreated` — 监听 `AsyncTaskCreatedEvent`，立即异步执行任务

### 批量删除策略

由于 `skill_message_part` 数据量可能很大（每个消息多个 part），需要分批删除：

```
executeDeleteSessionMessages(task):
  1. UPDATE status = PROCESSING
  2. payload 解析出 sessionId
  3. 循环批量删除：
     DELETE FROM skill_message_part WHERE session_id = ? LIMIT 1000
     → 直到 affected_rows = 0
  4. DELETE FROM skill_message WHERE session_id = ?       // 消息量通常不大，一次删
  5. cleanRedisCaches(sessionId)                            // Redis 清理
  6. UPDATE status = COMPLETED
```

### 定时任务（ScheduledJob，兜底）

```java
@Scheduled(fixedDelay = 30_000)  // 每 30 秒
processPendingTasks():
  → 查询 status=PENDING 的 DELETE_SESSION_MESSAGES 任务
  → 逐一执行 deleteSessionMessages()
```

定时任务兜底确保即使 `AsyncTaskCreatedEvent` 丢失，任务也能在 30 秒内被执行。

## 事件定义

```java
// SessionDeletedEvent — 会话已删除，主流程发布
public record SessionDeletedEvent(
    SkillSession session,      // 会话完整快照
    String deletedBy,          // 操作者 cookie userId
    Instant deletedAt,         // 删除时间
    int messageCount           // 被删消息数量
) {}

// AsyncTaskCreatedEvent — 异步任务已创建，AsyncTaskService 发布
public record AsyncTaskCreatedEvent(
    Long taskId,               // 任务 ID
    String taskType,           // DELETE_SESSION_MESSAGES / ...
    String payload             // JSON
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
| `model/event/AsyncTaskCreatedEvent.java` | 异步任务创建事件 record |
| `repository/AsyncTaskRepository.java` + XML | 任务 CRUD |
| `service/AsyncTaskService.java` | 任务创建 + 执行 + 查询 |
| `service/SessionDeletedEventListener.java` | WS/Gateway/Task 三个 listener |
| `service/AsyncTaskEventListener.java` | 即时触发异步任务执行 |

### 修改文件
| 文件 | 改动 |
|------|------|
| `StreamMessage.java` | 新增 `SESSION_DELETED` 类型 + 工厂方法 |
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
