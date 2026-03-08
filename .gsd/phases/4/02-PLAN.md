---
phase: 4
plan: 2
wave: 2
---

# Plan 4.2: 移除 sessionMap 依赖 + 清理 envelope 代码

## Objective
PC Agent 不再需要维护 `sessionMap` (toolSessionId → skillSessionId) 映射——Skill Server 现在通过 DB 查找 `findByToolSessionId` 完成路由（Phase 3.5 已实现）。同时清理 envelope 解析代码，因为 Gateway 已不再使用 envelope 包装。

## Context
- [EventRelay.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/EventRelay.ts)
  - L91: `sessionMap` 字段定义
  - L93-94: `lastSkillSessionId` 字段
  - L132-163: `relayUpstream()` — 使用 sessionMap 做映射
  - L194-207: `handleDownstreamMessage()` — envelope 解析
  - L115-118: `setAgentId()` — 启用 envelope
  - L84-88: `protocolAdapter` / `useEnvelope` 字段
- [ProtocolAdapter.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/src/main/pc-agent/ProtocolAdapter.ts) — envelope 包装实现

## Tasks

<task type="auto">
  <name>简化 relayUpstream — 移除 sessionMap 映射</name>
  <files>src/main/pc-agent/EventRelay.ts</files>
  <action>
    1. **简化 `relayUpstream()` 方法**：
       - 删除 sessionMap 查找 + lastSkillSessionId 回退逻辑 (L138-145)
       - 上行消息只需携带 `toolSessionId`，不再需要 `sessionId`（skillSessionId）
       - Skill Server 会通过 DB 查找 `toolSessionId → welinkSessionId` 完成路由
       - 新上行消息格式：`{ type: 'tool_event', toolSessionId, event }`

    2. **删除 sessionMap 相关字段和使用**：
       - 删除 L91: `sessionMap` 字段
       - 删除 L93-94: `lastSkillSessionId` 字段
       - 删除 `invoke.chat` 中 L232-237 的 sessionMap 映射记录
       - 删除 `invoke.create_session` 中 L277-282 的 sessionMap 映射记录

    注意：
    - `msg.sessionId` (welinkSessionId) 在 `create_session` 回传时仍需保留——它用于 `session_created` 上行消息
    - `trySendError` 仍需要 `msg.sessionId` 用于上行错误报告
    - invoke.chat 和 invoke.question_reply 的 `trySendError(msg.sessionId, err)` 可保留
  </action>
  <verify>grep -rn "sessionMap" src/main/pc-agent/EventRelay.ts → 0 匹配</verify>
  <done>sessionMap 和 lastSkillSessionId 完全移除，relayUpstream 只携带 toolSessionId</done>
</task>

<task type="auto">
  <name>清理 envelope 解析和 ProtocolAdapter 使用</name>
  <files>
    src/main/pc-agent/EventRelay.ts
    src/main/pc-agent/ProtocolAdapter.ts
  </files>
  <action>
    1. **简化 `handleDownstreamMessage()`** (L189-218):
       - 删除 envelope 解析分支 (L194-207: hasEnvelope/MessageEnvelope)
       - 直接 `msg = raw as Record<string, unknown>`

    2. **简化 `relayUpstream()` 中的消息构建**:
       - 删除 `useEnvelope && protocolAdapter` 条件分支
       - 直接发送 `{ type: 'tool_event', toolSessionId, event }`

    3. **简化 `create_session` 的上行消息**:
       - 删除 envelope 包装分支 (L292-298)
       - 直接发送 `sessionData` 对象

    4. **简化 `trySendError()`**:
       - 删除 envelope 包装分支 (L385-386)
       - 直接发送 `{ type: 'tool_error', sessionId, error }`

    5. **删除 envelope 相关字段和方法**:
       - 删除 `protocolAdapter` 字段 (L85)
       - 删除 `useEnvelope` 字段 (L88)
       - 删除 `setAgentId()` 方法 (L115-118)
       - 删除 `ProtocolAdapter` import

    6. **评估 ProtocolAdapter.ts**:
       - 如果 EventRelay 是唯一使用者，则 ProtocolAdapter.ts 文件可标记为待删除
       - 此 Plan 保留文件但移除引用，Phase 5 (协议规范化) 再决定是否删除
  </action>
  <verify>
    grep -rn "envelope\|ProtocolAdapter\|useEnvelope" src/main/pc-agent/EventRelay.ts → 0 匹配
  </verify>
  <done>EventRelay.ts 无 envelope/ProtocolAdapter 引用，消息直接发送</done>
</task>

## Success Criteria
- [ ] `sessionMap` 完全移除
- [ ] `relayUpstream` 只发送 `toolSessionId`（不发 sessionId）
- [ ] envelope 解析和包装代码完全移除
- [ ] `ProtocolAdapter` 引用从 EventRelay 中移除
- [ ] PC Agent 重启后上行消息仍正常（不依赖内存映射）
