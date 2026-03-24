# Layer 2：Skill Server ↔ AI Gateway 协议详解（v3）

> **v3 变更：** 注册流程增加 conn:ak 绑定；Mesh 策略路由补充被动学习与 routeCache 清理细节；MDC 上下文保护修复。
> **v2 变更：** SS→GW 连接从单连接改为 Discovery 多连接；新增 Mesh/Legacy 双策略路由；新增 `session_route` 表；Gateway 自注册机制。

## 概述

Skill Server 与 AI Gateway 之间通过 WebSocket 双向通信。Skill Server 作为客户端连接 Gateway 的 `/ws/skill` 端点。

Skill Server 通过 `GatewayDiscoveryService` 动态发现所有 Gateway 实例，并与每个实例建立独立 WebSocket 连接。

```
┌──────────────┐                    ┌──────────────┐
│  Skill       │   WebSocket ×N     │  AI Gateway  │
│  Server      │ ──────────────────→│  集群         │
│  :8082       │ ←──────────────────│  :8081       │
└──────────────┘                    └──────────────┘
    GatewayWSClient ×N             SkillWebSocketHandler
    (WS 客户端 per GW)              (WS 服务端)
    GatewayDiscoveryService        GatewayInstanceRegistry
    (实例发现)                       (实例自注册)
```

---

## 一、WebSocket 连接协议

### 1.1 连接建立

**地址：** `ws://{gateway-host}:8081/ws/skill`
**认证方式：** WebSocket 子协议 (Sec-WebSocket-Protocol)

**子协议格式：**
```
Sec-WebSocket-Protocol: auth.{Base64URL(JSON)}
```

**认证 JSON：**
```json
{
  "token": "sk-intl-9f2a7d3e4b1c",    // 内部 Token
  "source": "skill-server",             // 来源服务标识
  "instanceId": "ss-instance-001"       // Skill Server 实例 ID（Mesh 策略必需）
}
```

**Gateway 验证逻辑（SkillWebSocketHandler.beforeHandshake）：**
1. 从 `Sec-WebSocket-Protocol` 头提取 `auth.` 前缀的值
2. Base64URL 解码 → JSON 解析
3. 验证 `token` 与配置的 `skill.gateway.internal-token` 匹配
4. 验证 `source` 非空
5. 提取 `instanceId`（若存在），存入 WebSocket 会话属性
6. 将 `source` 存入 WebSocket 会话属性
7. 回显子协议到响应头（RFC 6455 要求）

### 1.2 Gateway 实例自注册

**GatewayInstanceRegistry（Gateway 侧）：**

每个 Gateway 实例启动时在 Redis 中自注册：

```
Redis Key: gw:instance:{instanceId}
Value:     {"wsUrl":"ws://{host}:8081/ws/skill","startedAt":"...","lastHeartbeat":"..."}
TTL:       30s（可配置 gateway.instance-registry.ttl-seconds）
```

**心跳刷新：** 每 10s 刷新一次 TTL（`@Scheduled(fixedDelay)`）
**值序列化：** 使用 Spring 注入的 `ObjectMapper`，不手动拼接 JSON
**关闭清理：** `@PreDestroy` 时删除 Redis Key

### 1.3 Gateway 实例发现

**GatewayDiscoveryService（Skill Server 侧）：**

定时扫描 Redis 发现所有活跃的 Gateway 实例：

```
扫描方式: Redis SCAN cursor（替代 KEYS，避免阻塞）
匹配模式: gw:instance:*
扫描间隔: 10s（可配置 skill.gateway.discovery-interval-ms，默认 10000）
```

**发现逻辑：**
```
每 10s 执行 discover():
  1. SCAN gw:instance:* 获取所有 key
  2. 解析每个 key 的 value → 提取 wsUrl
  3. 与 knownInstanceIds（ConcurrentHashMap.newKeySet()）对比
  4. 新增实例 → 通知 listener.onGatewayAdded(instanceId, wsUrl)
  5. 消失实例 → 通知 listener.onGatewayRemoved(instanceId)
```

**线程安全：** `knownInstanceIds` 使用 `ConcurrentHashMap.newKeySet()` 确保并发安全。

### 1.4 连接管理

**GatewayWSClient（Skill Server 侧）：**

