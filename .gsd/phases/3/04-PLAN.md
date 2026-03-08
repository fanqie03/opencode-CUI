---
phase: 3
plan: 4
wave: 2
---

# Plan 3.4: invoke 路由 agentId→ak + 创建会话字段对齐

## Objective
将 Skill Server 的 `sendInvokeToGateway` 路由字段从 `agentId` 改为 `ak`（与 Phase 2 Gateway 侧一致），同时将创建会话 API 的字段对齐目标协议。P1 级别修复。

## Context
- .gsd/SPEC.md
- skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
- skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java
- skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java
- skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java

## Tasks

<task type="auto">
  <name>SkillSession 模型 + sendInvokeToGateway 参数改为 ak</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java
    skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
  </files>
  <action>
    1. `SkillSession.java`:
       - `agentId: Long` → `ak: String`
       - `imChatId: String` → `imGroupId: String`
       - 保留 `skillDefinitionId` 字段（DB 可能还需要），但 REST API 不再接收
    2. `GatewayRelayService.sendInvokeToGateway()`:
       - 参数名 `agentId` → `ak`
       - JSON 构建 `message.put("agentId", agentId)` → `message.put("ak", ak)`
       - Javadoc 和 log 消息同步更新
    3. 所有调用 sendInvokeToGateway 的地方：
       - SkillSessionController: `session.getAgentId().toString()` → `session.getAk()`
       - SkillMessageController: 同上
    - 保持 agentId 的 null check 逻辑，改为 ak 的 null check
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，sendInvokeToGateway 使用 ak 路由</done>
</task>

<task type="auto">
  <name>创建会话 API 字段对齐</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java
    skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java
  </files>
  <action>
    1. `CreateSessionRequest`:
       - 移除 `agentId: Long` → 新增 `ak: String`
       - 移除 `skillDefinitionId: Long`（前端不再传。若需保留可改为可选）
       - `imChatId: String` → `imGroupId: String`
    2. `SkillSessionController.createSession()`:
       - 校验改为 `request.getUserId() == null` → 400（移除 skillDefinitionId 校验）
       - 调用 `sessionService.createSession()` 更新参数
       - `sendInvokeToGateway` 改用 `request.getAk()`
    3. `SkillSessionService.createSession()`:
       - 参数签名更新：`(Long userId, String ak, String title, String imGroupId)`
       - Builder 同步更新
    - 数据库 schema 的 column rename 不在本 Phase 范围内，只改 Java 层
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，创建会话使用 ak + imGroupId</done>
</task>

<task type="auto">
  <name>更新所有受影响测试</name>
  <files>
    skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java
    skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
    skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
  </files>
  <action>
    1. `SkillSessionControllerTest`:
       - `createSession201()`: 改用 `request.setAk("ak-001")` 代替 `request.setAgentId(3L)`
       - 移除 `request.setSkillDefinitionId(2L)`
       - verify 改为 `sendInvokeToGateway(eq("ak-001"), ...)`
       - `closeSessionSendsGatewayInvoke()`: `session.setAk("ak-001")` 代替 `session.setAgentId(99L)`
    2. `SkillMessageControllerTest`:
       - 所有 `session.setAgentId(99L)` → `session.setAk("ak-test")`
       - verify 改为 `sendInvokeToGateway(eq("ak-test"), ...)`
    3. `GatewayRelayServiceTest`:
       - `sendInvokeUsesGatewayWs()` / `sendInvokeDropsWhenNoActiveConnection()` / `sendInvokeDoesNotFallbackToRedis()`:
         参数改为 `ak` 字符串
  </action>
  <verify>mvn test -pl skill-server -q</verify>
  <done>全部 skill-server 测试通过</done>
</task>

## Success Criteria
- [ ] sendInvokeToGateway 发送 `ak` 字段（非 agentId）
- [ ] CreateSessionRequest 使用 ak:String + imGroupId
- [ ] SkillSession 模型包含 ak:String + imGroupId
- [ ] 所有现有测试更新后通过
