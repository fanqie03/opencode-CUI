# Implement: skill 会话删除支持多端同步

## 实施顺序

### Step 1: 基础设施（可并行）

**Repository 层**：
- [ ] `SkillMessagePartRepository` 新增 `deleteBySessionId(@Param("sessionId") Long sessionId)` + XML SQL
- [ ] `SkillMessageRepository` 新增 `deleteBySessionId(@Param("sessionId") Long sessionId)` + XML SQL
- [ ] `SkillSessionRepository` 新增 `deleteById(@Param("id") Long id)` + XML SQL

**事件定义**：
- [ ] 新建 `model/event/SessionDeletedEvent.java`

**SyncType**：
- [ ] 新增 `SESSION_DELETED("session.deleted")` 枚举项

**异步任务基础设施**：
- [ ] `db/migration/V16__async_task.sql` — DDL
- [ ] `model/AsyncTask.java` + `enums/AsyncTaskStatus.java` + `enums/AsyncTaskType.java`
- [ ] `repository/AsyncTaskRepository.java` + XML（含 updateStatusCas / incrementRetryAndReset 带 status='PROCESSING' 守卫）
- [ ] `service/task/TaskProcessor.java` 接口
- [ ] `service/task/TaskContainer.java` — 按 AsyncTaskType 路由
- [ ] `service/task/DeleteSessionMessagesTaskProcessor.java` — Redisson RLock + @Transactional 批量删除

**Redisson 配置**：
- [ ] pom.xml 新增 `redisson-spring-boot-starter`
- [ ] `config/RedissonConfig.java`

### Step 2: Service 层（主流程）

- [ ] `SkillSessionService.deleteSession(Long sessionId)`：@Transactional 硬删除
- [ ] `SkillSessionFlowService.deleteSession(SkillSession session)`：
  - if ACTIVE → abortSession(session)
  - sessionService.deleteSession(sessionId)
  - 查询 messageCount
  - eventPublisher.publishEvent(new SessionDeletedEvent(...))

### Step 3: 事件监听器（分支逻辑）

- [ ] 新建 `service/listener/DeleteSessionMessagesTaskCreator.java`：监听 `SessionDeletedEvent`，调用 `AsyncTaskService.createTask()` 仅登记 PENDING
- [ ] 新建 `service/listener/SessionDeletedGatewayNotifier.java`：监听 `SessionDeletedEvent`，发送 close_session invoke
- [ ] 新建 `service/listener/SessionDeletedWsNotifier.java`：监听 `SessionDeletedEvent`，调用 `MultiDeviceSyncService.push(SyncMode.WS, SyncType.SESSION_DELETED, ...)`
- [ ] `service/AsyncTaskService.java`：`createTask()` + `@Scheduled(cron)` 扫描调度 → `asyncTaskExecutor` → `TaskContainer`
- [ ] `RedisMessageBroker.onSessionDeleted`：清理 ss:stream-seq + ss:tool-session
- [ ] `StreamBufferService.onSessionDeleted`：清理 stream buffer
- [ ] `config/AsyncConfig.java`：新增 `asyncTaskExecutor` 线程池

### Step 4: Controller 层

- [ ] `SkillSessionController`：
  - 现有 close 改为 `@PostMapping("/{id}/close")`
  - 新增 `@DeleteMapping("/{id}")` deleteSession
- [ ] `SkillSessionService` 内部查询默认排除 CLOSED

### Step 5: 前端

- [ ] `api.ts`：closeSession → `POST /{id}/close`，新增 `deleteSession`
- [ ] `useSkillSession.ts`：`removeSessionLocally` / `deleteSessionFn` 使用函数式 `setCurrentSession` 避免闭包竞态
- [ ] `useSkillStream.ts`：`session.deleted` 绕过 session filter，确保非当前会话也能同步删除
- [ ] `protocol/types.ts`：StreamMessageType 新增 `session.deleted`

## 验证

```bash
cd skill-server && mvn compile
cd skill-miniapp && npx tsc --noEmit
cd skill-server && mvn test
```
