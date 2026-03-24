# 服务间路由解耦设计

> 日期：2026-03-24
> 状态：Draft
> 分支：route-redesign-0321

## 1. 背景与问题

当前 skill-server 与 ai-gateway 之间的路由寻址存在双向耦合：

1. **SS 读取 GW 内部状态**：SS 通过 Redis `conn:ak:{ak}` key 查询 Agent 连接在哪个 GW 实例上，用于精确投递 invoke 消息。这是 GW 的内部状态泄漏到了 SS。

2. **GW 维护 SS 实例路由**：GW 的 `SkillRelayService` 维护 `routeCache`（sessionId → SS WebSocket 连接映射）和 Mesh 路由逻辑，GW 需要感知 SS 的实例拓扑。

**目标**：服务间实例寻址由服务内部自行处理，SS 不感知 GW 实例，GW 不感知 SS 实例。

## 2. 设计原则

| 原则 | 说明 |
|------|------|
| 服务间无实例级状态共享 | 发送方不知道接收方有几个实例、资源在哪个实例上 |
| 路由决策归接收方 | 发送方只管投递到某条连接，接收方自行判断是否处理或内部中转 |
| 服务内部自治 | 每个服务内部用自己的 Redis Cluster 解决实例间中转 |
| 用户零感知 | 扩缩容、故障场景下消息不丢、不报错、延迟无明显变化 |

## 3. 整体架构

```
                    ┌─────────────────────────────┐
                    │        Skill Server          │
                    │                              │
                    │  SS-1 ←──Redis Streams──→ SS-2│
                    │   (SS 自有 Redis Cluster)     │
                    └──┬────────────────────────┬──┘
                       │  WebSocket 网状长连接    │
                       │  (一致性哈希选连接)      │
                    ┌──┴────────────────────────┴──┐
                    │        AI Gateway            │
                    │                              │
                    │  GW-1 ←──Redis Streams──→ GW-2│
                    │   (GW 自有 Redis Cluster)     │
                    └──┬────────────────────────┬──┘
                       │  WebSocket              │
                    Agent-A                   Agent-B
```

**关键分层**：

- **服务间**：WebSocket 网状连接 + 一致性哈希路由（第4节）
- **服务发现**：HTTP 接口 + 配置中心降级（第5节）
- **服务内注册表**：MySQL 持久化 + Redis 缓存（第6节）
- **服务内中转**：Redis Streams + 消费者组（第7节）
- **零感知保障**：三级 fallback + pending 队列 + 心跳检测（第8节）

## 4. 服务间路由：一致性哈希

### 4.1 哈希策略

SS 与 GW 之间保持网状 WebSocket 长连接。发送方通过一致性哈希选择连接，不感知对端实例身份。

- **下行（SS → GW）**：`hash(ak)` → 选中某条 GW 连接
- **上行（GW → SS）**：`hash(welinkSessionId)` → 选中某条同 `source_type` 的 SS 连接

### 4.2 一致性哈希环

- 每条 WebSocket 连接作为哈希环上的虚拟节点（用连接 ID 做 key）
- 连接建立 → 加入环，连接断开 → 从环移除
- 扩缩容时只影响环上相邻的一小段映射，大部分路由不变
- 无需任何跨服务协调

### 4.3 GW 侧连接分组

GW 按 `source_type` 分组管理下游连接（如 `skill-server`、`bot-platform`）。上行广播时按组进行，不会错发给其他类型的服务。

### 4.4 与现有方案对比

| 维度 | 现有方案 | 新方案 |
|------|---------|--------|
| 下行路由 | SS 查 `conn:ak` 精确投递到特定 GW 实例 | SS 哈希选连接，GW 内部自行中转 |
| 上行路由 | GW 维护 `routeCache` + Mesh 路由 | GW 哈希选连接，SS 内部自行中转 |
| 实例感知 | 双向感知 | 双向不感知 |
| 扩缩容影响 | 需要更新服务发现 + 路由缓存 | 仅影响哈希环局部映射 |

