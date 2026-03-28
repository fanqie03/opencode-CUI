# Phase 1: Gateway/Source 路由方案定稿 - Discussion Log

> **审计轨迹专用。** 不作为规划、研究或执行 agent 的输入。
> 正式决策以 `01-CONTEXT.md` 为准；本文件只保留讨论过程中考虑过的替代项与收口理由。

**Date:** 2026-03-29
**Phase:** 01-gateway-source
**Areas discussed:** GW 内部路由状态落点、协议与路由键、legacy 兼容与故障降级、新版 Source 连线模型

---

## GW 内部路由状态落点

| Option | Description | Selected |
|--------|-------------|----------|
| GW 共享 Redis 作真源 | `GW` 内部路由真源统一放在 GW 共享 Redis，本地只做缓存；`SS` Redis 和数据库不进入 GW 回路由热路径。 | X |
| 只把 `sourceInstance` 放 Redis | `toolSessionId / welinkSessionId` 仍主要依赖接入 GW 的本地学习与缓存。 | |
| 数据库或 `SS` Redis 参与热路径 | 通过数据库或 `SS` Redis 参与 GW 回路由真源。 | |

**User's choice:** 确认 GW 共享 Redis 是 `GW` 内部路由真源。  
**Notes:** 随后进一步确认 `toolSessionId` 和 `welinkSessionId` 都应进入 GW 共享 Redis，作为正式路由索引，而不是只保留一个主键或继续依赖本地学习。

---

## 协议与路由键

| Option | Description | Selected |
|--------|-------------|----------|
| 显式 `route_bind / route_unbind` | v3 握手保留 `source` 与 `instanceId`，新增 `protocolVersion`，由 Source 主动显式绑定和解绑路由。 | X |
| 继续被动学习 | 继续依赖 GW 从业务消息中推断完整路由关系。 | |
| 只保留 legacy 机制 | 继续依赖 `route_confirm / route_reject`、广播试探和历史兼容逻辑。 | |

**User's choice:** 同意 v3 必须显式 `route_bind / route_unbind`。  
**Notes:** 同时确认 `instanceId` 在 v3 语义上等于 `sourceInstanceId`；查路由优先级为 `toolSessionId > welinkSessionId > legacy source fallback`；`route_confirm / route_reject` 只保留给 legacy；缺少必要字段时返回 `protocol_error`。

---

## legacy 兼容与故障降级

| Option | Description | Selected |
|--------|-------------|----------|
| v3 定向 relay，legacy 受控兜底 | v3 只走定向 relay 和明确失败；legacy 保留 owner/fallback，并仅在兼容场景下允许受控广播。 | X |
| v3 继续允许广播学习 | 新版协议也允许广播命中后再学习路由。 | |
| 完全取消 legacy 兜底 | 立即要求所有旧服务跟随新语义工作。 | |

**User's choice:** 确认采用“v3 定向 relay + legacy 受控广播兜底”。  
**Notes:** 还确认了连接断开后必须立即清理索引，重连后重新 `route_bind`，不依赖旧状态自动恢复。

---

## 新版 Source 连线模型

| Option | Description | Selected |
|--------|-------------|----------|
| 只连统一 ALB + 小连接池 | 每个 Source 实例只连统一 ALB WebSocket 域名，维护连接池；业务路由与传输路由解耦。 | X |
| 统一 ALB + 双连接冗余 | 只保留 2 条连接用于冗余，主要仍按实例级单 owner 理解。 | |
| 继续发现所有 GW 实例并全连 | 保留 `GatewayDiscoveryService` / 实例直连作为主路径。 | |

**User's choice:** 同意只连统一 ALB，但在讨论中进一步补充“单条 WebSocket 会有并发瓶颈”，因此最终定稿为小连接池模型。  
**Notes:** 连接池默认值从最初建议的 2 条修正为 4 条，可配置提升到 8 条；实例级单 owner 语义被修正为连接级 owner；同一会话通过一致性哈希稳定到同一条连接，不同会话可分散到不同连接以避免阻塞。

---

## the agent's Discretion

- Redis key 命名、TTL、反向索引存储结构、缓存淘汰细节留给后续研究与规划阶段细化。
- 会话一致性哈希的实现形式、观测指标与连接池扩容阈值留给后续实现与压测 phase 决定。

## Deferred Ideas

None.
