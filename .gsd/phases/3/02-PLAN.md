---
phase: 3
plan: 2
wave: 1
---

# Plan 3.2: Abort API + abort_session Invoke

## Objective
新增 `POST /api/skill/sessions/{id}/abort` REST API，向 Gateway 发送 `invoke.abort_session`。这补齐 P0/P1 级别功能缺失。

## Context
- .gsd/SPEC.md
- skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java
- skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java

## Tasks

<task type="auto">
  <name>新增 abort 端点</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java
  </files>
  <action>
    1. 新增 `@PostMapping("/{id}/abort")` 方法 `abortSession(@PathVariable Long id)`
    2. 逻辑：
       - 查询 session，验证存在 + 非 CLOSED
       - 若 `session.getAgentId() != null && session.getToolSessionId() != null`：
         - 构建 payload `{"toolSessionId":"..."}`
         - 调用 `gatewayRelayService.sendInvokeToGateway(agentId, sessionId, "abort_session", payload)`
       - 调用 `sessionService.closeSession(id)`（abort 后标记关闭）
       - 返回 `{"status":"aborted","sessionId":"..."}`
    - closeSession 保持不变（它做正常关闭），abort 是中止正在进行的 AI 操作
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，新增 abort 端点</done>
</task>

<task type="auto">
  <name>补充 abort 测试用例</name>
  <files>
    skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java
  </files>
  <action>
    1. 新增 `abortSession200()` 测试：
       - 验证返回 200，状态为 "aborted"
       - 验证调用了 `sendInvokeToGateway` 且 action 为 `"abort_session"`
       - 验证调用了 `sessionService.closeSession()`
    2. 新增 `abortSession404()` 测试：session 不存在返回 404
  </action>
  <verify>mvn test -pl skill-server -Dtest=SkillSessionControllerTest -q</verify>
  <done>abort 端点测试通过</done>
</task>

## Success Criteria
- [ ] `POST /api/skill/sessions/{id}/abort` 端点可用
- [ ] 生成 `invoke.abort_session` 发给 Gateway
- [ ] Session 标记为 CLOSED
- [ ] 测试通过