## 5. 服务发现：HTTP 接口 + 配置中心降级

### 5.1 主路径：HTTP 接口

GW 暴露内部 HTTP 接口，返回当前所有 GW 实例的 WebSocket 地址列表：

```
GET /internal/instances

Response:
{
  "instances": [
    { "instanceId": "gw-pod-1", "wsUrl": "ws://10.0.1.1:8081/ws/skill" },
    { "instanceId": "gw-pod-2", "wsUrl": "ws://10.0.1.2:8081/ws/skill" }
  ]
}
```

- SS 定期调用该接口（通过 GW 的负载均衡器地址访问任一实例）
- 对比本地已知列表，新增实例建连、消失实例断连

### 5.2 降级路径：配置中心

HTTP 接口不可用时，从配置中心读取静态 GW 实例列表作为兜底。

### 5.3 刷新策略

- 定期刷新间隔：10 秒（可配置）
- 接口调用超时：3 秒
- 连续 3 次失败后降级到配置中心
- 配置中心也不可用时，维持现有连接不变

### 5.4 需要删除的现有机制

- `GatewayInstanceRegistry`：GW 向共享 Redis 注册 `gw:instance:{id}` → 改为 HTTP 接口
- `GatewayDiscoveryService`：SS 扫描共享 Redis `gw:instance:*` → 改为调用 HTTP 接口

## 6. 服务内资源注册表：MySQL + Redis 缓存

每个服务内部维护"资源在哪个实例上"的映射。这是纯内部状态，不暴露给其他服务。

### 6.1 GW 侧：Agent 连接注册表

**MySQL 表 `gw_agent_route`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| ak | VARCHAR(64) PK | Agent Access Key |
| instance_id | VARCHAR(128) | GW 实例 ID |
| status | VARCHAR(16) | CONNECTED / DISCONNECTED |
| connected_at | DATETIME | 连接时间 |
| updated_at | DATETIME | 最后更新时间 |

**Redis 缓存**：`gw:internal:agent:{ak} → instanceId`

- Agent 注册：写 MySQL + 写 Redis（TTL 与心跳周期关联）
- Agent 心跳：刷新 Redis TTL + 定期更新 MySQL `updated_at`
- Agent 断连：删 Redis + 更新 MySQL status 为 DISCONNECTED

**命名约定**：`gw:internal:` 前缀明确这是 GW 内部命名空间。

### 6.2 SS 侧：Session 所有权注册表

**MySQL 表**：复用现有 `session_route` 表（`source_instance` 字段记录所有权）

**Redis 缓存**：`ss:internal:session:{welinkSessionId} → instanceId`

- Session 创建：写 MySQL + 写 Redis
- Session 活跃：消息处理时刷新 Redis TTL
- Session 关闭：删 Redis + 更新 MySQL status 为 CLOSED

### 6.3 缓存一致性

- 写入路径：先写 MySQL，成功后写 Redis
- 读取路径：先读 Redis → 未命中则查 MySQL 并回填 Redis
- MySQL 为 source of truth，Redis 缓存允许短暂不一致
- 实例启动时从 MySQL 预热 Redis 缓存

## 7. 服务内中转：Redis Streams

各服务用自己的 Redis Cluster，通过 Redis Streams + 消费者组实现实例间消息中转。

### 7.1 Stream 设计

**GW 侧**：
- 定向 Stream：`gw:relay:stream:{instanceId}`（每个实例一个）
- 广播 Stream：`gw:relay:stream:broadcast`
- 消费者组：每个实例创建自己的消费者组

**SS 侧**：
- 定向 Stream：`ss:relay:stream:{instanceId}`
- 广播 Stream：`ss:relay:stream:broadcast`
- 消费者组：同上

### 7.2 消息格式

