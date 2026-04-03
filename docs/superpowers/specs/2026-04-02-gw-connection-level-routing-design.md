# GW 连接级路由设计

> 修复 SS→GW 连接池扩容后路由失效问题：将路由粒度从 instanceId 级别提升到 connection 级别。

## 问题背景

### 现状

- SS 每个 Pod 通过 ALB 建立 N 条 WebSocket 连接到 GW（默认 3，计划改 8）
- GW 侧按 `ssInstanceId` 存储和路由，粒度为实例级别

### 三层问题

| 层面 | 问题 | 影响 |
|------|------|------|
| GW 本地 `sourceTypeSessions` | `Map<ssInstanceId, WebSocketSession>` 用 `put` 覆盖 | 同一 SS 的多条连接只有最后一条有效，其余变僵尸 |
| GW 哈希环 | `addNode(instanceId, session)` 替换旧值 | 每个 SS Pod 永远只占 1 个哈希环节点 |
| Redis `gw:source-conn` HASH | field = `gwInstanceId`，同 GW 只有一条 | 连接数对路由系统不可见 |

### 根因

路由系统的粒度是 instanceId 级别，不是 connection 级别。增加连接数既不提升路由可靠性，也不提升吞吐。

## 设计方案：连接级哈希环节点

### 核心思路

将哈希环的 nodeKey 从 `ssInstanceId` 改为 `ssInstanceId#sessionId`，让每条连接成为独立的路由目标。SS 侧零改动，保留 ALB。

### 数据结构变更

#### GW 本地存储

```java
// Before: sourceType → { ssInstanceId → WebSocketSession }
Map<String, Map<String, WebSocketSession>> sourceTypeSessions;

// After: sourceType → { ssInstanceId → { sessionId → WebSocketSession } }
Map<String, Map<String, Map<String, WebSocketSession>>> sourceTypeSessions;
```

三层结构：按 sourceType → ssInstanceId → sessionId 索引。

#### 哈希环节点

```
Before: addNode("ss-pod-0", session)          — 一个 SS Pod = 一个节点
After:  addNode("ss-pod-0#ws-sess-abc", session)  — 一条连接 = 一个节点
```

每个 SS Pod 如果有 N 条连接落到此 GW，哈希环上就有 N 个物理节点（N × 150 虚拟节点）。

#### Redis HASH

Key 不变：`gw:source-conn:{sourceType}:{ssInstanceId}`

```
# Before: field = gwInstanceId，同一 GW 只有一条
gw-pod-0 → 1743580800

# After: field = gwInstanceId#sessionId，每条连接各占一个 field
gw-pod-0#sess-a1 → 1743580800
gw-pod-0#sess-a2 → 1743580800
gw-pod-1#sess-b1 → 1743580800
gw-pod-1#sess-b2 → 1743580800
```

## 注册与注销流程

### 注册（registerSourceSession）

```
SS 连接到达 GW
  → session attributes 中提取 sourceType, ssInstanceId
  → sessionId = session.getId()
  → connectionKey = ssInstanceId + "#" + sessionId

  → sourceTypeSessions[sourceType][ssInstanceId][sessionId] = session
  → hashRings[sourceType].addNode(connectionKey, session)
  → redisMessageBroker.registerSourceConnection(sourceType, ssInstanceId, gwInstanceId, sessionId)
```

`put` 不再覆盖，因为 key 是 sessionId 级别，天然唯一。

### 注销（removeSourceSession）

```
连接断开
  → 提取 sourceType, ssInstanceId, sessionId
  → connectionKey = ssInstanceId + "#" + sessionId

  → sourceTypeSessions[sourceType][ssInstanceId].remove(sessionId)
  → 如果该 ssInstanceId 下的 Map 为空，移除 ssInstanceId 这一层
  → 如果该 sourceType 下的 Map 为空，移除 sourceType 这一层
  → hashRings[sourceType].removeNode(connectionKey)
  → redisMessageBroker.removeSourceConnection(sourceType, ssInstanceId, gwInstanceId, sessionId)
```

断开一条连接只移除那一条，不影响同 SS Pod 的其他连接。

### RedisMessageBroker 接口变更

```java
// Before
registerSourceConnection(sourceType, ssInstanceId, gwInstanceId)
removeSourceConnection(sourceType, ssInstanceId, gwInstanceId)
refreshSourceConnectionHeartbeat(sourceType, ssInstanceId, gwInstanceId)

// After — 新增 sessionId 参数
registerSourceConnection(sourceType, ssInstanceId, gwInstanceId, sessionId)
removeSourceConnection(sourceType, ssInstanceId, gwInstanceId, sessionId)
refreshSourceConnectionHeartbeat(sourceType, ssInstanceId, gwInstanceId, sessionId)
```

