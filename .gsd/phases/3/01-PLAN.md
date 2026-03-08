---
phase: 3
plan: 1
wave: 1
---

# Plan 3.1: question_reply Invoke 分支

## Objective
发送消息 API 支持 `toolCallId` 可选字段。当 `toolCallId` 存在时生成 `invoke.question_reply`，否则保持 `invoke.chat`。这是 P0 级别的功能缺失修复。

## Context
- .gsd/SPEC.md
- skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
- skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java

## Tasks

<task type="auto">
  <name>SendMessageRequest 增加 toolCallId + 路由逻辑</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
  </files>
  <action>
    1. `SendMessageRequest` 增加 `private String toolCallId` 字段
    2. `sendMessage()` 方法中：
       - 若 `request.getToolCallId() != null` → action 改为 `"question_reply"`，payload 用 `buildQuestionReplyPayload()`
       - 否则保持 `"chat"` + `buildChatPayload()`
    3. 新增 `buildQuestionReplyPayload(String text, String toolCallId, String toolSessionId)` 方法：
       ```json
       {"text":"...", "toolCallId":"...", "toolSessionId":"..."}
       ```
    - 不要改动 buildChatPayload，它只用于普通 chat
  </action>
  <verify>mvn compile -pl skill-server -q</verify>
  <done>编译通过，sendMessage 支持 toolCallId 路由</done>
</task>

<task type="auto">
  <name>补充 question_reply 测试用例</name>
  <files>
    skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
  </files>
  <action>
    1. 新增测试 `sendMessageWithToolCallIdSendsQuestionReply()`：
       - 设置 `request.setToolCallId("tc-001")`
       - 验证 `gatewayRelayService.sendInvokeToGateway(...)` 第三个参数为 `"question_reply"`
    2. 更新 `sendMessage201()` 测试名为 `sendMessageChatInvoke()` 更清晰
       - 验证无 toolCallId 时 action 为 `"chat"`
  </action>
  <verify>mvn test -pl skill-server -Dtest=SkillMessageControllerTest -q</verify>
  <done>新增测试通过，question_reply 路由逻辑验证</done>
</task>

## Success Criteria
- [ ] SendMessageRequest 含 toolCallId 可选字段
- [ ] 有 toolCallId → invoke.question_reply
- [ ] 无 toolCallId → invoke.chat（原逻辑不变）
- [ ] 新增测试通过