```json
{
  "type": "relay",
  "originalMessage": "<原始 GatewayMessage JSON>",
  "sourceInstance": "gw-pod-1",
  "targetAk": "agent-key-123",
  "timestamp": 1711267200000
}
```

### 7.3 消费者组配置

- 每个实例作为消费者组的一个消费者
- 使用 `XREADGROUP` 阻塞读取
- 处理后 `XACK` 确认
- 未 ACK 的消息可被其他消费者自动接管（实例宕机场景）

### 7.4 Stream 清理

- 定向 Stream：已 ACK 的消息定期 `XTRIM`，保留最近 1000 条或 5 分钟
- 广播 Stream：所有消费者 ACK 后清理
- 避免 Stream 无限增长占用内存

### 7.5 与 Redis pub/sub 对比

| 维度 | Redis pub/sub | Redis Streams |
|------|--------------|---------------|
| 可靠性 | fire-and-forget，订阅者不在线则丢失 | 持久化，消费者组保证至少一次投递 |
| 背压 | 无 | XREADGROUP BLOCK 原生支持 |
| 宕机恢复 | 消息丢失 | pending 消息可被其他消费者接管 |
| 消息积压 | 不支持 | Stream 缓冲，可控清理 |

## 8. 零感知保障：三级 Fallback

### 8.1 投递流程

以 GW 收到下行消息、需要投递给 Agent-X 为例：

```
GW-A 收到消息（SS 通过哈希选中 GW-A）
  │
  ├─ 第一级：本地检查
  │    Agent-X 在本实例？→ 直接投递，结束 ✓
  │
  ├─ 第二级：查注册表定向转发
  │    Redis 查 gw:internal:agent:{ak} → GW-B
  │    检查 GW-B 心跳是否存活
  │    存活 → XADD 到 gw:relay:stream:gw-b → 结束 ✓
  │    不存活 → 进入第三级
  │
  └─ 第三级：广播自判
       XADD 到 gw:relay:stream:broadcast
       各实例自判本地有无 Agent-X
       有 → 处理并更新注册表
       无 → 忽略
       全部无应答 → 写入 pending 队列
```

SS 侧流程同理（session ownership 替代 agent connection）。

### 8.2 Pending 队列

当三级 fallback 都未能投递时（Agent/Session 确实暂时不可达）：

- 消息写入 Redis Stream：`gw:pending:{ak}` 或 `ss:pending:{welinkSessionId}`
- TTL：30 秒（超过此时间认为消息已过期）
- Agent 重连 / Session 被接管后，新 owner 实例检查 pending 队列并补投

### 8.3 实例心跳

每个实例定期向自己的 Redis 写心跳：

- Key：`gw:heartbeat:{instanceId}` 或 `ss:heartbeat:{instanceId}`
- 写入间隔：5 秒
- TTL：15 秒
- 用途：转发前检查目标实例是否存活，不存活则跳过第二级直接广播

### 8.4 场景覆盖

| 场景 | 处理方式 | 用户感知 |
|------|---------|---------|
| 正常运行 | 第一级或第二级命中 | 无延迟 |
| GW 实例宕机 | 心跳过期 → 跳过定向 → 广播 → Agent 重连后从 pending 补投 | 消息延迟数秒（Agent 重连时间） |
| SS 实例宕机 | 心跳过期 → 广播 → 其他 SS 接管 session_route → 补投 | 消息延迟数秒 |
| GW 扩容 | 新实例心跳注册 → 参与广播自判 → Agent 新连接自然注册 | 无感知 |
| SS 扩容 | 新实例加入消费者组 → 可接收中转消息 | 无感知 |
| GW 优雅缩容 | @PreDestroy 清理注册表 + Agent 主动断连重连 | 无感知 |
| SS 优雅缩容 | @PreDestroy 关闭路由 + 迁移 pending | 无感知 |

### 8.5 完整时序：GW-B 宕机场景

