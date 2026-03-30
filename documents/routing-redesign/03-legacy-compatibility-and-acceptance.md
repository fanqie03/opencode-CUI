# Legacy Compatibility And Acceptance

本文建立在 [02-target-routing-model-v3](./02-target-routing-model-v3.md) 的目标模型之上，用来回答两个问题：

1. 在不立即升级旧版 SS 和三方 Source 的前提下，legacy 路径到底保留到什么边界。
2. Phase 1 的设计产出要覆盖哪些明确的验收与后续拆分口径。

## Legacy Compatibility Boundary

Phase 1 定义的边界是：

- 新版 v3 路径走显式绑定、共享真源和连接级 owner。
- legacy path 继续保留 owner/fallback 逻辑，以及最后一层 `controlled broadcast` 兜底。
- 但 legacy path 不承诺会话级精确回路由语义，也不承诺具备 v3 的显式路由绑定能力。

换句话说，legacy 的目标是“继续可用”，不是“与 v3 等价”。这点必须写清楚，否则后续 implementation phase 很容易被迫同时满足两套相互矛盾的语义。

对 legacy 的保留能力可以总结为：

- 允许继续依赖历史的 `route_confirm` 学习路径。
- 允许在归属不明时保留 `route_reject` 作为学习失败反馈。
- 允许在 owner 缺失或局部状态失效时启用 `controlled broadcast`。

对 legacy 的不承诺能力也要同步写清楚：

- 不承诺所有消息都能按 `toolSessionId` 精确命中原始 Source 实例。
- 不承诺在跨 GW、跨集群、状态漂移场景下仍具备 v3 等级的确定性。
- 不承诺继续沿用到最终目标架构完成后仍然是正式主路径。

## v3 vs Legacy Behavior Matrix

| 能力或行为 | v3 Path | Legacy Path |
| --- | --- | --- |
| 路由声明方式 | Source 主动发送 `route_bind` / `route_unbind` | 依赖历史学习与 owner/fallback |
| 学习确认 | 不依赖 `route_confirm` | 继续允许 `route_confirm` |
| 学习失败反馈 | 使用 `protocol_error` 明确返回协议错误 | 继续允许 `route_reject` |
| 广播策略 | 不属于正式主路径 | 仅作为 `controlled broadcast` 最后一层兜底 |
| 缺失必填字段 | 明确返回 `protocol_error` | 允许进入 legacy 宽松兼容判断 |
| 回路由语义 | 会话级显式索引 + 连接级 owner | “尽量可达”，不承诺精确回原实例 |
| 运维定位 | 可直接对照共享索引解释路径 | 需要结合 owner、fallback、历史学习一起排查 |

这张矩阵的核心不是“legacy 很差”，而是帮助团队明确：legacy 的存在是为了兼容，而不是为了继续定义正式设计。

## Failure And Recovery Scenarios

设计文档必须显式覆盖失败与恢复，因为这决定后续 implementation phase 是否会把“平时能跑”和“异常也能解释”分开考虑。

### Source reconnect

当 `Source reconnect` 发生时：

- v3 连接必须重新握手，并重新发送所需的 `route_bind`
- 失效连接对应的 `connectionId -> owningGw` 需要被删除
- 与该连接关联的 `sourceInstance -> activeConnectionSet` 需要被更新
- 若会话仍归属于该 `sourceInstance`，新连接建立后可重新接管传输层回路由

legacy 路径在 `Source reconnect` 后可以继续走历史 owner/fallback，但不保证沿用旧学习结果即可恢复所有精确回路由。

### GW restart

当 `GW restart` 发生时：

- 当前 `GW` 的本地缓存会丢失，但不应该影响 `GW 共享 Redis` 中的共享真源定义
- v3 路径依赖共享索引恢复路由，不依赖“重启前本地学到了什么”
- 如果当前 `GW` 并不是目标连接的 owner，则恢复后仍可通过内部 relay 找到真正的连接持有者

legacy 路径在 `GW restart` 后仍可能触发更多 fallback，包括 `controlled broadcast`，这是兼容成本的一部分。

### owner drift

当发生 `owner drift` 时，设计需要回答“谁来收敛漂移后的新事实”。

- v3 路径以共享索引和重新绑定为准，新的 `route_bind` 会覆盖旧归属
- 连接级 owner 漂移时，`connectionId -> owningGw` 是第一真相
- 业务归属漂移时，`toolSessionId` / `welinkSessionId` 对应的 `sourceInstance` 更新优先于历史学习结果

legacy 路径里，`owner drift` 只能通过 owner/fallback 和历史学习慢慢收敛，这也是为什么它不应再承担正式主路径职责。

## Acceptance Scenarios

Phase 1 的设计验收不能只停留在抽象原则，必须覆盖以下明确场景：

1. 新版 Source 仅连接统一 `ALB` 入口，仍然能把下行消息正确送到本地或远端 Agent。
2. Agent 回包时，当前 `GW` 能按 `toolSessionId` 或 `welinkSessionId` 找到目标 `sourceInstance`，并在必要时 relay 到真正的连接 owner。
3. `Source reconnect` 后，v3 连接会重新绑定，回路由恢复不依赖旧的被动学习状态。
4. `GW restart` 后，共享索引仍然是主真源，本地缓存重新预热不改变正式语义。
5. 发生 `owner drift` 时，共享索引和重新绑定可以收敛新真相，legacy 只保留兼容兜底。
6. 在 `2 clusters * 8 instances` 的现实拓扑下，目标方案不依赖跨集群实例级直连长连接。
7. 设计口径可以直接支撑后续以 `60 万用户 / 120 万 agent` 为目标的实现拆分和压测拆分。

验收通过的标准不是“今天已经实现”，而是“今天已经把后续实现必须满足的设计条款写清楚”。

## Out Of Scope For Phase 1

以下内容明确不属于本 phase：

- 直接修改生产路由代码并切换到 v3
- 在本 phase 内完成全部灰度和发布策略
- 在本 phase 内完成容量压测、连接 draining、监控指标和故障演练

Phase 1 只负责设计定稿，因此不应该把 implementation 工作、压测工作或 rollout 工作挤进同一批文档交付。

## Follow-on Phases

完成 Phase 1 后，后续工作应被拆成至少两个方向：

- `implementation phase`：在 `ai-gateway` 和 `skill-server` 中真正实现 `Source Protocol v3`、共享索引、连接池和 relay 逻辑
- `load test phase`：针对连接池吞吐、回路由稳定性、`2 clusters * 8 instances` 现实拓扑以及 `60 万用户 / 120 万 agent` 的容量目标做压测和验收

如果后续还有 rollout、draining、观测性等运维细节，也应该继续拆成独立 phase，而不是回填到 Phase 1。
