# Phase 1: Gateway/Source 路由方案定稿 - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning

<domain>
## Phase Boundary

本 phase 只负责定稿 `Gateway/Source` 路由方案，统一解释当前真实链路，明确目标模型、协议边界、兼容策略、验收口径与后续实现拆分。

本 phase 不直接修改生产路由实现，不把 MQ-only、纯 HTTP 回调或一次性强制升级所有 legacy 服务纳入范围。

</domain>

<decisions>
## Implementation Decisions

### GW 内部路由真源
- **D-01:** `GW` 内部路由真源统一放在 `GW` 集群共享 Redis；`GW` 本地内存和 Caffeine 只做缓存与加速，不再承担真源语义。
- **D-02:** `SS` Redis、数据库都不进入 `GW -> Source` 回路由热路径；它们可以保留各自业务职责，但不再作为 `GW` 侧回路由真源。
- **D-03:** 目标模型中 Redis 需要显式维护两类业务路由索引：`toolSessionId -> sourceInstance` 与 `welinkSessionId -> sourceInstance`；两者都属于正式索引，不再只靠本地学习。

### 连接级路由抽象
- **D-04:** 因为单条 WebSocket 在高并发回复场景下会出现队头阻塞，目标模型不采用“单连接承载全部会话”的思路，而采用 `Source` 连接池。
- **D-05:** 原先讨论中的 `sourceInstance -> owningGw` 被修正为连接级抽象：`sourceInstance -> activeConnectionSet`，`connectionId -> owningGw`。
- **D-06:** 业务路由与传输路由解耦：业务层仍按 `toolSessionId / welinkSessionId -> sourceInstance` 决定目标实例；传输层再把该实例下的会话一致性哈希到某一条活跃连接。
- **D-07:** 同一会话必须稳定落到同一条连接以保证顺序性，不同会话允许分散到不同连接以提升吞吐。

### Source Protocol v3
- **D-08:** 新版协议继续使用 `Sec-WebSocket-Protocol: auth.{Base64(JSON)}` 握手。
- **D-09:** v3 握手字段固定为 `source`、`instanceId`、`protocolVersion`；其中 `instanceId` 在 v3 语义上明确等价于 `sourceInstanceId`。
- **D-10:** v3 Source 必须显式发送 `route_bind` 与 `route_unbind`；`GW` 不再把“从业务消息里被动学习完整路由”作为 v3 主路径。
- **D-11:** `route_bind` 至少携带 `welinkSessionId`、`sourceInstanceId`，并允许附带 `toolSessionId`；`route_unbind` 负责显式撤销绑定。
- **D-12:** `GW -> Source` 查路由优先级固定为 `toolSessionId > welinkSessionId > legacy source fallback`。
- **D-13:** `route_confirm` / `route_reject` 不再属于 v3 主路径，只保留给 legacy 兼容；v3 报文缺少必要路由键时，`GW` 返回 `protocol_error`，不再靠广播试探学习。

### 兼容与故障降级
- **D-14:** v3 主路径只允许“定向 relay + 明确失败”，不再允许通过广播试探去学习 v3 路由。
- **D-15:** legacy SS 与三方 Source 第一阶段继续保留 `source` 维度的 owner / fallback 语义；只有在 owner 缺失、owner 失效等兼容场景下，才允许受控广播做最后兜底。
- **D-16:** 新版 Source 路径与 legacy 路径必须在文档中明确区分，避免 mixed mode 下把 legacy 行为误当成 v3 正式语义。
- **D-17:** Source 或 GW 断连时，必须立即清理该连接及其反向索引；后续重连后由 Source 重新 `route_bind`，不依赖旧状态自动恢复。

### 新版 Source 连线模型
- **D-18:** 新版 Source 只连接统一 `ALB` 暴露的 WebSocket 域名，不再把 `GatewayDiscoveryService`、`/internal/instances`、`POD_IP:port` 这类实例发现作为主路径。
- **D-19:** 每个新版 Source 实例维持一个小型连接池，默认值为 `4` 条长连接，可配置提升到 `8`；连接池同时承担冗余与吞吐扩展，不只是可用性冗余。
- **D-20:** 若同一实例的多条连接落到不同 GW，这属于正常状态；回路由由 `connectionId -> owningGw` 与 GW 内部 relay 解决。
- **D-21:** 第一版方案不要求连接数自动伸缩；是否按流量动态调池大小留给后续实现和压测 phase 决定。

### the agent's Discretion
- Redis 具体 key 命名、TTL、反向索引结构、缓存失效细节由研究与规划阶段决定，但必须服从上述真源边界和连接级抽象。
- 会话一致性哈希的具体 key 选择优先以 `toolSessionId` 为主；`toolSessionId` 缺失时如何平滑回退到 `welinkSessionId` 由后续方案细化。

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 项目与 phase 基线
- `.planning/PROJECT.md` - 项目级约束、核心价值、当前里程碑边界。
- `.planning/REQUIREMENTS.md` - Phase 1 对应的需求编号、兼容要求、验收与 out-of-scope。
- `.planning/ROADMAP.md` - Phase 1 目标、成功标准和三个计划分解。
- `.planning/STATE.md` - 当前项目状态与本轮上下文的承接点。

