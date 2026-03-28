# Phase 1: Gateway/Source 路由方案定稿 - Research

**Date:** 2026-03-29
**Status:** Complete
**Goal:** 回答“为了把 Phase 1 规划好，我需要提前知道什么？”

---

## Research Question

在不改生产代码的前提下，如何把当前 `Gateway/Source` 路由现状、目标模型、协议边界、legacy 兼容和验收口径拆成可执行的文档型计划，并确保这些计划后续能稳定指导实现 phase？

## Current System Reality

### 现有真实链路

#### Source -> GW -> Agent
- `skill-server` 当前通过 [GatewayWSClient.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java) 维护到 GW 的连接池，设计上仍带有“发现多个 GW 实例并全连/哈希发送/广播兜底”的 mesh 痕迹。
- `skill-server` 下发 `invoke` 时，主路径在 [GatewayRelayService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java)，优先 `sendViaHash`，再尝试按 `conn:ak` 精确发送，最后广播。
- `ai-gateway` 接到 Source 消息后，在 [SkillWebSocketHandler.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java) 和 [SkillRelayService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java) 中完成路由学习、转发和 legacy/mesh 分流。
- Agent 接入与 `conn:ak` 注册在 [AgentWebSocketHandler.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java)。

#### Agent -> GW -> Source
- GW 侧返回流量目前主要依赖 [SkillRelayService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java) 的本地 `routeCache/sourceTypeSessions/hashRings` 和 [UpstreamRoutingTable.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/UpstreamRoutingTable.java) 的本地 Caffeine 路由表。
- `skill-server` 收到 GW 回流后，由 [GatewayMessageRouter.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java) 做 session 归属判断、relay 或 fallback，并在部分路径里仍会触发 `route_confirm/route_reject`。
- `welinkSessionId` 的 owner/接管逻辑主要在 [SessionRouteService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java)；`toolSessionId` 反查仍带有 repository/DB 的历史依赖。

### 现状的关键特征
- 当前实现同时混有 mesh、legacy owner、广播兜底、被动学习四套语义。
- GW 侧路由真源并不唯一，状态分散在本地内存、Caffeine、GW Redis、SS Redis 和 repository 查询中。
- 现有代码默认 Source 侧能发现 GW 实例并建立实例级连接，这与生产“统一经 Ingress/ALB 暴露，跨集群无法实例级直连”的现实拓扑不一致。

## Existing Assets The Planner Can Reuse

### 代码层复用点
- [RedisMessageBroker.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java)：已经具备 `conn:ak`、`gw:internal:agent:{ak}`、`gw:relay:{instanceId}` 等机制，适合在设计文档里作为“现有控制面能力”引用。
- [SkillRelayService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java) 与 [LegacySkillRelayStrategy.java](d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java)：适合作为“现状为什么混乱”的证据，也能支撑 mixed mode 迁移边界描述。
- [GatewayWSClient.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java)：适合作为“当前 Source 连接模型”样本，同时也是后续实现 phase 的主要改造入口之一。
- [SessionRouteService.java](d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java)：适合说明当前 `welinkSessionId` owner 语义与 `toolSessionId` 的不足。

### 文档层复用点
- [routing-redesign-proposal.md](d:/02_Lab/Projects/sandbox/opencode-CUI/documents/routing-redesign/routing-redesign-proposal.md) 不是最终答案，但已经沉淀了现状观察、旧方案和术语。
- [industry-routing-research.md](d:/02_Lab/Projects/sandbox/opencode-CUI/documents/routing-redesign/industry-routing-research.md) 可直接支撑“为什么连接层和消息层要解耦”“为什么 v3 不应继续依赖广播学习”的论证。
- [01-CONTEXT.md](d:/02_Lab/Projects/sandbox/opencode-CUI/.planning/phases/01-gateway-source/01-CONTEXT.md) 已经锁住本 phase 的全部关键语义，planner 不应重新发明。

## Gaps Between Current Code And Locked Decisions

### 真源边界冲突
- 已锁定决策要求 `GW` 共享 Redis 成为唯一 GW 内部路由真源，但当前 GW 实现仍有本地 `routeCache`、`UpstreamRoutingTable` 和 legacy owner key 并存。
- 已锁定决策要求 `toolSessionId` 与 `welinkSessionId` 都成为正式索引，但当前代码里 `toolSessionId` 仍不稳定，且部分路径靠被动学习或 DB 反查。

### 协议语义冲突
- 已锁定决策要求 v3 显式 `route_bind / route_unbind`，但当前实现仍偏向从 `invoke` / 回流消息中被动学习路由。
- 已锁定决策要求 v3 缺必要字段时返回 `protocol_error`，而当前代码仍保留 `route_confirm / route_reject` 作为主路径一部分。

### 连接模型冲突
- 已锁定决策要求新版 Source 只连统一 ALB 入口，并以连接池承担冗余和吞吐；当前 `GatewayWSClient` 仍基于 GW 实例发现和 mesh/full-connection 逻辑。
- 已锁定决策要求从“实例级 owner”切换到“连接级 owner”，但当前很多设计描述仍以 `sourceInstance -> owningGw` 单 owner 思维展开。

