---
phase: 3
plan: 3
wave: 1
---

# Plan 3.3: 权限回复字段改造

## Objective
权限回复从 `approved: Boolean` 改为 `response: String("once"/"always"/"reject")`，对齐目标协议。P1 级别修复。

## Context
- .gsd/SPEC.md
- skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
- skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java

## Tasks

<task type="auto">
  <name>PermissionReplyRequest + payload 改造</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
  </files>
  <action>
    1. `PermissionReplyRequest` 改为：
       - 移除 `private Boolean approved;`
       - 新增 `private String response;`（合法值 "once", "always", "reject"）
    2. `replyPermission()` 校验逻辑：
       - `request.getResponse() == null` → 400
       - 验证 response 值 ∈ {"once", "always", "reject"}，不合法 → 400
    3. `buildPermissionReplyPayload()` 改为接收 `String response`（不再是 `boolean approved`）：
       - payload 改为 `{"permissionId":"...", "response":"once", "toolSessionId":"..."}`
    4. StreamMessage 构建：
       - `.response(request.getResponse())` 代替 `request.getApproved() ? "approved" : "rejected"`
    5. 响应体：
       - 返回 `"response": request.getResponse()` 代替 `"approved": request.getApproved()`
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，权限回复使用 response:String</done>
</task>

<task type="auto">
  <name>更新权限回复测试</name>
  <files>
    skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
  </files>
  <action>
    1. 更新 `permissionReply200()` → `permissionReplyOnce200()`
       - `request.setResponse("once")` 代替 `request.setApproved(true)`
       - 验证响应体 `response` 字段
    2. 更新 `permissionReplyMissingApproved400()` → `permissionReplyMissingResponse400()`
       - response 为 null → 400
    3. 新增 `permissionReplyInvalidResponse400()`
       - `request.setResponse("invalid")` → 400
    4. 更新 `permissionReplyClosedSession409()`
       - 使用 `request.setResponse("once")`
  </action>
  <verify>mvn test -pl skill-server -Dtest=SkillMessageControllerTest -q</verify>
  <done>权限回复测试全部通过</done>
</task>

## Success Criteria
- [ ] PermissionReplyRequest 使用 response:String 字段
- [ ] 合法值仅 once/always/reject，其他值 400
- [ ] 发送到 Gateway 的 payload 含 response 字段
- [ ] 测试通过