### 既有路由设计与外部调研
- `documents/routing-redesign/routing-redesign-proposal.md` - 既有路由重构提案，包含 mesh / 被动学习 / 广播降级等旧思路，规划时需要明确哪些内容沿用、哪些内容被 Phase 1 决策替换。
- `documents/routing-redesign/industry-routing-research.md` - 外部系统路由模式调研，为“定向查表 vs 广播兜底”“连接层 vs 消息层”提供对照。

### GW 当前实现热点
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java` - 当前 GW 侧 Source 路由主入口，本地连接池、哈希环、旧 route cache 都在这里。
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/UpstreamRoutingTable.java` - 当前 `toolSessionId/welinkSessionId -> sourceType` 的本地 Caffeine 路由表。
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java` - 当前 GW 内部 Redis key、relay channel、agent 注册信息的实现位置。
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java` - 当前 Source 握手字段、消息入口、legacy/v3 兼容边界入口。
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java` - 当前 `conn:ak` / `gw:internal:agent` 注册与刷新逻辑。
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java` - 当前 legacy owner / fallback 路径实现。

### Skill Server 当前实现热点
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java` - 当前 `welinkSessionId` owner 路由与接管逻辑。
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` - 当前 `Agent -> GW -> Source` 回流路由、`route_confirm/route_reject` 触发点。
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java` - 当前 `Source -> GW -> Agent` 投递路径，包含 hash、精确投递和广播兜底。
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java` - 当前 Source 侧“发现所有 GW 并全连”的连接模型实现，后续规划必须明确如何替换。

### 代码库映射辅助文档
- `.planning/codebase/ARCHITECTURE.md` - 当前系统分层与模块关系概览。
- `.planning/codebase/CONCERNS.md` - 现有实现里关于路由、状态边界和可维护性的关注点。

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `RedisMessageBroker`：已经具备 GW 实例注册、agent 注册和 GW 间 relay 的基础能力，可作为 v3 控制面的承载点继续演进。
- `SkillRelayService` / `LegacySkillRelayStrategy`：当前代码已经把“新旧路径分流”做成显式入口，适合作为 mixed mode 演进切面。
- `SessionRouteService`：已经承担 `welinkSessionId` owner 语义，可用于梳理当前链路与 legacy 行为，但不应继续扩张成 GW 侧真源。
- `GatewayWSClient`：当前已具备连接池、哈希和广播基础设施，但其“发现全部 GW 实例并全连”的策略需要收敛到 ALB 入口模式。

### Established Patterns
- GW 侧已经存在 Redis 驱动的 `conn:ak`、`gw:internal:agent:{ak}`、`gw:relay:{instanceId}` 模式，说明“共享 Redis + GW 内部 relay”是已有基础设施，而不是全新引入。
- 当前 Source 路由状态被分散在本地内存、Caffeine、GW Redis、SS Redis 和部分 repository 查询中，边界不清正是本 phase 要收口的问题。
- 现有实现里 legacy 与 mesh 思路并存，且 `route_confirm/route_reject`、broadcast miss、owner fallback 混在一起，后续规划必须先拆清正式路径与兼容路径。

### Integration Points
- v3 握手与控制消息的入口在 `SkillWebSocketHandler`。
- `route_bind / route_unbind` 写路由真源以及清理反向索引的责任应落在 GW 侧统一路由组件，后续可由 `SkillRelayService` 演进承接。
- `Agent -> GW -> Source` 回路由的查表、relay 和定向下发路径，需要重新切分 `GatewayMessageRouter`、GW 路由表和 relay 逻辑。
- `Source -> GW -> Agent` 的连接模型切换会直接影响 `GatewayWSClient`，规划阶段需要把“统一 ALB 入口 + 小连接池 + 会话哈希”写成明确迁移方向。

</code_context>

<specifics>
## Specific Ideas

- 单条 WebSocket 存在高并发回复下的阻塞风险，因此本次定稿明确把“连接池既承担冗余，也承担吞吐”写入目标模型。
- `sourceInstance -> owningGw` 的实例级单 owner 说法已经被否决；后续所有实现与文档都应改用连接级 owner 语义。
- 新版 v3 的核心是“业务路由显式绑定、传输路由连接级分摊、GW 内部 Redis 真源、legacy 仅作兼容”。
- Phase 1 的目标不是给出最终代码结构，而是先把 downstream agent 不该再反复追问的关键语义全部锁住。

</specifics>

<deferred>
## Deferred Ideas

None - 讨论聚焦在 Phase 1 既定范围内。

</deferred>

---

*Phase: 01-gateway-source*
*Context gathered: 2026-03-29*
