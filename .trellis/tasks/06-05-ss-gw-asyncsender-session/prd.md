# 修复 SS+GW asyncSender session 抢占丢消息

## Goal

定位并修复 SS+GW 通道中疑似两套 `asyncSender` 竞争同一个 session 导致其中一路消息丢失的问题，保证同一 session 的发送器注册、替换、关闭与投递语义一致可观测。

## What I already know

* 用户提供了 SS+GW 全量日志，指出当前存在“两套 asyncSender 抢占同一个 session”的现象。
* 日志中出现同一 `toolSessionId=ses_d89c3e47-d82e-4020-b55a-45621da966d1`、同一 `sourceInstanceId=deployment-skill-api-d6895b978-sm4rz` 在多个 gateway/redis listener 线程间转发。
* 旧诊断结论提醒：`AsyncSessionSender.enqueue(...)` 和 `Delivered to-source relay` 只能证明本地入队，不能证明远端 WebSocket frame 已发送或被 SS 接收。

## Assumptions (temporary)

* 根因可能位于 gateway 侧 source session / sender registry 的并发注册或覆盖逻辑。
* 如果同一个 logical session 被多个 `AsyncSessionSender` 包装，旧 sender 的队列中消息可能在 registry 指针切换后失去后续投递机会。
* 修复应优先保证同一物理 WebSocket session 只对应一个可用 sender，或在替换 sender 时有明确 drain/close/failure 语义。

## Open Questions

* 暂无阻塞问题；先从日志和代码路径自行验证根因。

## Requirements (evolving)

* 找到同一 session 被多个 `asyncSender` 注册、替换或关闭的具体代码路径。
* 明确重复 sender 出现时的期望行为：复用、幂等注册、或安全替换。
* 修复丢消息风险，并保留足够日志用于区分本地 enqueue、实际发送失败、以及 session 被替换。
* 不依赖单实例进程内状态来表达跨 GW/SS 的 correctness-critical 投递结论。

## Acceptance Criteria (evolving)

* [x] 日志证据能解释两套 sender 竞争同一 session 的触发顺序。
* [x] 修复后同一 session 的 sender 生命周期不会出现静默覆盖导致队列丢失。
* [x] 针对重复注册/并发替换场景增加或更新测试。
* [x] 运行相关模块的 targeted tests。
* [x] `gitnexus_detect_changes()` 确认影响范围符合预期。

## Implementation Result

* GW 回源路由改为 `messageId` 优先，并把同一 `messageId` 绑定到同一条本机 SS link；绑定 link 失效时不静默重选，避免同一消息跨 link 乱序或重复。
* L2 从共享 Stream 改成 target-GW mailbox Stream：只选择一个拥有 SS 本机连接的 GW，且只有该 GW 消费自己的 mailbox。
* `AsyncSessionSender` 失败会回调清理本地 sender/link/Redis source-conn 状态；注册 fallback `instanceId` 会写回 session 属性，保证反注册有同一身份。
* `tool_done` / `tool_error` 的 `messageId` 通过云端事件补齐或 trace 绑定恢复，不凭空生成业务 messageId。
* Agent close/offline 只在确认当前 GW 仍是 owner 或无新 owner 时广播，避免重连/升级时 `agent_offline` 风暴。
* Verified with targeted ai-gateway tests, full `mvn test`, `git diff --check`, and GitNexus detect_changes low-risk result.

## Definition of Done (team quality bar)

* Tests added/updated where appropriate.
* Lint / typecheck / targeted tests green.
* Docs/notes updated if behavior or operational diagnosis wording changes.
* Rollout/rollback considered if risky.

## Out of Scope (explicit)

* 本任务不重写完整 GW-SS Netty/WebSocket 传输架构。
* 本任务不把 enqueue 日志升级为端到端业务 ACK，除非代码已有现成 ACK 机制可安全接入。

## Technical Notes

* Log attachment: `C:\Users\15721\.codex\attachments\dde4cfa8-e3aa-4acb-88bc-a58b84611fe3\pasted-text.txt`
* Initial suspected components: `AsyncSessionSender`, source session registry, `EventRelayService`, `SkillRelayService`, WebSocket handler/session lifecycle hooks.
