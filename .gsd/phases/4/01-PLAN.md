---
phase: 4
plan: 1
wave: 1
---

# Plan 4.1: invoke handler 补齐与修复

## Objective
补齐 `question_reply` 和 `abort_session` 两个缺失的 invoke handler，修复 `close_session` 的错误 SDK 调用。这 3 个都是 P0 级别——直接影响 AI 提问回答、中止操作、关闭会话的基本 E2E 流程。

## Context
- .gsd/SPEC.md
- [EventRelay.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/EventRelay.ts) — `handleInvoke` 方法 (L223-L354)
- [Gap Analysis 层④](file:///C:/Users/15721/.gemini/antigravity/brain/b295cd8f-2d9a-422d-8d16-98a7b01a5e9b/gap_analysis.md#L98-L121)

## Tasks

<task type="auto">
  <name>新增 invoke.question_reply handler</name>
  <files>src/main/pc-agent/EventRelay.ts</files>
  <action>
    在 `handleInvoke` 方法的 switch 中，`chat` case 之后新增 `question_reply` case：
    - 从 payload 提取 `toolSessionId`, `text`, `toolCallId` 三个字段
    - 校验必填：toolSessionId, text（toolCallId 可选但建议有）
    - 调用 `this.client.session.prompt()` — 与 chat 完全相同的 SDK 调用
    - prompt 的 body.parts 使用 `[{ type: 'text', text }]`
    - 记录 sessionMap 映射（保持与 chat 一致的行为）
    - debugLog 使用 `'invoke.question_reply'` 前缀
    - 注意：question_reply 与 chat 的唯一区别在于语义——前者是回答 AI 的提问，后者是用户主动发起
  </action>
  <verify>npx tsc --noEmit --project src/main/pc-agent/tsconfig.json 或在编辑器确认无 TS 类型错误</verify>
  <done>question_reply case 在 switch 中存在，提取 toolSessionId/text 并调用 session.prompt()</done>
</task>

<task type="auto">
  <name>新增 invoke.abort_session handler + 修复 close_session</name>
  <files>src/main/pc-agent/EventRelay.ts</files>
  <action>
    1. **新增 `abort_session` case**（在 close_session 之前）：
       - 从 payload 提取 `toolSessionId`
       - 校验必填：toolSessionId
       - 调用 `this.client.session.abort({ path: { id: toolSessionId } })`
       - 这才是"中止正在进行的 AI 操作"的正确调用

    2. **修复 `close_session` case**（L308-L323）：
       - 当前错误地调用 `session.abort()` — 语义错误
       - 改为调用 `session.delete({ path: { id: toolSessionId } })`
       - `delete` 是"永久删除会话"，`abort` 是"中止当前操作"

    注意事项：
    - 不要修改 `abort` → `delete` 时的参数结构，path 不变
    - OpenCode SDK 方法名: `session.abort()` / `session.delete()`
  </action>
  <verify>grep "session.delete" src/main/pc-agent/EventRelay.ts → 匹配 close_session case</verify>
  <done>abort_session 调用 session.abort()，close_session 调用 session.delete()</done>
</task>

## Success Criteria
- [ ] `question_reply` invoke → 调用 session.prompt（与 chat 相同 SDK 调用）
- [ ] `abort_session` invoke → 调用 session.abort
- [ ] `close_session` invoke → 调用 session.delete（非 abort）
- [ ] TypeScript 编译无错误
