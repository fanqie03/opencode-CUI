# v1 → v2 变更日志

> 分支：`route-redesign-0321`

## 概要

v2 协议文档反映了路由架构重设计（Mesh/Legacy 双策略）的所有改动。核心变化集中在 Layer 2（SS↔GW）和 Layer 3（GW↔Plugin），Layer 1（Miniapp↔SS）和 Layer 4（Plugin↔OpenCode）无协议变更。

## 受影响文档

| 文档 | 变更程度 | 说明 |
|------|---------|------|
| 02-skillserver-gateway.md | **大幅更新** | 新增 Gateway 实例自注册/发现、Mesh/Legacy 双策略、session_route 表 |
| 03-gateway-plugin.md | **中等更新** | GatewayMessage 新增字段、Lua 原子删除、SCAN 替代 KEYS、routeCache 定时清理 |
| 01-miniapp-skillserver.md | 无变更 | 原样保留 |
| 04-plugin-opencode.md | 无变更 | 原样保留 |
| 05-opencode-to-custom-protocol-mapping.md | 无变更 | 原样保留 |
| 06-end-to-end-flows.md | 待更新 | 新增路由流程场景（后续补充） |
| 07-message-type-lifecycle.md | 待更新 | gatewayInstanceId 注入/剥离流程（后续补充） |

## 详细变更

### 1. Gateway 实例自注册（新增）

- `GatewayInstanceRegistry` 在 Redis 中自注册，Key: `gw:instance:{instanceId}`
- Value 使用 `ObjectMapper` 序列化（不手动拼接 JSON）
- TTL 30s，每 10s 刷新心跳

### 2. Skill Server 多 Gateway 发现连接（新增）

- `GatewayDiscoveryService` 定时 SCAN Redis 发现 Gateway 实例
- `knownInstanceIds` 使用 `ConcurrentHashMap.newKeySet()`（线程安全）
- `GatewayWSClient` 从单连接改为按 instanceId 管理多连接
- 重连使用 `computeIfPresent` 原子操作避免并发问题

### 3. Mesh/Legacy 双策略路由（新增）

- 新增 `SkillRelayStrategy` 接口
- 新增 `LegacySkillRelayStrategy` 实现（兼容旧版 SS）
- SkillRelayService 根据连接是否携带 `instanceId` 自动选择策略
- Mesh 策略：本地 routeCache + 被动路由学习
- Legacy 策略：Owner 心跳 + Rendezvous Hash + Redis 中继

### 4. GatewayMessage 字段变更

- 新增 `gatewayInstanceId` — Gateway 实例标识，上行时注入，下行时剥离
- `withoutRoutingContext()` 现在同时剥离 `userId + source + gatewayInstanceId`

### 5. Redis 操作改进

- `conditionalRemoveConnAk`: GET+DELETE 改为 Lua 原子脚本（消除 TOCTOU 竞态）
- Gateway 实例扫描: `KEYS gw:instance:*` 改为 `SCAN` 游标遍历（避免阻塞）
- 新增 Key: `conn:ak:{ak}` — Agent 连接绑定

### 6. routeCache 定时清理（新增）

- 每 5 分钟扫描 routeCache，驱逐已关闭连接的条目
- 防止 ConcurrentHashMap 内存无限增长

### 7. 数据库变更

- `ak_sk_credential.user_id`: `BIGINT` → `VARCHAR(128)`
- `agent_connection.user_id`: `BIGINT` → `VARCHAR(128)`
- `agent_connection` 新增唯一约束 `(ak_id, tool_type)`
- 新增 `session_route` 表（Skill Server 侧）

### 8. 配置变更

- 新增 `gateway.instance-id` 配置
- 新增 `gateway.instance-registry.*` 配置块
- 新增 `gateway.skill-relay.owner-ttl-seconds` 配置
- 驱动: MariaDB JDBC → MySQL Connector/J
