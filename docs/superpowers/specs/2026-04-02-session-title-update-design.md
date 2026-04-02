# 会话标题更新能力

## 目标

将现有的"标题为空时回填"逻辑改为"标题变化时更新"，支持 OpenCode agent 多次更新会话标题。

## 背景

当前实现中，OpenCode agent 生成的会话标题只在 welink session 标题为空时写入（一次性回填）。后端 SQL 有 `WHERE title IS NULL OR title = ''` 守卫，前端有 `!s.title` 守卫。这导致 AI 后续重新生成的标题无法同步到 welink session。

## 修改点

### 1. 后端 SQL（SkillSessionMapper.xml）

将 `updateTitleIfEmpty` 的 SQL 条件从"标题为空才更新"改为"标题不同才更新"：

```sql
UPDATE skill_session
SET title = #{title}, last_active_at = NOW()
WHERE id = #{id} AND (title IS NULL OR title != #{title})
```

### 2. 后端 Repository（SkillSessionRepository.java）

重命名方法 `updateTitleIfEmpty` → `updateTitle`。

### 3. 后端 Service（SkillSessionService.java）

重命名 `updateTitleIfEmpty` → `updateTitle`，保留 null/blank 入参校验，去掉"仅空标题更新"的语义。

### 4. 后端 Router（GatewayMessageRouter.java）

调用处从 `sessionService.updateTitleIfEmpty()` 改为 `sessionService.updateTitle()`。

### 5. 前端（useSkillSession.ts）

`updateSessionTitle` 回调中将 `!s.title` 守卫改为 `s.title !== title`：

```typescript
prev.map((s) => (s.id === sessionId && s.title !== title ? { ...s, title } : s))
```

同时更新 `setCurrentSession` 的对应逻辑。

## 不需要改动的部分

- `OpenCodeEventTranslator` — 已正确翻译 `session.updated` → `session.title`
- `useSkillStream` — 已正确分发 `session.title` 消息
- `StreamMessage` 类型定义 — 无变化
- `SessionSidebar` — 已正确渲染 `session.title`

## 数据流

```
OpenCode emits session.updated (new title)
  → EventTranslator → session.title StreamMessage
  → GatewayMessageRouter → sessionService.updateTitle()
    → SQL: UPDATE WHERE title IS NULL OR title != newTitle
  → WebSocket broadcast → frontend
  → updateSessionTitle() → s.title !== newTitle ? update : skip
```

## 验证标准

- [ ] AI 首次生成标题时正常写入（原有行为不变）
- [ ] AI 重新生成不同标题时，welink session 标题同步更新
- [ ] AI 发送相同标题时，不触发无意义的 DB 写入和前端刷新
- [ ] 标题为 null/blank 时不更新（入参校验不变）
