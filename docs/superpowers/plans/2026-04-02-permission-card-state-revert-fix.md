# 主会话权限审批按钮状态回退修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复主会话中点击权限审批按钮后状态被流式消息 rebuild 覆盖回退的 bug，并修复刷新后 permission 状态未持久化导致的状态丢失。

**Architecture:** 前端：在 `StreamAssembler` 新增 `resolvePermission` 方法，在 `replyPermissionFn` 中同步更新 assembler。后端：在 `MessagePersistenceService` 新增 `persistPermissionReply` 方法，Controller `replyPermission` 端点直接持久化 permission 回复到数据库。

**Tech Stack:** React 18, TypeScript 5.3+, Vite 5, Spring Boot 3.4, Java 21, MyBatis

---

### Task 1: StreamAssembler 新增 `resolvePermission` 方法

**Files:**
- Modify: `skill-miniapp/src/protocol/StreamAssembler.ts:293` (在 `complete()` 方法之前插入)

- [ ] **Step 1: 添加 `resolvePermission` 方法**

在 `StreamAssembler` 类中，`complete()` 方法（第 295 行）之前，插入以下方法：

```ts
  /** Mark a permission part as resolved (used by replyPermission to keep assembler in sync) */
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

- [ ] **Step 2: 运行 typecheck 验证**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无类型错误（`resolvePermission` 使用的 `permResolved` 和 `permissionResponse` 字段在 `MessagePart` 接口中已定义）

- [ ] **Step 3: Commit**

```bash
git add skill-miniapp/src/protocol/StreamAssembler.ts
git commit -m "feat(skill-miniapp): add resolvePermission method to StreamAssembler"
```

---

### Task 2: `replyPermissionFn` 中同步更新 Assembler

**Files:**
- Modify: `skill-miniapp/src/hooks/useSkillStream.ts:1069` (`replyPermissionFn` 函数体内，`setMessages` 调用之前)

- [ ] **Step 1: 在 `replyPermissionFn` 中插入 assembler 同步逻辑**

在 `useSkillStream.ts` 的 `replyPermissionFn` 中，在第 1070 行注释 `// 立即更新 messages state...` 之前，插入以下代码：

```ts
      // 同步更新 StreamAssembler，防止后续 applyStreamedMessage 重建 parts 时覆盖 permResolved
      for (const assembler of assemblersRef.current.values()) {
        if (assembler.resolvePermission(permissionId, response)) {
          break;
        }
      }

```

插入后，`replyPermissionFn` 函数体变为：

```ts
  const replyPermissionFn = useCallback(
    async (permissionId: string, response: 'once' | 'always' | 'reject', subagentSessionId?: string) => {
      if (!sessionId) {
        return;
      }

      // 同步更新 StreamAssembler，防止后续 applyStreamedMessage 重建 parts 时覆盖 permResolved
      for (const assembler of assemblersRef.current.values()) {
        if (assembler.resolvePermission(permissionId, response)) {
          break;
        }
      }

      // 立即更新 messages state 中 permission part 的 permResolved
      // 防止后续 re-render 时 PermissionCard 的 useEffect 重置 resolved 状态
      setMessages((prev) =>
        prev.map((message) => ({
          ...message,
          parts: (message.parts ?? []).map((p) => {
            // 直接在 message parts 中匹配
            if (p.type === 'permission' && p.permissionId === permissionId) {
              return { ...p, permResolved: true, permissionResponse: response };
            }
            // SubtaskBlock 内的 subParts 中匹配
            if (p.type === 'subtask' && p.subParts?.length) {
              return {
                ...p,
                subParts: p.subParts.map((sp) =>
                  sp.type === 'permission' && sp.permissionId === permissionId
                    ? { ...sp, permResolved: true, permissionResponse: response }
                    : sp,
                ),
              };
            }
            return p;
          }),
        })),
      );

      setError(null);
      try {
        await api.replyPermission(sessionId, permissionId, response, subagentSessionId);
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to reply permission';
        setError(message);
      }
    },
    [sessionId],
  );
```

- [ ] **Step 2: 运行 typecheck 验证**

Run: `cd skill-miniapp && npx tsc --noEmit`
Expected: 无类型错误（`assemblersRef` 是 `useRef<Map<string, StreamAssembler>>` 类型，在 `replyPermissionFn` 闭包内可直接访问）

- [ ] **Step 3: 运行 build 验证**

Run: `cd skill-miniapp && npx vite build`
Expected: 构建成功，无错误

- [ ] **Step 4: Commit**

```bash
git add skill-miniapp/src/hooks/useSkillStream.ts
git commit -m "fix(skill-miniapp): sync assembler on permission reply to prevent state revert"
```