- 初始化时连接 `seed-gateway-url`（种子地址，兼容无 Redis 注册的 Legacy Gateway）
- `GatewayDiscoveryService` 发现新实例 → 自动建立新连接
- 实例消失 → 关闭对应连接
- 按 instanceId 管理多连接 Map

**Seed URL 去重逻辑：**

初始化时通过 seed URL 创建的连接 ID 为 `seed-{host:port}`。当 GatewayDiscoveryService 发现新实例时：
- 若 seed 连接的 URL 与已发现实例的 URL 匹配：
  - seed 连接仍存活 → 提升（remap key，不关闭连接）
  - seed 连接已断开 → 关闭旧连接，用发现实例 ID 新建连接
- 若不匹配 → 正常新建连接

**重连策略：**
```
每个连接独立重连
初始延迟: 1s（可配置 skill.gateway.reconnect-initial-delay-ms）
最大延迟: 30s（可配置 skill.gateway.reconnect-max-delay-ms）
退避算法: 指数退避 delay = min(initialDelay × 2^(attempts-1), maxDelay)
使用 computeIfPresent 原子操作避免并发重连问题
内部 Token 无效 → 停止重连（不重试认证错误）
```

**发送接口：**
- `sendToGateway(message)` — Legacy 接口，发送到任意活跃连接
- `sendToGateway(gwInstanceId, message)` — 精确发送到指定 Gateway 实例
- `broadcastToAllGateways(message)` — 发送到所有已连接的 Gateway 实例
- 精确发送失败 → 降级为广播

**[v3] conn:ak 精确路由：** Skill Server 的 `GatewayRelayService.sendInvokeToGateway()` 在发送 invoke 前，通过 `Redis GET conn:ak:{ak}` 查询 Agent 连接在哪个 Gateway 实例，优先精确发送到该实例，失败再广播。

---

## 二、下行路由：用户操作 → Agent 执行

> **下行 = Miniapp/IM 用户操作 → Skill Server → Gateway → Plugin → OpenCode**
>
> 核心问题：用户发了一条消息，系统怎么找到正确的 Agent 来执行？

### 2.1 Skill Server 侧：选择 Gateway 实例

Skill Server 的 `GatewayRelayService.sendInvokeToGateway()` 负责把 invoke 消息投递到正确的 Gateway。

```
用户操作 (REST API)
  ↓
Skill Server 构造 invoke 消息:
  { type: "invoke", ak, userId, source: "skill-server", welinkSessionId, action, payload }
  ↓
查询 Agent 连接位置:
  Redis GET conn:ak:{ak} → 返回 gwInstanceId（Agent 当前连接的 Gateway 实例）
  ↓
投递策略:
  ┌── gwInstanceId 存在 → 精确发送到该 Gateway 实例
  │     ↓ 精确发送失败?
  │     └── fallback: 广播到所有已连接的 Gateway 实例
  └── gwInstanceId 不存在（Agent 可能不在线）→ 广播到所有 Gateway 实例
```

**关键点：** `conn:ak:{ak}` 是 V3 新增的 Redis Key，在 Agent 注册时写入、心跳时刷新、断连时条件删除。它让 Skill Server 能跳过广播，直接投递到正确的 Gateway。

### 2.2 Gateway 侧：验证 + 转发到 Agent

Gateway 的 `SkillWebSocketHandler` **仅接受 `invoke` 类型**的下行消息，收到后交给 `SkillRelayService.handleInvokeFromSkill()` 处理。

**验证流程：**
```
Step 1: 验证 source — 消息中的 source 必须与握手时绑定的 source 一致
  → 不一致 → 发送协议错误 source_mismatch + 拒绝

Step 2: 验证 ak — 必须非空
  → 空 → 日志 WARN + 拒绝

Step 3: 验证 userId — 必须与 Redis gw:agent:user:{ak} 中存储的 userId 一致
  → 不一致 → 日志 WARN [SKIP] + 拒绝
```

**路由学习 + 投递：**
```
Step 4: 被动学习路由（仅 Mesh 策略的 SS 连接）
  从 invoke 消息的 payload 中提取 toolSessionId
  learnRoute(): 用 put() 写入 routeCache
    routeCache[toolSessionId] = 当前 SS 连接
    routeCache["w:" + welinkSessionId] = 当前 SS 连接
  （这些映射用于后续上行消息的精确路由，见第三节）

Step 5: 投递到 Agent
  Redis PUBLISH agent:{ak} = message.withoutRoutingContext()
  → 剥离 userId / source / gatewayInstanceId（Agent 不需要这些字段）
  → Agent 所在的 Gateway 实例已订阅 agent:{ak}，收到后发给本地 WebSocket
```

