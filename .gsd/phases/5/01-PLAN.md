---
phase: 5
plan: 1
wave: 1
---

# Plan 5.1: 清理全链路 envelope 残留代码

## Objective
删除协议已废弃的 envelope 相关代码——Skill Server 的 `MessageEnvelope.java` 模型类、PC Agent 的 `MessageEnvelope.ts` 类型文件和 `ProtocolAdapter.ts` (含测试)。Phase 3-4 已移除所有 envelope 使用代码，这些是孤立残留。

## Context
- [MessageEnvelope.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/model/MessageEnvelope.java) — 90 行，仅 self-reference
- [MessageEnvelope.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/types/MessageEnvelope.ts)
- [ProtocolAdapter.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/ProtocolAdapter.ts)
- [ProtocolAdapter.test.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/__tests__/ProtocolAdapter.test.ts)

## Tasks

<task type="auto">
  <name>删除 Skill Server MessageEnvelope.java</name>
  <files>skill-server/src/main/java/com/opencode/cui/skill/model/MessageEnvelope.java</files>
  <action>
    1. 确认 `MessageEnvelope` 在 Skill Server 中无其他引用（Phase 3 已移除使用代码）
    2. `git rm` 删除文件
  </action>
  <verify>grep -rn "MessageEnvelope" skill-server/src/ → 0 matches</verify>
  <done>MessageEnvelope.java 已删除且无残留引用</done>
</task>

<task type="auto">
  <name>删除 PC Agent envelope/ProtocolAdapter 文件</name>
  <files>
    src/main/pc-agent/types/MessageEnvelope.ts
    src/main/pc-agent/ProtocolAdapter.ts
    src/main/pc-agent/__tests__/ProtocolAdapter.test.ts
  </files>
  <action>
    1. 确认 `MessageEnvelope` 和 `ProtocolAdapter` 在 EventRelay.ts 中无引用（Phase 4 已移除）
    2. `git rm` 删除 3 个文件
  </action>
  <verify>grep -rn "ProtocolAdapter\|MessageEnvelope" src/main/pc-agent/ → 0 matches (除被删文件自身)</verify>
  <done>3 个 TS 文件已删除且无残留 import</done>
</task>

## Success Criteria
- [ ] `MessageEnvelope` 在全项目中 0 引用
- [ ] `ProtocolAdapter` 在全项目中 0 引用
- [ ] skill-server Maven 编译通过