```
T+0.0s   GW-B 宕机，Agent-X 断连
T+0.0s   SS 发 invoke 给 Agent-X，hash 选中 GW-A
T+0.0s   GW-A 本地无 Agent-X
T+0.0s   GW-A 查注册表 → 指向 GW-B
T+0.0s   GW-A 检查 GW-B 心跳 → 还存在（TTL 未过期）
T+0.0s   GW-A 写入 gw:relay:stream:gw-b → GW-B 已死，消息 pending
T+0.1s   XREADGROUP 超时，GW-A 发现 GW-B 未消费
T+0.1s   GW-A 广播到 gw:relay:stream:broadcast
T+0.1s   所有存活 GW 自判 → 都无 Agent-X
T+0.1s   GW-A 写入 pending 队列 gw:pending:{ak}
T+0.1s   GW-A 清理 GW-B 的注册表记录
T+3.0s   Agent-X 重连到 GW-C
T+3.0s   GW-C 注册 Agent-X，检查 pending 队列
T+3.0s   GW-C 发现积压消息，立即投递给 Agent-X
```

用户感知：消息延迟约 3 秒，无丢失、无报错。

## 9. 需要删除/重构的现有代码

| 现有代码 | 处理方式 |
|---------|---------|
| `conn:ak:{ak}` Redis key | 删除。SS 不再读取。GW 内部改用 `gw:internal:agent:{ak}` |
| `SkillRelayService.routeCache` | 删除。GW 不再维护 SS 实例路由缓存 |
| `SkillRelayService` Mesh 路由逻辑 | 重构为一致性哈希 + 服务内中转 |
| `GatewayInstanceRegistry` | 删除。改为 HTTP 接口暴露实例列表 |
| `GatewayDiscoveryService` | 重构。改为调用 HTTP 接口 + 配置中心降级 |
| `GatewayRelayService` 中查 `conn:ak` 的逻辑 | 删除。改为一致性哈希选连接 |

## 10. 基础设施依赖

| 组件 | 规格 | 用途 |
|------|------|------|
| GW Redis Cluster | 3主3从，8GB | Agent 注册表缓存、Redis Streams 中转、心跳、pending 队列 |
| SS Redis Cluster | 3主3从，8GB | Session 注册表缓存、Redis Streams 中转、心跳、pending 队列 |
| GW MySQL | 已有 `ai_gateway` DB | `gw_agent_route` 表 |
| SS MySQL | 已有 `skill_db` DB | 复用 `session_route` 表 |

**注意**：GW 和 SS 的 Redis Cluster 是独立的，不共享。服务间唯一的通信通道是 WebSocket 长连接。

## 11. 配置参数

### GW 侧

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:gateway-local}}
  internal-api:
    enabled: true                    # 暴露 /internal/instances 接口
  heartbeat:
    interval-ms: 5000               # 心跳写入间隔
    ttl-seconds: 15                  # 心跳 TTL
  relay:
    stream-max-len: 1000            # Stream 最大长度
    stream-ttl-minutes: 5           # Stream 消息保留时间
    pending-ttl-seconds: 30         # Pending 队列 TTL
    broadcast-timeout-ms: 200       # 广播等待应答超时
```

### SS 侧

```yaml
skill:
  instance-id: ${HOSTNAME:skill-server-local}
  gateway:
    discovery-url: ${GATEWAY_LB_URL:http://localhost:8081}/internal/instances
    discovery-interval-ms: 10000    # 服务发现刷新间隔
    discovery-timeout-ms: 3000      # HTTP 调用超时
    discovery-fail-threshold: 3     # 连续失败次数后降级
    fallback-ws-urls:               # 配置中心降级地址
      - ws://gateway-1:8081/ws/skill
      - ws://gateway-2:8081/ws/skill
  heartbeat:
    interval-ms: 5000
    ttl-seconds: 15
  relay:
    stream-max-len: 1000
    stream-ttl-minutes: 5
    pending-ttl-seconds: 30
    broadcast-timeout-ms: 200
```
