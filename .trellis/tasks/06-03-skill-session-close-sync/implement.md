# Implement: skill 会话删除支持多端同步

## 实施顺序

### Step 1: 基础设施（可并行）

**Repository 层**：
- [ ] `SkillMessagePartRepository` 新增 `deleteBySessionId(@Param("sessionId") Long sessionId)` + XML SQL
- [ ] `SkillMessageRepository` 新增 `deleteBySessionId(@Param("sessionId") Long sessionId)` + XML SQL
- [ ] `SkillSessionRepository` 新增 `deleteById(@Param("id") Long id)` + XML SQL

**事件定义**：
- [ ] 新建 `model/event/SessionDeletedEvent.java`
  ```java
  public record SessionDeletedEvent(
      SkillSession session,
      String deletedBy,
      Instant deletedAt,
      int messageCount
  ) {}
  ```

**StreamMessage**：
- [ ] `Types` 新增 `SESSION_DELETED = "session.deleted"`
- [ ] 新增静态工厂方法 `sessionDeleted()`

### Step 2: Service 层（主流程）

- [ ] `SkillSessionService.deleteSession(Long sessionId)`：@Transactional 硬删除 + afterCommit Redis 清理
- [ ] `SkillSessionFlowService.deleteSession(SkillSession session)`：
  - if ACTIVE → abortSession(session)
  - sessionService.deleteSession(sessionId)
  - 查询 messageCount
  - eventPublisher.publishEvent(new SessionDeletedEvent(...))

### Step 3: 事件监听器（分支逻辑）

- [ ] 新建 `service/SessionDeletedEventListener.java`（@Component），三个 @EventListener：
  - `onSessionDeletedRedis`：清理 Redis 缓存（afterCommit）
  - `onSessionDeletedGateway`：发送 close_session invoke
  - `onSessionDeletedWs`：emitToClient session.deleted 事件
  - 每个方法顶层 try-catch，异常不传播

### Step 4: Controller 层

- [ ] `SkillSessionController`：
  - 现有 close 方法改为 `@PostMapping("/{id}/close")`
  - 新增 `@DeleteMapping("/{id}")` deleteSession 方法

### Step 5: 前端

- [ ] `api.ts`：closeSession 路径改为 `POST /{id}/close`
- [ ] `api.ts`：新增 `deleteSession(id)` 函数
- [ ] `useSkillSession.ts`：暴露 `deleteSession` 方法
- [ ] WS handler：监听 `session.deleted` 事件，处理 UI 更新

## 验证

```bash
cd skill-server && mvn compile
cd skill-miniapp && npx tsc --noEmit
```