**invoke 消息格式：**
```json
{
  "type": "invoke",
  "ak": "agent-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "chat",
  "payload": {"text": "你好", "toolSessionId": "xxx"}
}
```

**action 取值：**

| Action | 触发场景 | payload 关键字段 |
|--------|---------|-----------------|
| `chat` | 用户发送消息 | `{text, toolSessionId}` |
| `create_session` | 创建远端会话 | `{title?}` |
| `close_session` | 关闭会话 | `{toolSessionId}` |
| `abort_session` | 中止会话 | `{toolSessionId}` |
| `permission_reply` | 权限回复 | `{permissionId, response, toolSessionId}` |
| `question_reply` | 问题回复 | `{answer, toolCallId?, toolSessionId}` |

### 2.3 多实例下行全链路示意

```
场景: SS-1 和 SS-2 都连接了 GW-A 和 GW-B；Agent 连接在 GW-A

用户在 SS-1 发消息:
  SS-1: Redis GET conn:ak:agent-1 → "GW-A"
  SS-1 → GW-A: invoke(chat, ak=agent-1)   ← 精确投递，不广播
  GW-A: 验证 source/userId → 学习路由 → PUBLISH agent:agent-1
  GW-A: 本地已订阅 agent:agent-1 → 发给 Plugin WebSocket
  Plugin: 执行 ChatAction → OpenCode SDK prompt()

若 conn:ak 不存在（Agent 刚断连）:
  SS-1: Redis GET conn:ak:agent-1 → null
  SS-1: broadcastToAllGateways(invoke)  ← 广播到 GW-A 和 GW-B
  GW-A: 有 Agent 连接 → PUBLISH agent:agent-1 → 投递成功
  GW-B: 无 Agent 连接 → PUBLISH agent:agent-1 → 无订阅者，消息丢弃
```

---

## 三、上行路由：Agent 事件 → Skill Server

> **上行 = OpenCode → Plugin → Gateway → Skill Server → Miniapp/IM**
>
> 核心问题：Agent 产生了一个事件，Gateway 怎么把它投递到正确的 Skill Server 实例？

### 3.1 Gateway 侧：路由上下文注入 + Skill 路由

Agent 的 `tool_event` / `tool_done` / `session_created` 等消息到达 Gateway 后，由 `EventRelayService.relayToSkillServer()` 处理。

**路由上下文注入（v3 含 MDC 保护）：**
```
Step 1: MdcHelper.snapshot() → 保存调用方 MDC 上下文
Step 2: message.ensureTraceId() → 若无 traceId 则生成 UUID
Step 3: 注入 ak（从 sessionAkMap 取得）
Step 4: 注入 userId（从 Redis gw:agent:user:{ak} 取得）
Step 5: SkillRelayService.relayToSkill(message) → 路由到 Skill Server（见下）
Step 6: finally → MdcHelper.restore(previousMdc) → 恢复调用方 MDC
```

### 3.2 上行消息类型

| 消息类型 | 源头 | 触发条件 | 关键字段 |
|---------|------|---------|---------|
| `tool_event` | Agent | OpenCode SDK 事件 | toolSessionId, event |
| `tool_done` | Agent | 执行完成 | toolSessionId, usage |
| `tool_error` | Agent | 执行错误 | toolSessionId, error |
| `session_created` | Agent | 会话创建成功 | welinkSessionId, toolSessionId |
| `agent_online` | Gateway | Agent 注册成功 | ak, toolType, toolVersion |
| `agent_offline` | Gateway | Agent 断连/超时 | ak |
| `status_response` | Agent | 健康状态回复 | opencodeOnline |

### 3.3 Mesh 策略：routeCache 精确路由 + 广播降级

适用于握手时携带 `instanceId` 的新版 Skill Server。

