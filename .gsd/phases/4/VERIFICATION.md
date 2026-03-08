# Phase 4 验证报告：PC Agent 协议对齐

> 日期：2026-03-08 | 结果：**4/4 PASS** ✅

## Must-Haves

| #   | 验证项                                     | 结果  | 证据                                                                                               |
| --- | ------------------------------------------ | :---: | -------------------------------------------------------------------------------------------------- |
| 1   | question_reply → session.prompt            |   ✅   | EventRelay.ts L213: `session.prompt(promptArgs)` in `case 'question_reply'`                        |
| 2   | abort_session → session.abort              |   ✅   | EventRelay.ts L277: `session.abort({ path: { id: toolSessionId } })`                               |
| 3   | close_session → session.delete（非 abort） |   ✅   | EventRelay.ts L297: `session.delete({ path: { id: toolSessionId } })`                              |
| 4   | PC Agent 重启后上行消息正常                |   ✅   | `Select-String "sessionMap\|lastSkillSessionId"` → **0 matches**; relayUpstream 只发 toolSessionId |

## 代码证据

### MH-1: question_reply handler
```typescript
// EventRelay.ts L203-220
case 'question_reply': {
  const toolSessionId = payload.toolSessionId as string | undefined;
  const text = payload.text as string | undefined;
  const toolCallId = payload.toolCallId as string | undefined;
  // ...
  const result = await (this.client as any).session.prompt(promptArgs);
}
```

### MH-2: abort_session handler
```typescript
// EventRelay.ts L269-286
case 'abort_session': {
  const toolSessionId = payload.toolSessionId as string | undefined;
  // ...
  await (this.client as any).session.abort({
    path: { id: toolSessionId },
  });
}
```

### MH-3: close_session 修复
```diff
-await (this.client as any).session.abort({
+await (this.client as any).session.delete({
   path: { id: toolSessionId },
 });
```

### MH-4: sessionMap 完全移除
```
PS> Select-String -Path "src/main/pc-agent/EventRelay.ts" -Pattern "sessionMap|lastSkillSessionId"
→ (no output = 0 matches)
```

relayUpstream 新实现只发 `toolSessionId`，Skill Server 通过 DB 查找路由。

## Commits
- `c6eec56`: feat(phase-4): add question_reply/abort_session handlers, fix close_session (Plan 4.1)
- `f0fbb09`: refactor(phase-4): remove sessionMap + envelope code from EventRelay (Plan 4.2)