---

### Task 3: `MessagePersistenceService` 新增 `persistPermissionReply` 公开方法

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java:286` （在 `updatePermissionReplyByPermissionId` 方法之后插入）

- [ ] **Step 1: 添加 `persistPermissionReply` 公开方法**

在 `MessagePersistenceService` 类中，`updatePermissionReplyByPermissionId` 方法（第 286 行）之后，`persistFilePart` 方法之前，插入以下方法：

```java
    /**
     * 由 Controller 调用：用户点击权限审批按钮后，直接将 response 持久化到数据库。
     * 确保刷新后 permission 状态不丢失（不依赖 synthesize 间接机制）。
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

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git commit -m "feat(skill-server): add persistPermissionReply method for direct DB persistence"
```

---

### Task 4: Controller 注入 `MessagePersistenceService` 并调用

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

- [ ] **Step 1: 注入 `MessagePersistenceService`**

在 `SkillMessageController.java` 中：

1. 添加 import：
```java
import com.opencode.cui.skill.service.MessagePersistenceService;
```

2. 添加字段（在 `private final GatewayMessageRouter messageRouter;` 之后）：
```java
    private final MessagePersistenceService persistenceService;
```

3. 更新构造函数签名，添加参数 `MessagePersistenceService persistenceService`（在 `GatewayMessageRouter messageRouter` 之后），以及函数体中添加赋值 `this.persistenceService = persistenceService;`：

```java
    public SkillMessageController(SkillMessageService messageService,
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            GatewayApiClient gatewayApiClient,
            AssistantIdProperties assistantIdProperties,
            ImMessageService imMessageService,
            ObjectMapper objectMapper,
            SessionAccessControlService accessControlService,
            GatewayMessageRouter messageRouter,
            MessagePersistenceService persistenceService) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.gatewayApiClient = gatewayApiClient;
        this.assistantIdProperties = assistantIdProperties;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.messageRouter = messageRouter;
        this.persistenceService = persistenceService;
    }
```

- [ ] **Step 2: 在 `replyPermission` 方法中调用持久化**

在 `replyPermission` 方法中，`gatewayRelayService.publishProtocolMessage(sessionId, replyMessage);`（第 391 行）之后，插入：

```java
        // 直接持久化 permission reply，确保刷新后状态不丢失
        try {
            persistenceService.persistPermissionReply(numericSessionId, permId, request.getResponse());
        } catch (Exception e) {
            log.warn("Failed to persist permission reply: sessionId={}, permId={}, error={}",
                    sessionId, permId, e.getMessage());
        }
```

- [ ] **Step 3: 更新测试**

在 `SkillMessageControllerTest.java` 中：

1. 添加 import：
```java
import com.opencode.cui.skill.service.MessagePersistenceService;
```

2. 添加 mock 字段（在 `private SessionAccessControlService accessControlService;` 之后）：
```java
    @Mock
    private MessagePersistenceService persistenceService;
```

3. 更新 `setUp()` 中的构造函数调用，添加 `persistenceService` 参数：
```java
        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService,
                gatewayApiClient, assistantIdProperties, imMessageService, new ObjectMapper(),
                accessControlService, mock(GatewayMessageRouter.class), persistenceService);
```
注意：现有测试缺少 `GatewayMessageRouter` 参数，需要同时补上 `mock(GatewayMessageRouter.class)`。

4. 在 `permissionReplyOnce200` 测试中，添加持久化验证：
```java
        verify(persistenceService).persistPermissionReply(1L, "p-abc", "once");
```

- [ ] **Step 4: 编译并运行测试**

Run: `cd skill-server && mvn compile test -q`
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git add skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
git commit -m "fix(skill-server): persist permission reply in controller to survive refresh"
```

---

### Task 5: 手动验证

- [ ] **Step 1: 验证主会话权限审批不回退**

1. 启动 dev server: `cd skill-miniapp && npm run dev`
2. 在主会话（不起 subagent）触发多个权限请求
3. 逐一点击审批按钮
4. 确认：所有按钮点击后保持"已处理"状态，不回退为可点击状态

- [ ] **Step 2: 验证刷新后状态一致**

1. 审批完所有权限后刷新页面
2. 确认：所有权限卡片状态与点击时一致（全部显示"已处理"）
3. 后端日志确认 `Persisted permission reply from controller` 输出

- [ ] **Step 3: 验证 subagent 权限审批不受影响**

1. 在有 subagent 的会话中触发权限请求
2. 点击 SubtaskBlock 内的权限审批按钮
3. 确认：subagent 权限审批行为正常，状态不回退