**路由表（routeCache）— Gateway 内存中维护：**
```
routeCache: ConcurrentHashMap<String, WebSocketSession>

Key 有两种形式:
  toolSessionId         → SS 的 WebSocket 连接   （精确，由 Agent 事件中的 toolSessionId 定位）
  "w:" + welinkSessionId → SS 的 WebSocket 连接   （备选，由 invoke 中的 welinkSessionId 定位）
```

**路由表怎么建立的？** 被动学习，不需要额外注册：
- 当 Skill Server 发 invoke 到 Gateway 时，Gateway 从消息中提取 `toolSessionId`/`welinkSessionId`，记录"这个 session 属于这条 SS 连接"
- 后续 Agent 回传的 `tool_event` 自然就能通过 `toolSessionId` 找到正确的 SS 连接

**上行路由决策（`SkillRelayService.relayToSkill`）：**
```
Step 1: routeCache[toolSessionId] 命中且连接活跃?
  → 是 → 精确发送 + 用 putIfAbsent 补充学习 welinkSessionId 映射
  → 否 → 继续

Step 2: routeCache["w:" + welinkSessionId] 命中且连接活跃?
  → 是 → 精确发送 + 用 putIfAbsent 补充学习 toolSessionId 映射
  → 否 → 继续

Step 3: 广播到同 sourceType 所有 Mesh SS 连接（至少一个成功即返回 true）

Step 4: Mesh 全部失败 → 回退到 Legacy 策略
```

**为什么下行学习用 `put()` 而上行学习用 `putIfAbsent()`？**
- 下行 invoke 是 Skill Server 主动声明"这个 session 由我处理"，权威性高，应覆盖旧映射
- 上行 tool_event 是 Gateway 推断"这条消息通过某个 SS 连接命中了"，不应覆盖已有的权威映射

**routeCache 清理：**
- **连接断开时：** `invalidateRoutesForSession()` 遍历 routeCache，删除所有指向该 session 的条目
- **定期清理：** `evictStaleRouteCache()` 每 5 分钟扫描 routeCache，驱逐已关闭连接的条目（防内存泄漏）

### 3.4 Legacy 策略：Owner 心跳 + Redis 中继

适用于握手时不携带 `instanceId` 的旧版 Skill Server。

**核心思路：** 每个 Gateway 实例通过心跳声明自己"拥有"某个 source 的连接。上行消息到达时，通过 Rendezvous Hash 选择 Owner 实例，若不在本地则通过 Redis 中继转发。

**Owner 心跳：**
```
每 10s:
  SET gw:source:owner:{source}:{instanceId} "alive" EX 30
  SADD gw:source:owners:{source} "{source}:{instanceId}"
```

**路由决策：**
```
Step 1: 尝试本地默认 link
  sendViaDefaultLink(source, message) → 成功 → return

Step 2: 本地无连接 → 选择 Owner
  SMEMBERS gw:source:owners:{source}
  过滤存活: EXISTS gw:source:owner:{source}:{ownerKey}
  Rendezvous Hash: max(hash(type + "|" + ownerKey)) → 选择 instanceId

Step 3: 选中自己 → 重试本地 sendViaDefaultLink

Step 4: 选中远程实例 → Redis PUBLISH gw:relay:{remoteInstanceId}
  远程实例收到 → handleRelayedMessage() → sendViaDefaultLink()
```

**Rendezvous Hashing：**
```java
long rendezvousScore(String key, String ownerKey) {
  String stableKey = key != null ? key : "default";
  return Integer.toUnsignedLong((stableKey + "|" + ownerKey).hashCode());
}
// 选择得分最高的 ownerKey → 保证同一 key 始终路由到同一 Owner
```

### 3.5 多实例上行全链路示意

```
场景: SS-1(Mesh) 和 SS-2(Mesh) 都连接了 GW-A；Agent 连接在 GW-A
SS-1 之前发过 invoke(welinkSessionId=12345, toolSessionId=xxx)

Agent 产生 tool_event(toolSessionId=xxx):
  GW-A: EventRelayService.relayToSkillServer()
    → 注入 ak, userId
    → SkillRelayService.relayToSkill(message)
    → routeCache["xxx"] 命中 SS-1 的连接 → 精确发送给 SS-1
    → SS-2 不会收到此消息 ✓

Agent 产生 session_created(welinkSessionId=12345, toolSessionId=yyy):
  GW-A: routeCache["yyy"] 未命中
    → routeCache["w:12345"] 命中 SS-1 → 发送给 SS-1
    → 同时学习: routeCache["yyy"] = SS-1（后续 tool_event 可直接命中）

全新 Agent 的首条 tool_event（routeCache 完全为空）:
  GW-A: routeCache 全部未命中
    → 广播到所有 Mesh SS 连接（SS-1 和 SS-2 都会收到）
    → 每个 SS 自行判断是否持有该 session
```

