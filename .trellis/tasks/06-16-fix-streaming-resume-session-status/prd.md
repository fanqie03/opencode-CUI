# 问题一：Resume 缺少 streaming 事件，客户端无历史流式消息渲染

## 症状

用户在两个正在执行任务的会话之间来回切换时，resume 请求返回的 streaming 响应中有 `sessionStatus: "busy"` 但 `parts` 为空，导致前端无法恢复流式消息渲染，用户看不到任何历史对话内容。

## 根因分析

**时序窗口竞争**：`activateIdleSession`（GatewayMessageRouter.java:705-717）先将 `sessionStatus("busy")` 写入 Redis buffer，此时 `stream:{sessionId}:status` key 已存在。但在 `tool_event`（实际的 text.delta / thinking.delta）到达并调用 `accumulateDelta` 写入 part 之前，存在一个短暂的时间窗口。

如果 resume 恰好在这个窗口内到达：
1. `SnapshotService.buildStreamingState()` 检查 `bufferService.isSessionStreaming(sessionId)` → `true`（status key 存在）
2. `bufferService.getStreamingParts(sessionId)` → 空列表（尚无 delta 到达）
3. 返回 `{ sessionStatus: "busy", parts: [] }`

前端 `restoreStreamingMessage`（useSkillStream.ts:678-682）处理逻辑：
- `partMessages.length === 0` 且 `!isIdle`（因为 status 是 busy）→ 直接 return
- **不设置 `isStreaming = true`**，不重建 assembler
- 用户看到空白会话

**同样发生在**：GatewayMessageRouter.retryPendingMessages（line 1242）重放 pending 消息后 emit busy，但 delta 尚未入 buffer。

## 修复方案

**后端**：`SnapshotService.buildStreamingState` 中，当 `isStreaming=true` 但 `parts` 为空时，返回 `sessionStatus: "active"`（新状态，区别于 busy）而非 `"busy"`。同时检查 DB 中 session 的实际状态作为兜底。

**前端**：`restoreStreamingMessage` 中，当 `sessionStatus === "active"` 且 parts 为空时，将 `isStreaming` 设为 `true`（表示会话确实在执行中），但不渲染消息（因为尚无内容）。当后续 live streaming event 到达时，前端从 live 路径正常追加渲染。

---

# 问题二：任务执行完但 sessionStatus 一直 busy

## 症状

Agent 任务已执行完毕（tool_done 已到达并被处理），但客户端 streaming 连接显示的 `sessionStatus` 仍为 `"busy"`，导致客户端一直显示"生成中"，输入框禁用且无恢复手段（当前无独立停止按钮）。

## 根因分析

**根因 A — Abort 路径未向客户端 emit idle 事件**（SkillSessionFlowService.java:161-173）

`finalizeAbortedSession()` 执行流程：
1. `persistBufferedAbortParts` — 持久化缓冲
2. `persistIfFinal(sessionId, idle)` — 写 DB
3. `sessionService.markSessionIdle(sessionId)` — DB status → IDLE
4. `bufferService.clearSession(sessionId)` — 清 Redis buffer

**缺失**：没有调用 `emitter.emitToSession()` 向客户端推送 `sessionStatus("idle")`。客户端此前已收到 live `session.status: busy` 并将 `isStreaming` 置为 true，之后再无任何事件将其恢复为 false。导致输入框永久禁用。

**根因 B — idle 事件 emit 失败导致 buffer 未清理**

GatewayMessageRouter.handleToolDone（line 965-970）：
```java
emitter.emitToSession(session, sessionId, userId, msg);  // 如果抛异常
bufferService.accumulate(sessionId, msg);                // 永远不到达
```

`emitToSession` → `OutboundDeliveryDispatcher.deliver` → `StreamMessageEmitter.emitToSession`（line 64-83）在 enrichment 阶段可能因 Redis 连接失败抛异常。异常被外层 try-catch 吞掉后，`bufferService.accumulate(sessionId, idleMsg)` 不会执行。Redis buffer 中的 `stream:{sessionId}:status` 保持 `"busy"`，TTL 1 小时。

结果：后续 resume 时 `buildStreamingState` 返回 `sessionStatus: "busy"` + 旧 parts，前端重新进入 streaming 状态，无法恢复。

**根因 C — 非 miniapp 域 idle 不入 buffer**

GatewayMessageRouter.handleToolDone（line 970-972）：
```java
if (session == null || session.isMiniappDomain()) {
    bufferService.accumulate(sessionId, msg);
}
```

对于 IM 等非 miniapp 域的会话，`tool_done` 产生的 idle 事件仅 emit 到前端，不写入 Redis buffer。一旦 emit 丢包（网络问题、客户端断连），buffer 中永远没有 idle 记录。后续 resume 永远返回 busy + 旧 parts。

## 修复方案

**修复 A**：`finalizeAbortedSession` 在 `clearSession` 之前，调用 `emitter.emitToSession()` 推送 `sessionStatus("idle")` 到客户端，确保 abort 后客户端解除 streaming 状态。

**修复 B**：`handleToolDone` 中将 `bufferService.accumulate` 提前到 `emitter.emitToSession` 之前执行，确保 idle 事件**先入 buffer**，再推送到客户端。即使推送失败，buffer 状态已正确。

**修复 C**：移除 `handleToolDone` 中 `session.isMiniappDomain()` 的守卫条件，所有域的 idle 事件均入 buffer。确保无论客户端类型，buffer 状态始终与实际一致。

---

# 需求汇总

| # | 需求 | 层 | 优先级 |
|---|------|----|--------|
| R1 | `buildStreamingState` 新增 `isStreaming=true && parts为空` 的处理，返回 `sessionStatus: "active"` | 后端 | P0 |
| R2 | 前端处理 `sessionStatus: "active"`（parts 为空），设置 `isStreaming = true` 等待 live 事件 | 前端 | P0 |
| R3 | `finalizeAbortedSession` 补充 emit `sessionStatus("idle")` 到客户端 | 后端 | P0 |
| R4 | `handleToolDone` 中 `bufferService.accumulate` 移到 `emitter.emitToSession` 之前 | 后端 | P0 |
| R5 | `handleToolDone` 移除 `isMiniappDomain` 守卫，所有域 idle 均入 buffer | 后端 | P1 |

# 验收标准

- [ ] 两个 ACTIVE 会话切换时，resume 返回 `sessionStatus: "active"`，前端显示流式等待状态
- [ ] Abort 会话后，客户端收到 `session.status: idle` 事件，输入框恢复可用
- [ ] tool_done 后即使 emit 失败，buffer 已正确记录 idle，resume 返回 idle
- [ ] IM 等非 miniapp 域会话完成后，resume 返回 idle 而非 busy
- [ ] 现有正常 streaming 流程（live delta → done → idle）不受影响