### 降级策略冲突
- 已锁定决策要求 v3 只走定向 relay + 明确失败，legacy 才允许受控广播；当前实现里广播 miss 与主路径边界不清。

## Planning Implications

### 这不是代码改造 phase，而是设计基线 phase
Phase 1 的计划必须以“文档产出”作为核心交付，而不是悄悄夹带代码修改。计划里的 acceptance criteria 应该验证：
- 目标文档是否完整覆盖需求 ID。
- 现状链路、目标模型、协议语义、兼容边界、验收场景是否被写清楚。
- 旧设计提案中已过时的 mesh/被动学习/单 owner 叙事是否被明确替换或标注为历史背景。

### 最自然的 3 个计划切片
基于 roadmap，这个 phase 最适合拆成 3 个 plan：

1. **当前链路梳理**
   目标是把 `Source -> GW -> Agent` 与 `Agent -> GW -> Source` 的真实现状画清楚，并明确哪些状态在哪一层。

2. **目标模型与协议定稿**
   目标是把 v3 协议、连接级抽象、Redis 真源、路由键优先级、连接池与会话哈希写成可执行的正式设计。

3. **legacy / 验收 / 后续边界**
   目标是写清 mixed mode、legacy 的保留能力与不承诺能力、验收场景、实现 phase 与压测 phase 的边界。

### 输出位置建议
- `.planning/phases/01-gateway-source/` 继续承接上下文、研究、计划、验证这些 GSD 产物。
- `documents/routing-redesign/` 应承接最终设计文档或对现有提案的正式重写，这样实现 phase 读取入口清晰。
- 如果 planner 选择继续复用 `routing-redesign-proposal.md`，必须在计划里写清“重写/替换哪些旧段落”，避免新旧叙事叠加。

## Risks The Planner Must Carry Forward

### 风险 1：文档只描述目标，不解释现状
如果计划只写目标模型而不把当前真实链路拆清，后续实现 phase 会继续围绕错误心智模型讨论。

### 风险 2：把 v3 和 legacy 写成同一套路径
如果计划没有显式区分“正式路径”和“兼容路径”，执行阶段很容易再次把广播兜底、被动学习混回主路径。

### 风险 3：忽略单连接吞吐瓶颈
用户已经明确指出单条 WebSocket 会成为并发瓶颈，所以 planner 必须把“连接池承担吞吐”写进任务目标与 acceptance criteria，不能只保留双连接冗余表述。

### 风险 4：计划没有 requirement traceability
本 phase 有 11 个 requirement ID；planner 必须在 plan frontmatter 里显式覆盖全部 ID，否则 checker 之后还会打回。

## Recommended Acceptance Shape For Plans

每个 plan 都应该至少有以下类型的验收标准：

- **文档存在性**：目标文件存在，且包含指定章节标题。
- **语义覆盖**：文档中明确出现锁定术语，例如 `route_bind`、`protocol_error`、`toolSessionId > welinkSessionId > legacy source fallback`、`connectionId -> owningGw`。
- **边界清晰性**：文档中分别出现“新版 Source 必选能力”“legacy 兼容路径”“Out of Scope / 后续 phase 边界”。
- **需求映射**：每个 plan 的 frontmatter 都显式声明覆盖的 requirement IDs。

## Validation Architecture

### 适用结论
Phase 1 是设计文档 phase，不需要运行应用级自动化测试；验证重点应是“文档完整性、需求覆盖和关键术语可 grep”。

### 建议验证方式
- 快速验证：对单个输出文件做章节和关键术语 `rg` 检查。
- 波次验证：对 phase 目录下全部文档做 requirement ID、章节、关键术语和边界声明检查。
- 人工验证：通读最终设计文档，确认新旧叙事没有冲突，且实现/压测边界明确。

### 建议命令
- Quick:
  - `rg -n "## Implementation Decisions|## Canonical References" .planning/phases/01-gateway-source/01-CONTEXT.md`
  - `rg -n "route_bind|protocol_error|connectionId -> owningGw" documents/routing-redesign .planning/phases/01-gateway-source`
- Full:
  - `rg -n "CURR-01|CURR-02|ROUT-01|ROUT-02|ROUT-03|PROT-01|PROT-02|COMP-01|COMP-02|ACPT-01|ACPT-02" .planning/phases/01-gateway-source/*-PLAN.md`
  - `rg -n "新版 Source|legacy|验收场景|后续实现 phase|压测 phase" documents/routing-redesign .planning/phases/01-gateway-source`

## Research Summary

- 当前代码能支撑 planning 的不是某个“正确实现”，而是一组可复用的证据点与控制面基础设施。
- 规划阶段最重要的不是再发明技术方案，而是把已锁定语义稳定映射到 3 个文档型计划，并给每个计划配上可 grep 的验收标准。
- planner 必须把“连接级 owner”“显式 route_bind / route_unbind”“GW Redis 真源”“legacy 仅兼容兜底”“连接池承担吞吐”写进目标和验收条件，否则后续实现 phase 会再次漂移。

## RESEARCH COMPLETE

已形成可直接喂给 planner 的 planning 输入。
