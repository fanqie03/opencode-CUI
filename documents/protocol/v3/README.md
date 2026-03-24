# 协议文档 v3（当前版本）

> **状态：** 当前版本，反映并发注册分布式锁、AkSk 认证缓存分级、设备绑定校验优化及日志规范化等改动。
> **前一版本：** [v2](../v2/README.md)
> **变更日志：** [CHANGELOG.md](./CHANGELOG.md)

OpenCode-CUI 全链路协议报文文档，按层级组织。

## 文档结构

| 文档 | 层级 | 内容概要 |
|------|------|---------|
| [01-miniapp-skillserver.md](./01-miniapp-skillserver.md) | Miniapp ↔ Skill Server | REST API 全量端点、WebSocket StreamMessage 19 种消息类型详解、StreamAssembler 重组逻辑、MessagePart 结构 |
| [02-skillserver-gateway.md](./02-skillserver-gateway.md) | Skill Server ↔ AI Gateway | **[v3 更新]** Gateway 实例自注册/发现、Mesh/Legacy 双策略路由、invoke 6 种 Action、上行 7 种消息类型、conn:ak 连接注册 |
| [03-gateway-plugin.md](./03-gateway-plugin.md) | AI Gateway ↔ Plugin | **[v3 更新]** 并发注册分布式锁、conn:ak 绑定与心跳刷新、Lua 原子条件删除、AkSk 认证 GATEWAY/REMOTE 双模式 |
| [04-plugin-opencode.md](./04-plugin-opencode.md) | Plugin ↔ OpenCode SDK | OpenCode 11 种 SDK 事件原始结构、UpstreamEventExtractor 提取规则、7 种 Action 的 SDK API 调用、ToolDoneCompat 状态机、配置体系 |
| [05-opencode-to-custom-protocol-mapping.md](./05-opencode-to-custom-protocol-mapping.md) | 全链路映射 | OpenCode 事件→自定义协议的四层转换映射、字段名称对应表、特殊时序处理 |
| [06-end-to-end-flows.md](./06-end-to-end-flows.md) | 全链路流程汇总 | **[v3 更新]** 14 个完整业务场景的端到端流程，含分布式锁注册、Mesh 路由 |
| [07-message-type-lifecycle.md](./07-message-type-lifecycle.md) | 协议报文生命周期 | **[v3 更新]** 按消息类型分类的全流程详解，含 conn:ak 绑定/刷新流程 |
| [CHANGELOG.md](./CHANGELOG.md) | 变更日志 | v2 → v3 完整变更记录 |

## v3 核心变更概览

### 并发注册分布式锁
- Agent 注册使用 Redis 分布式锁（`gw:register:lock:{ak}`，TTL 10s）
- 锁释放使用 Lua 脚本原子校验 owner，防止误释放
- 保护设备绑定校验、重复连接检查、数据库注册的完整原子序列

### conn:ak 连接注册
- 注册成功后绑定 `conn:ak:{ak}` → `gatewayInstanceId`（TTL 120s）
- 心跳刷新 TTL，断连时 Lua 原子条件删除（仅当前实例持有时删除）
- 防止 Agent 快速重连到其他 Gateway 实例时旧实例误删新绑定

### AkSk 认证双模式
- **GATEWAY 模式：** 本地 HMAC-SHA256 验签（查 ak_sk_credential 表）
- **REMOTE 模式：** L1 Caffeine → L2 Redis → L3 外部身份 API 三级缓存
- V2 的 `skip-verification` 调试开关已移除

### 设备绑定校验优化
- `DeviceBindingService` 查询 agent_connection 最新记录验证 MAC + toolType
- Fail-Open 策略：服务未启用/异常时允许通过
- 首次注册无条件通过

### MDC 上下文保护
- `EventRelayService.relayToSkillServer()` 保存/恢复调用方 MDC 上下文
- 防止消息转发过程中清除调用方的 traceId、ak 等追踪字段

## 阅读建议

- **快速了解全貌** → 先读 05（映射总表）
- **了解 v3 变更** → 先读 [CHANGELOG.md](./CHANGELOG.md)
- **理解完整业务流程** → 读 06（端到端场景）
- **查某种消息的完整生命周期** → 读 07（按消息类型索引）
- **前端开发** → 重点读 01
- **后端开发** → 重点读 02 + 03
- **Plugin 开发** → 重点读 04 + 03
- **排查问题** → 05 的时序处理章节 + 06 的错误处理场景
