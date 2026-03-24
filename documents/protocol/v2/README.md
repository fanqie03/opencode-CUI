# 协议文档 v2（归档）

> **状态：** 已归档，反映路由架构重设计后、并发注册优化前的协议状态。
> **当前版本：** 请查看 [v3](../v3/README.md)
> **前一版本：** [v1](../v1/README.md)
> **变更日志：** [CHANGELOG.md](./CHANGELOG.md)

OpenCode-CUI 全链路协议报文文档，按层级组织。

## 文档结构

| 文档 | 层级 | 内容概要 |
|------|------|---------|
| [01-miniapp-skillserver.md](./01-miniapp-skillserver.md) | Miniapp ↔ Skill Server | REST API 全量端点、WebSocket StreamMessage 19 种消息类型详解、StreamAssembler 重组逻辑、MessagePart 结构 |
| [02-skillserver-gateway.md](./02-skillserver-gateway.md) | Skill Server ↔ AI Gateway | **[v2 更新]** Gateway 实例自注册/发现、Mesh/Legacy 双策略路由、invoke 6 种 Action、上行 7 种消息类型、session_route 持久化路由 |
| [03-gateway-plugin.md](./03-gateway-plugin.md) | AI Gateway ↔ Plugin | **[v2 更新]** GatewayMessage 新增 gatewayInstanceId、Lua 原子删除、SCAN 替代 KEYS、routeCache 定时清理 |
| [04-plugin-opencode.md](./04-plugin-opencode.md) | Plugin ↔ OpenCode SDK | OpenCode 11 种 SDK 事件原始结构、UpstreamEventExtractor 提取规则、7 种 Action 的 SDK API 调用、ToolDoneCompat 状态机、配置体系 |
| [05-opencode-to-custom-protocol-mapping.md](./05-opencode-to-custom-protocol-mapping.md) | 全链路映射 | OpenCode 事件→自定义协议的四层转换映射、字段名称对应表、特殊时序处理 |
| [06-end-to-end-flows.md](./06-end-to-end-flows.md) | 全链路流程汇总 | 14 个完整业务场景的端到端流程 |
| [07-message-type-lifecycle.md](./07-message-type-lifecycle.md) | 协议报文生命周期 | 按消息类型分类的全流程详解 |
| [CHANGELOG.md](./CHANGELOG.md) | 变更日志 | v1 → v2 完整变更记录 |
| [routing-redesign](../../routing-redesign/) | 路由架构重设计（独立目录） | 设计方案与业界调研 |

## v2 核心变更概览

### Gateway 多实例网格路由
- Gateway 实例通过 Redis 自注册（`gw:instance:{id}`，TTL 30s）
- Skill Server 通过 SCAN 定时发现 Gateway 集群拓扑
- 每个 SS 实例与所有 GW 实例建立独立 WebSocket 连接

### Mesh/Legacy 双策略
- **Mesh（新版 SS）：** 携带 instanceId，本地 routeCache + 被动学习
- **Legacy（旧版 SS）：** 无 instanceId，Owner 心跳 + Rendezvous Hash + Redis 中继
- 自动识别，无需手动切换

### 协议字段增强
- `GatewayMessage` 新增 `gatewayInstanceId`（内部路由，下行剥离）
- `userId` 从 `BIGINT` 改为 `VARCHAR(128)`（防 JS 精度丢失）

### Redis 操作安全性
- `conditionalRemoveConnAk` → Lua 原子脚本（消除 TOCTOU 竞态）
- `KEYS` → `SCAN`（避免 Redis 阻塞）

## 阅读建议

- **快速了解全貌** → 先读 05（映射总表）
- **了解 v2 变更** → 先读 [CHANGELOG.md](./CHANGELOG.md)
- **理解完整业务流程** → 读 06（端到端场景）
- **查某种消息的完整生命周期** → 读 07（按消息类型索引）
- **前端开发** → 重点读 01
- **后端开发** → 重点读 02 + 03
- **Plugin 开发** → 重点读 04 + 03
- **排查问题** → 05 的时序处理章节 + 06 的错误处理场景
- **路由架构决策** → 读 [routing-redesign](../../routing-redesign/)
