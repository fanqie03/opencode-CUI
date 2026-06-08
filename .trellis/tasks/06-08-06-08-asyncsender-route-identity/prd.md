# 区分 GW agent 与 GW-SS asyncSessionSender 通道身份

## Goal

澄清并加固 `ai-gateway` 内部 `AsyncSessionSender` 的使用边界，避免 GW 给本地 Agent 发送消息的 sender 与 GW 和 skill-server/source 之间的 sender 在日志、生命周期、失败清理和路由判断中混淆。

## What I already know

* 用户观察到：GW 给 agent 发送消息时也使用 `asyncSessionSender`，这会和 GW 与 SS 之间的发送链路混在一起。
* 当前分支：`codex/gw-agent-asyncsender-identity`。
* GitNexus 显示 `AsyncSessionSender` 位于 `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java`，`EventRelayService` 和 `SkillRelayService` 都 import 了它。
* `AsyncSessionSenderFactory` 是 Spring singleton，内部用 `ConcurrentHashMap<String, SenderEntry>` 管理 sender，key 当前是 `session.getId()`。
* `EventRelayService.sendToLocalAgentIfPresent(...)` / `sendToLocalAgent(...)` 通过 `getOrCreateSender(session)` 给 Agent WebSocket enqueue。
* `SkillRelayService.sendToSession(...)` / `sendProtocolError(...)` / `sendToLocalSourceConnection(...)` 通过 `getOrCreateSender(session)` 给 Source/skill-server WebSocket enqueue。
* 现有规范要求：`AsyncSessionSenderFactory` 是本地 WebSocket sender 生命周期的唯一 owner；一个 `linkId` 只能对应一个 `AsyncSessionSender`；调用方不能各自维护 sender map。
* 过去的 GW-SS 分析结论：`AsyncSessionSender.enqueue(...)` 只表示进入本地队列，不等价于远端已经收到。
* 2026-06-08 评估：`AgentWebSocketHandler` 和 `SkillWebSocketHandler` 是不同 WebSocket 端点，每次握手生成独立 `WebSocketSession`；因此同一个 `sessionId` 同时作为 Agent 和 Source sender 的正常运行时概率很低。
* 旁路发现：更可能的连接生命周期风险是 Agent reconnect / 旧连接 close 时只按 `ak` 清理，可能需要另一个任务评估是否要按 `ak + sessionId` 条件删除，避免误伤新连接。

## Assumptions (temporary)

* 这次要解决的是本地 sender ownership/observability 边界，不是新增跨服务 wire protocol 字段。
* Agent WebSocket session 和 Source/SS WebSocket session 来自不同 WebSocket 端点与不同长连接，正常情况下 `session.getId()` 不会共享或碰撞。
* 同一个 physical WebSocket session 被不同业务通道身份重复获取 sender 的概率很低，更像代码误用/端点误接入/测试 mock 误配场景；应作为防御性诊断，而不是本任务的主要根因假设。

## Requirements (evolving)

* 给 `AsyncSessionSender` / `AsyncSessionSenderFactory` 增加明确的本地 sender identity，例如 `channel=agent`、`channel=source`、`peerType=skill-server`、`peerId/ak/sourceInstanceId`、`linkId`。
* 保持 `AsyncSessionSenderFactory` 作为唯一 owner，不在 `EventRelayService` / `SkillRelayService` 各自维护 sender map。
* 日志、线程名、pending/失败清理诊断必须能直接看出 sender 属于 Agent 出口还是 Source/SS 出口。
* 对同一 `sessionId` 被不同 sender identity 复用的异常情况做轻量 mismatch 诊断，避免未来代码误用时排障困难。
* 不把本地 sender identity 误塞进 `GatewayMessage` / `RelayMessage`，除非后续确认需要跨服务协议参与路由。

## Acceptance Criteria (evolving)

* [x] Agent 本地发送日志包含 `channel=agent` 和可关联的 `ak/linkId`。
* [x] Source/SS 发送日志包含 `channel=source`、`sourceType=skill-server`、`sourceInstanceId/linkId`。
* [x] `AsyncSessionSenderFactory` 仍保证一个 physical `sessionId` 只有一个 active sender。
* [x] identity mismatch 作为防御性测试覆盖，证明同一 session 被 Agent/Source 两类路径误用时会产生明确诊断。
* [x] 现有 `SkillRelayServiceTest` / `SkillRelayServiceV2Test` sender flush 相关用例继续通过。
* [x] 若修改具体 symbol，改前已跑 GitNexus impact，改后跑 `gitnexus_detect_changes()`。

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* 本任务不先引入 Netty/WebFlux 传输替换。
* 本任务不改变 GW-SS wire message schema，除非设计确认本地 identity 不足以满足目标。
* 本任务不恢复任何 source broadcast/fan-out 兜底。
* 本任务暂不修复 Agent reconnect 旧连接关闭时的 `ak` 级清理语义；如确认存在误伤新连接风险，另开生命周期任务处理。

## Technical Approach

Recommended MVP: add a local `AsyncSenderIdentity` or `SenderChannel` model in `ai-gateway.ws`, pass it from `EventRelayService` and `SkillRelayService` into `AsyncSessionSenderFactory.getOrCreate(...)`, store it on `SenderEntry` / `AsyncSessionSender`, and include it in logs/thread names. Keep the sender map keyed by physical `sessionId` so one WebSocket session still has only one sending thread. Treat identity mismatch on the same `sessionId` as a low-probability programming/diagnostic guard, not as an expected runtime route.

## Feasible Approaches

**Approach A: Local sender identity on the shared factory (Recommended)**

* How it works: keep one factory and one sender per `sessionId`; add identity metadata for channel/peer/link; pass identity at every get-or-create call; validate mismatch.
* Pros: smallest wire-compatible change, preserves current owner pattern, improves logs and tests, catches wrong-path usage.
* Cons: caller sites must be updated consistently.

**Approach B: Split Agent and Source sender factories**

* How it works: define separate Spring beans/qualifiers for Agent and Source sender factories.
* Pros: stronger dependency-level separation.
* Cons: easier to accidentally create two senders for one physical session if a path is miswired; duplicates lifecycle/config surface.

**Approach C: Add a protocol/message field**

* How it works: add a new field to outbound messages to identify target channel.
* Pros: visible across services.
* Cons: changes wire contract for a local sender ownership issue; increases compatibility risk and does not by itself fix factory/log ownership.

## Open Questions

* Resolved: MVP is limited to observability (`channel/peer/linkId` in logs and sender metadata). Identity mismatch on an existing live sender is diagnostic only and keeps reusing the original sender.

## Validation

* `mvn -f ai-gateway/pom.xml test -Dtest=AsyncSessionSenderTest` passed: 8 tests.
* `mvn -f ai-gateway/pom.xml test "-Dtest=EventRelayServiceTest,SkillRelayServiceTest,SkillRelayServiceV2Test,AsyncSessionSenderTest"` passed: 65 tests.
* `mvn -f ai-gateway/pom.xml test` passed: 451 tests.
* `gitnexus_detect_changes(scope=all)` returned medium risk; affected processes are sender get/pending diagnostics and relay paths expected for this observability-only change.

## Technical Notes

* Branch created from clean `main`: `codex/gw-agent-asyncsender-identity`.
* Relevant files inspected:
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java`
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSenderFactory.java`
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
  * `.trellis/spec/ai-gateway/backend/conventions.md`
  * `.trellis/spec/ai-gateway/backend/logging-guidelines.md`
