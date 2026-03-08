---
phase: 3
plan: 5
wave: 3
---

# Plan 3.5: toolSessionId→welinkSessionId 查找 + Envelope 清理

## Objective
上行消息（tool_event / tool_done / tool_error / permission_request）从 Gateway 收到时携带 `toolSessionId`，Skill Server 需要查 DB 将其转换为 `welinkSessionId`（即 SkillSession 主键），然后路由到正确的 session channel。同时清理 `handleGatewayMessage` 中残留的 envelope 解析代码。

## Context
- .gsd/SPEC.md
- skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
- skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java
- skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java

## Tasks

<task type="auto">
  <name>新增 toolSessionId 查找 + 改造上行消息路由</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java
    skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
    skill-server/src/main/java/com/opencode/cui/skill/repository/SkillSessionRepository.java
  </files>
  <action>
    1. `SkillSessionRepository` 新增：
       `Optional<SkillSession> findByToolSessionId(String toolSessionId)`
    2. `SkillSessionService` 新增：
       `SkillSession findByToolSessionId(String toolSessionId)` — 带简单缓存（ConcurrentHashMap）
    3. `GatewayRelayService.handleGatewayMessage()`：
       - `tool_event` / `tool_done` / `tool_error` / `permission_request`：
         从 `node.path("sessionId")` 改为读取 `node.path("toolSessionId")`
         或保留 sessionId 读取但做 DB 查找确认
       - 用 `sessionService.findByToolSessionId(toolSessionId)` 获取 welinkSessionId
       - 将解析出的 welinkSessionId 传给各 handler
    4. 保持 `session_created` 不变（它本身就含 welinkSessionId 上下文）
    - WHY: Gateway 上行消息不再携带 welinkSessionId，仅携带 toolSessionId
    - 注意 `agent_online` / `agent_offline` 不涉及 session，无需修改
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，上行消息通过 toolSessionId 路由</done>
</task>

<task type="auto">
  <name>清理 envelope 残留 + 更新测试</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
    skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
  </files>
  <action>
    1. `handleGatewayMessage()` 中删除 envelope 解析代码块（约 20 行）：
       - `JsonNode envelopeNode = node.path("envelope");` 及其整个 if 块
       - 保留 SequenceTracker 调用（如果 sequenceNumber 在主消息中可用就保留，否则也删除）
    2. 更新测试：
       - `toolEventPersistsAndBroadcasts()` 等测试需要 mock `findByToolSessionId` 返回 session
       - 新增测试 `toolEventLooksUpWelinkSessionId()` 验证 toolSessionId → welinkSessionId 路由
    - 不要删除 sequenceTracker 本身（它可能在未来用别的方式获取序列号）
  </action>
  <verify>mvn test -pl skill-server -q</verify>
  <done>全部测试通过，envelope 代码已清理</done>
</task>

## Success Criteria
- [ ] 上行 tool_event/tool_done/tool_error 通过 toolSessionId 查 DB 获取 welinkSessionId
- [ ] 路由到正确的 session:{welinkSessionId} channel
- [ ] envelope 解析代码已删除
- [ ] 新增 + 更新测试通过