HASH field 从 `gwInstanceId` 变成 `gwInstanceId#sessionId`。

## 心跳刷新与 Stale 清理

### 心跳刷新（refreshSourceConnectionHeartbeats）

```
@Scheduled(fixedDelay = 10_000)
refreshSourceConnectionHeartbeats():
  for sourceType in sourceTypeSessions:
    for ssInstanceId in sourceTypeSessions[sourceType]:
      for sessionId, session in sourceTypeSessions[sourceType][ssInstanceId]:
        if session.isOpen():
          redisMessageBroker.refreshSourceConnectionHeartbeat(
            sourceType, ssInstanceId, gwInstanceId, sessionId)
        else:
          // 惰性清理：从本地数据结构和哈希环移除
          removeStaleSession(sourceType, ssInstanceId, sessionId)
```

发现 session 已关闭时，主动清理本地 + Redis，避免僵尸连接累积。

### Stale 连接清理（cleanupStaleSourceConnections）

GW 启动时清理自身遗留条目：

```
cleanupStaleSourceConnections(gwInstanceId):
  scan all keys matching "gw:source-conn:*"
  for each key:
    for each field in HGETALL:
      if field == gwInstanceId or field.startsWith(gwInstanceId + "#"):
        HDEL key field
```

兼容旧格式（`field == gwInstanceId`），确保滚动升级时能清理旧版数据。

### getSourceConnections 惰性清理

超过 30s 未心跳的条目删除，返回 `Map<gwInstanceId#sessionId, timestamp>`。逻辑不变，仅 field 格式变化。

## L2 路由适配

### 路由查找流程

L1 直接用本地哈希环（已是连接级节点），L3 是广播，均不需要改路由查找逻辑。

L2 变化：

```
L2 路由:
  1. Redis 查 gw:route:{toolSessionId} → sourceType:ssInstanceId（不变）
  2. Redis 查 gw:source-conn:{sourceType}:{ssInstanceId}
     → Map<gwInstanceId#sessionId, timestamp>
  3. 提取去重的 gwInstanceId 集合
  4. 如果包含本 GW:
     → 本地哈希环 getNode(routingKey) 投递
  5. 如果不包含本 GW:
     → 选一个远程 gwInstanceId，通过 pub/sub gw:relay:{gwInstanceId} 中继
     → 接收端 GW 用自己的本地哈希环选连接投递
```

### 辅助方法

```java
Set<String> extractUniqueGwInstances(Map<String, Long> sourceConnections) {
    return sourceConnections.keySet().stream()
        .map(field -> field.contains("#")
            ? field.substring(0, field.indexOf("#"))
            : field)
        .collect(Collectors.toSet());
}
```

兼容旧格式（不含 `#` 的纯 `gwInstanceId`），支持滚动升级。

### 滚动升级兼容性

升级过程中新旧 GW Pod 共存，旧 GW 无法正确解析新格式 field。

**策略**：新 GW 双写 —— 同时写一条 `gwInstanceId`（无 `#`）的兜底 field，等全量升级完成后再移除双写逻辑。

| 阶段 | 操作 |
|------|------|
| 上线时 | 新 GW 双写：`gwInstanceId#sessionId` + `gwInstanceId`（兜底） |
| 全量后 | 移除双写逻辑，只写 `gwInstanceId#sessionId` |
| 清理 | 旧格式 field 由 30s 惰性清理自动淘汰 |

## 改动范围

### 需要改动的文件

| 文件 | 改动内容 | 改动量 |
|------|---------|--------|
| `SkillRelayService.java` | `sourceTypeSessions` 类型改三层 Map；注册/注销改 sessionId 级操作；哈希环 nodeKey 加 `#sessionId`；心跳遍历改三层；stale 清理改前缀匹配 | 中 |
| `RedisMessageBroker.java` | 三个方法新增 `sessionId` 参数；HASH field 改为 `gwInstanceId#sessionId`；双写兜底 field | 小 |
| `SkillRelayService.java` (L2 路由) | 新增 `extractUniqueGwInstances` 辅助方法；L2 查找后提取去重 gwInstanceId | 小 |

### 不需要改动的文件

| 文件 | 原因 |
|------|------|
| `ConsistentHashRing.java` | 泛型实现，nodeKey 是 String，不关心格式 |
| `SkillWebSocketHandler.java` | 只负责握手和调用 `registerSourceSession` |
| `GatewayWSClient.java` (SS 侧) | SS 零改动 |
| `gw:route:{toolSessionId}` | 路由 key 仍然是 instanceId 级别，不变 |

### 不引入的东西

- 不改 SS 端任何代码
- 不改网络拓扑（保留 ALB）
- 不改 `gw:route:{toolSessionId}` 的路由粒度
- 不新增配置项