---

## 四、conn:ak 连接注册（v3 新增）

### 4.1 用途

`conn:ak:{ak}` 记录每个 Agent 当前连接的 Gateway 实例 ID，供 Gateway 集群内精确路由和连接归属判定。

### 4.2 生命周期

```
注册时: SET conn:ak:{ak} = {gatewayInstanceId} EX 120   (handleRegister 成功后)
心跳时: EXPIRE conn:ak:{ak} 120                          (每次 heartbeat)
断连时: Lua 原子条件删除                                    (afterConnectionClosed)
```

### 4.3 条件删除 Lua 脚本

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
else
  return 0
end
```

- `KEYS[1]` = `conn:ak:{ak}`
- `ARGV[1]` = 当前 Gateway 的 `instanceId`
- 防止 Agent 快速重连到其他实例后，旧实例断连清理时误删新绑定

---

## 五、Redis Key 与频道总览

| Key/Channel | 类型 | 用途 | TTL |
|-------------|------|------|-----|
| `agent:{ak}` | Pub/Sub | Agent 消息投递（invoke 下发） | — |
| `gw:relay:{instanceId}` | Pub/Sub | 跨 Gateway 实例消息中继（Legacy） | — |
| `user-stream:{userId}` | Pub/Sub | 跨 SS 实例用户流推送 | — |
| `gw:instance:{instanceId}` | String | Gateway 实例自注册 | 30s |
| `gw:agent:user:{ak}` | String | AK → userId 映射 | 无 |
| `conn:ak:{ak}` | String | Agent 连接实例绑定 | 120s |
| `gw:auth:nonce:{nonce}` | String | Nonce 防重放 | 300s |
| `auth:identity:{ak}` | String | REMOTE 认证 L2 缓存 | 3600s |
| `gw:register:lock:{ak}` | String | 并发注册分布式锁 | 10s |
| `gw:source:owner:{source}:{instanceId}` | String | Legacy 策略 owner 心跳 | 30s |
| `gw:source:owners:{source}` | Set | Legacy 策略 owner 注册表 | 无 |

---

## 六、数据库

### session_route 表（Skill Server 侧）

```sql
CREATE TABLE session_route (
  id                  BIGINT PRIMARY KEY,          -- Snowflake ID
  ak                  VARCHAR(64) NOT NULL,
  welink_session_id   BIGINT NOT NULL,
  tool_session_id     VARCHAR(128) NULL,
  source_type         VARCHAR(32) NOT NULL,        -- "skill-server", "bot-platform"
  source_instance     VARCHAR(128) NOT NULL,
  user_id             VARCHAR(128) NOT NULL,
  status              VARCHAR(16) DEFAULT 'ACTIVE',
  created_at          DATETIME NOT NULL,
  updated_at          DATETIME,
  UNIQUE INDEX idx_welink_source (welink_session_id, source_type),
  INDEX idx_tool_session (tool_session_id),
  INDEX idx_ak_status (ak, status),
  INDEX idx_source_instance (source_instance)
);
```

**用途：** 持久化 session 级路由信息，为 1-AK:N-Source 场景预留。

---

## 七、配置参数

### Gateway 侧

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:gateway-local}}

  instance-registry:
    ttl-seconds: 30           # Redis 自注册 TTL
    heartbeat-interval-seconds: 10

  skill-relay:
    owner-heartbeat-interval-seconds: 10   # Legacy Owner 心跳
    owner-ttl-seconds: 30
    session-route-ttl-seconds: 1800

skill:
  gateway:
    internal-token: changeme   # Skill Server 内部认证 Token（必须修改）
```

### Skill Server 侧

```yaml
skill:
  gateway:
    ws-url: ws://localhost:8081/ws/skill    # 种子 Gateway 地址
    internal-token: changeme
    reconnect-initial-delay-ms: 1000
    reconnect-max-delay-ms: 30000
    discovery-interval-ms: 10000            # 实例发现间隔
```
