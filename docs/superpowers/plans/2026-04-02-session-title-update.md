# 会话标题更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会话标题从"空时回填"改为"变化时更新"，使 OpenCode agent 重新生成的标题能同步到 welink session。

**Architecture:** 修改后端 SQL 条件从 `title IS NULL OR title = ''` 改为 `title IS NULL OR title != #{title}`，重命名 Repository/Service 方法，前端守卫从 `!s.title` 改为 `s.title !== title`。

**Tech Stack:** Java (Spring Boot / MyBatis), TypeScript (React Hooks)

---

### Task 1: 后端 SQL 和 Repository

**Files:**
- Modify: `skill-server/src/main/resources/mapper/SkillSessionMapper.xml:191-195`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/repository/SkillSessionRepository.java:97-98`

- [ ] **Step 1: 修改 MyBatis SQL**

在 `SkillSessionMapper.xml` 中，将 `updateTitleIfEmpty` 改为 `updateTitle`，SQL 条件改为标题不同时才更新：

```xml
    <update id="updateTitle">
        UPDATE skill_session
        SET title = #{title}, last_active_at = NOW()
        WHERE id = #{id} AND (title IS NULL OR title != #{title})
    </update>
```

- [ ] **Step 2: 修改 Repository 接口**

在 `SkillSessionRepository.java` 中，将方法重命名并更新注释：

```java
    /** 当标题发生变化时更新会话标题 */
    int updateTitle(@Param("id") Long id, @Param("title") String title);
```

---

### Task 2: 后端 Service

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java:177-189`

- [ ] **Step 1: 重命名 Service 方法**

将 `updateTitleIfEmpty` 改为 `updateTitle`，更新注释和日志，保留入参校验：

```java
    /** 当会话标题发生变化时，更新为 AI 生成的新标题。 */
    @Transactional
    public boolean updateTitle(Long sessionId, String title) {
        if (sessionId == null || title == null || title.isBlank()) {
            return false;
        }
        int updated = sessionRepository.updateTitle(sessionId, title);
        if (updated > 0) {
            log.info("Updated session title: id={}, title={}", sessionId, title);
            return true;
        }
        return false;
    }
```

---

### Task 3: 后端 Router 调用处

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:484-487`

- [ ] **Step 1: 更新调用方法名**

将 `routeAssistantMessage` 中的调用从 `updateTitleIfEmpty` 改为 `updateTitle`，同时更新注释：

```java
        // 同步会话标题（标题变化时更新）
        if (StreamMessage.Types.SESSION_TITLE.equals(msg.getType()) && numericId != null) {
            sessionService.updateTitle(numericId, msg.getTitle());
        }
```

---

### Task 4: 后端编译验证

- [ ] **Step 1: 编译后端项目**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 2: 确认无残留引用**

在项目中搜索 `updateTitleIfEmpty`，确认已无任何引用。

---

### Task 5: 前端 useSkillSession

**Files:**
- Modify: `skill-miniapp/src/hooks/useSkillSession.ts:100-110`

- [ ] **Step 1: 修改 updateSessionTitle 守卫条件**

将 `!s.title` 改为 `s.title !== title`，对 `setSessions` 和 `setCurrentSession` 都做同样修改：

```typescript
  const updateSessionTitle = useCallback(
    (sessionId: string, title: string) => {
      setSessions((prev) =>
        prev.map((s) => (s.id === sessionId && s.title !== title ? { ...s, title } : s)),
      );
      setCurrentSession((prev) =>
        prev && prev.id === sessionId && prev.title !== title ? { ...prev, title } : prev,
      );
    },
    [],
  );
```

---

### Task 6: 前端构建验证

- [ ] **Step 1: TypeScript 类型检查**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 2: 构建验证**

Run: `cd skill-miniapp && npm run build`
Expected: 构建成功

---

### Task 7: 提交

- [ ] **Step 1: 提交所有改动**

```bash
git add skill-server/src/main/resources/mapper/SkillSessionMapper.xml \
       skill-server/src/main/java/com/opencode/cui/skill/repository/SkillSessionRepository.java \
       skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java \
       skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java \
       skill-miniapp/src/hooks/useSkillSession.ts
git commit -m "feat(session): update title on change instead of only when empty"
```
