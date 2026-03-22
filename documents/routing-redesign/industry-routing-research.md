# 业界多租户 Agent/IM 网关路由架构调研报告

> 调研日期：2026-03-21
> 目标：为 OpenCode-CUI AI Gateway 的路由架构重设计提供业界参考

---

## 一、开源 IM 网关项目的路由设计

### 1.1 OpenIM

**核心架构：**
- 采用 Go 语言微服务架构，连接层与业务层分离
- 依赖组件：MongoDB、Redis、Kafka、ZooKeeper、MinIO
- WebSocket 代理层（oimws）监听 10003 端口，专门管理客户端 WebSocket 连接

**路由方案：**
- **连接层**：Gateway 只负责管理 WebSocket 连接、认证和心跳，不做业务路由决策
- **消息总线**：使用 Kafka 解耦 Gateway 和后端 RPC 服务，实现异步处理和独立扩容
- **服务发现**：通过 ZooKeeper 注册 Gateway 实例，后端服务查询目标 Gateway 后定向推送

**消息路由到正确 WebSocket 实例的方式：**
1. 用户登录时，Gateway 在 Redis 中写入 `userId → gatewayInstanceId` 映射
2. 推送消息时，RPC 服务查 Redis 得到目标 Gateway，通过 Kafka 投递到该实例
3. Gateway 从 Kafka 消费后，在本地内存查找用户的 WebSocket 连接并推送

**优点：** Kafka 保证消息不丢，可水平扩展
**缺点：** 组件多（ZK + Kafka + Redis + Mongo），运维复杂
**适用场景：** 中大规模 IM（10万～1000万在线）

---

### 1.2 Centrifugo

**核心架构：**
- Go 实现的实时消息服务器，基于 Channel 订阅的 PUB/SUB 模型
- 支持 WebSocket、HTTP-streaming、SSE、gRPC、WebTransport
- 单机可支撑 **100万 WebSocket 连接 + 3000万消息/分钟**

**路由方案：**
- **多节点协调**：使用 Redis PUB/SUB 作为 Broker 实现多节点间消息广播
- **Channel 模型**：客户端订阅 Channel，消息发布到 Channel 后由所有订阅节点接收
- **一致性 Redis 分片**：支持 Redis Cluster / Sentinel / 一致性哈希分片

**消息路由到正确实例的方式：**
- **广播模型**：消息发布到 Redis Channel，所有 Centrifugo 节点都收到，只有持有该客户端连接的节点实际推送
- 不需要维护「用户在哪个节点」的路由表——通过广播 + 本地过滤解决

**快速重连与消息恢复：**
- 支持 offset 机制，客户端断连后重连可从上次 offset 恢复未接收的消息
- 无需 sticky sessions

**优点：** 架构极简，单二进制部署，无需 sticky session
**缺点：** 广播模型在节点多时浪费带宽，不适合点对点精确推送
**适用场景：** 实时通知、协作编辑、直播弹幕等广播场景

---

### 1.3 Matrix / Synapse

**核心架构：**
- Python (Twisted) 实现的去中心化通信协议
- Homeserver 架构，每个服务器独立运行，通过 Federation 协议跨服务器通信
- 支持 Worker 架构实现水平扩展

**路由方案：**
- **房间（Room）为中心**：所有消息属于某个 Room，Room 有明确的成员列表
- **Federation 路由**：跨 Homeserver 的消息通过 HTTP 事务（Transaction）传递
- **事件图（Event DAG）**：每条消息是 DAG 上的一个节点，确保因果一致性

**消息路由到正确实例的方式：**
1. 本地用户：直接通过本 Homeserver 的 WebSocket/SSE 推送
2. 远程用户：Federation Server 将消息以 HTTP 事务方式发送到对方 Homeserver
3. Worker 架构下：通过 Redis PUB/SUB 在主进程和 Worker 间分发事件

**优点：** 去中心化，无单点故障，协议标准化
**缺点：** Federation 延迟高，不适合低延迟场景
**适用场景：** 企业通信、跨组织协作

---

## 二、开源 API Gateway 中的 WebSocket 路由

### 2.1 Apache APISIX

**WebSocket 路由策略：**
- 支持 HTTP Upgrade → WebSocket 的协议升级
- **Sticky Session（会话亲和）**：通过一致性哈希实现，可基于 NGINX 变量、HTTP Header、Cookie 做哈希
- 三种一致性哈希来源：客户端 IP、Cookie、自定义 Header

**有状态连接路由的处理：**
- WebSocket 连接建立后，后续帧必须路由到同一个上游节点
- APISIX 通过 L4/L7 层的连接跟踪确保这一点
- **关键限制**：Sticky Session 必须配合 Session Replication 使用——某节点宕机后，Session 数据需要在其他节点上可用

**适用场景：** 作为前置负载均衡器，确保 WebSocket 连接路由到正确的后端服务实例

### 2.2 Envoy Proxy

**WebSocket 路由策略：**
- 原生支持 WebSocket（通过 HTTP/1.1 Upgrade）
- **Ring Hash 负载均衡**：基于一致性哈希将连接分配到上游集群
- **优雅关闭**：支持 Connection Draining，在 Pod 终止时优雅地迁移 WebSocket 连接

**Slack 的实践（见第四节）：**
- Slack 使用 Envoy 作为 WebSocket 接入层
- Envoy 处理 TLS 终止、负载均衡，将连接转发到 Gateway Server
- 扩缩容时 Envoy 支持零停机（zero-downtime）的连接迁移

**适用场景：** 云原生环境下的 WebSocket 接入和负载均衡

### 2.3 总结：API Gateway 的角色

| 能力 | APISIX | Envoy | Kong |
|------|--------|-------|------|
| WebSocket 支持 | 原生 | 原生 | 原生 |
| Sticky Session | 一致性哈希 | Ring Hash | 哈希/Cookie |
| 优雅关闭 | 基本 | Connection Draining | 基本 |
| 适用定位 | L7 网关 | Service Mesh Sidecar | API 管理 |

**关键结论：** API Gateway 解决的是「连接级」的路由问题（确保 WebSocket 帧到同一后端），而不是「消息级」的路由问题（确保消息到达持有特定用户连接的实例）。消息级路由需要应用层自己实现。

---

## 三、云厂商的实时通信架构

### 3.1 AWS IoT Core

**核心架构：**
- Device Gateway：全托管，自动扩展，支持 10 亿+ 设备同时连接
- Message Broker：高吞吐 PUB/SUB，基于 MQTT 5.0
- Rules Engine：可编程的消息路由规则

**路由方案：**
- **Topic-based 路由**：设备发布到 MQTT Topic，Rules Engine 根据 Topic + SQL 表达式路由到目标（Lambda、DynamoDB、Kinesis、S3 等）
- **设备影子（Device Shadow）**：每个设备有虚拟状态副本，服务端可通过影子间接与设备通信
- **无需知道设备连接在哪个实例**：AWS 内部处理消息到设备连接的路由

**消息可靠性：**
- MQTT QoS 0/1：QoS 1 保证至少一次到达
- 持久会话（Persistent Session）：设备离线期间的消息排队，重连后补发
- Session Expiry：可配置会话过期时间

**优点：** 完全托管，无需关心路由实现细节
**缺点：** 成本高，被 AWS 锁定
**适用场景：** IoT 设备管理，设备数量极大但单设备消息频率低

### 3.2 Azure SignalR Service

**核心架构：**
- 托管的 SignalR 服务，替代传统的 Redis Backplane
- 内部管理连接状态和消息路由
- 无需 Sticky Session

**路由方案：**
- **Hub 模型**：客户端连接到 Hub，服务端通过 Hub 向特定用户/组/全体发送消息
- **多实例路由**：发送给特定连接的消息，如果目标连接在当前 Endpoint 则直接推送，否则广播到所有 Endpoint
- **跨区域部署**：Primary/Secondary Endpoint 机制，同区域 Primary 优先，跨区域 Secondary 降级

**App Server → SignalR Service 的交互：**
```
App Server → SignalR Service Endpoint (Primary) → 客户端
                                                 → (如果不在此 Endpoint) → 广播到其他 Endpoint
```

**优点：** 无需管理 Backplane，自动扩展
**缺点：** 广播开销大，精确路由依赖内部实现
**适用场景：** .NET 生态的实时应用

### 3.3 三家云厂商路由策略对比

| 维度 | AWS IoT Core | Azure SignalR | Google Cloud Pub/Sub |
|------|-------------|---------------|---------------------|
| 路由粒度 | Topic + Rules | Hub + Connection/Group | Topic + Subscription |
| 路由信息存储 | 内部管理 | 内部管理 | Subscription 配置 |
| 多实例分发 | 内部路由 | 广播 + 过滤 | Pull/Push 订阅 |
| 消息持久化 | MQTT 持久会话 | 无（实时推送） | 7 天留存 |
| 扩展模型 | 自动 | 自动 | 自动 |

---

## 四、大规模 IM 系统的公开架构

### 4.1 知乎——千万级长连接网关

**核心架构（最接近我们的场景）：**
- 长连接 Broker + Kafka + 路由配置，各组件功能单一且清晰
- 容器化部署，水平扩展无限制

**路由方案（极具参考价值）：**
1. **七层负载均衡 + 一致性哈希**：
   - Nginx preread 机制解析客户端第一个报文，提取客户端唯一标识
   - 基于该标识做一致性哈希，将连接固定到某个 Broker 实例
   - **好处**：客户端重连时大概率连到同一个 Broker，减少路由表更新

2. **Kafka 作为消息枢纽**：
   - Broker 根据路由配置将消息发布到 Kafka Topic
   - 同时根据订阅配置消费 Kafka 将消息下发给客户端

3. **四种路由模式**：
   - 上报：消息路由到 Kafka Topic 但不消费（数据采集）
   - 即时通讯：消息路由到 Kafka Topic 且被消费（双向通信）
   - 纯下发：直接从 Kafka Topic 消费并下发（推送通知）
   - 预处理：路由到一个 Topic，经过处理后从另一个 Topic 消费

**路由信息存储：**
- Redis 存储 `userId → brokerInstanceId` 的映射（带 TTL）
- 推送服务查 Redis 找到目标 Broker，然后通过 Kafka 投递到该 Broker

**优点：** Kafka 保证可靠性，一致性哈希减少路由抖动
**缺点：** 一致性哈希在扩缩容时仍有部分连接需要重新分配
**适用场景：** 千万级在线的通知推送系统

---

### 4.2 微信 / 企业微信

**核心架构：**
- 长连接层（TCP 直连）+ 短连接层（类 HTTP）
- 参考微软 ActiveSync 协议设计
- Push + ACK 机制确保消息可靠送达

**路由方案：**
- **接入层**：多级负载均衡（DNS → LVS → Nginx → 接入服务器）
- **消息路由**：服务端通过长连接直接推送消息内容，客户端 ACK 确认
- **心跳保活**：智能心跳方案，动态调整心跳间隔以适应 NAT 网关超时（通常 300s）

**消息可靠性：**
- 每条消息有唯一 ID，发送方必须收到 ACK 才认为成功
- 失败后有限次重试
- 客户端本地维护消息 sequence，用于断线重连后的增量同步

**关键设计思想：**
- 连接层完全无状态，只负责维持 TCP 连接和转发
- 消息路由和业务逻辑在后端逻辑服务器完成
- 通过消息序号（seq）实现断线重连后的消息补齐

---

### 4.3 Discord——Gateway 分片架构

**核心架构：**
- 基于 Guild（服务器）的分片路由
- 每个 Bot 连接时指定 `[shard_id, num_shards]`
- 分片公式：`shard_id = (guild_id >> 22) % num_shards`

**路由方案：**
- **Guild 级别的分片**：每个 Guild 的事件只会路由到对应 shard 的 Gateway 连接
- **非 Guild 事件**（如 DM）：只发送到 shard_id = 0
- **灵活分片**：允许多个 Session 使用相同的 shard_id（用于负载均衡），也允许不同 num_shards（用于零停机扩容）

**连接管理：**
- 并发连接限制：由 `max_concurrency` 控制
- 速率限制：120 events / 60s，超过立即断连
- Payload 大小限制：4096 bytes

**扩容策略（零停机）：**
```
当前: 4 个 shard (num_shards=4)
扩容: 启动新 Session (num_shards=8)
      新旧 Session 并行运行
      逐步迁移 Guild → 新 shard
      关闭旧 Session
```

**优点：** 分片设计清晰，零停机扩容
**缺点：** 适合 Guild 场景，不适合 1:1 点对点通信
**适用场景：** 大规模群组/频道通信

---

### 4.4 Slack——Gateway Server + Envoy + Flannel

**核心架构：**
- Gateway Server (GS)：有状态，内存持有用户 WebSocket 连接和 Channel 订阅
- Channel Server (CS)：管理 Channel 消息，发送消息时广播到所有订阅的 GS
- Envoy：前端负载均衡、TLS 终止、百万级并发 WebSocket
- Flannel：地理分布式元数据缓存，维护预热的团队元数据内存缓存

**路由方案：**
1. **下行消息路由**：CS 收到消息后，向所有订阅该 Channel 的 GS 广播
2. **GS 本地过滤**：每个 GS 收到后检查本地是否有订阅该 Channel 的客户端，有则推送
3. **多区域部署**：Flannel 在各区域维护本地副本，监听实时事件更新缓存

**关键设计决策：**
- 采用 **广播 + 本地过滤** 模型（类似 Centrifugo 的方案 C）
- Slack 认为这比维护精确路由表更简单、更可靠
- 代价是带宽，但通过 Channel 粒度的订阅关系大幅减少了无效广播

**优点：** 架构简单健壮，无需维护精确路由表
**缺点：** 大型 Channel 的广播开销大
**适用场景：** 企业协作通信

---

### 4.5 WhatsApp——Erlang Actor 模型

**核心架构：**
- Erlang 实现，每个用户连接一个 Erlang Process（仅 300 bytes 内存）
- 无连接池、无多路复用，一个进程管理一个连接的完整生命周期
- Mnesia 分布式数据库存储路由表

**路由方案：**
- **Mnesia 路由表**：存储 `userId → serverId` 的映射，多节点复制
- **Erlang 集群内路由**：发送者的 Erlang 进程查 Mnesia 找到接收者所在服务器，通过 Erlang 节点间通信直接投递
- **离线消息队列**：如果接收者不在线，消息存入 Mnesia 的离线队列，等待重连后推送

**连接迁移：**
- Erlang 进程崩溃/节点重启时，进程自动重新创建
- 客户端重连后重新在 Mnesia 中注册路由
- 离线期间消息不丢（存在离线队列中）

**优点：** Erlang 的 Actor 模型天然适合每连接一进程，路由表分布式复制
**缺点：** Erlang 生态较小，不适合 Java/Spring 技术栈
**适用场景：** 海量 1:1 通信

---

## 五、Agent/Bot 平台的网关设计

### 5.1 Telegram Bot API

**两种接收消息的方式：**

| 维度 | Long Polling | Webhook |
|------|-------------|---------|
| 原理 | Bot 持续调用 `getUpdates` | Telegram 推送到 Bot 的 URL |
| 并发限制 | 同一 Bot Token 不允许并发 getUpdates（409 Conflict） | 无限制（URL 后面可以是负载均衡集群） |
| 扩展性 | **不可水平扩展** | **天然可扩展**（加 Pod 即可） |
| 消息可靠性 | 通过 offset 机制确保不漏 | 需要返回 200，否则 Telegram 重试 |
| 适用场景 | 开发/调试 | 生产环境 |

**关键设计思想：**
- Webhook 模式天然适合微服务架构——Telegram 不关心有多少服务器在处理，只要 URL 可达
- Bot 逻辑必须**无状态**（或使用 Redis 共享状态），才能真正实现水平扩展

### 5.2 Microsoft Bot Framework

**核心架构：**
- Bot Connector Service：Azure 托管的消息中转服务
- Channel Adapter：每个 Channel（Teams、Slack、Facebook 等）一个 Adapter
- Activity 协议：标准化的消息格式（Activity Schema）

**消息路由流程：**
```
用户 → Channel (Teams/Slack/...) → Bot Connector Service → Bot Endpoint (Webhook)
Bot Endpoint → Bot Connector Service → Channel → 用户
```

**路由方案：**
1. **Ingestion**：从 Channel 接收原生格式消息
2. **Authentication**：验证消息来源和 Bot 授权
3. **Translation**：在 Channel Schema 和 Activity Schema 间转换
4. **Routing**：投递到 Bot 的 Messaging Endpoint
5. **Response Processing**：Bot 响应经过反向翻译后投递到 Channel

**关键设计思想：**
- Bot 本身是被动的 Webhook 接收者，无需维护长连接
- Bot Connector Service 充当统一的路由中转层
- Channel 的多样性通过 Adapter 模式抽象

### 5.3 Slack Bot Event Distribution

**路由方案：**
- **Events API**（推荐）：Slack 向 Bot 的 Request URL 发送 HTTP POST
- **Socket Mode**：通过 WebSocket 连接接收事件（适合防火墙内的 Bot）
- 事件基于 Bot 订阅的 Event Type 进行过滤和分发

---

## 六、核心问题横向对比

### 6.1 消息如何精确路由到特定 WebSocket 连接所在实例？

| 方案 | 使用者 | 机制 | 路由信息存储 |
|------|--------|------|-------------|
| **Redis 查表 + 定向推送** | OpenIM、知乎 | 查 Redis 获取 userId→gatewayId，定向投递到目标 Gateway | Redis KV (TTL) |
| **广播 + 本地过滤** | Centrifugo、Slack、Azure SignalR | 消息广播到所有节点，只有持有连接的节点实际推送 | 不需要路由表 |
| **分片路由** | Discord | 基于 guild_id 哈希确定 shard，消息只发到对应 shard | 哈希算法（无外部存储） |
| **Erlang 集群路由** | WhatsApp | Mnesia 分布式数据库存储路由表，Erlang 节点间直接投递 | Mnesia（分布式内存数据库） |
| **MQ Tag 路由**（你们的新方案） | — | Skill Server 查 Redis 获取 gatewayId，MQ 消息带 tag 定向投递 | Redis KV + MQ Tag |

**推荐：** 对于你们的场景（100万 Agent、点对点精确路由），**Redis 查表 + MQ 定向投递**（即你们的方案 A）是业界最成熟的做法，与 OpenIM 和知乎的方案一致。

### 6.2 多个服务同时向同一个连接发消息时如何处理？

| 方案 | 使用者 | 机制 |
|------|--------|------|
| **会话级路由表** | 你们的新方案 | 每个会话独立路由记录，同一 AK 可有多条路由，互不干扰 |
| **Channel 隔离** | Slack、Centrifugo | 不同 Channel 的消息天然隔离，互不影响 |
| **MQ 分区有序** | OpenIM、知乎 | 以 AK/userId 为 Partition Key，同一用户的消息有序处理 |

**关键：** 你们的新方案（MySQL session_route 表，按 welinkSessionId/toolSessionId 独立记录）完全正确，这解决了当前 `gw:agent:source:{ak}` 覆盖写的根本问题。

### 6.3 连接迁移（实例重启、扩缩容）时的消息不丢失策略

| 策略 | 使用者 | 机制 |
|------|--------|------|
| **MQ 持久化 + 客户端重连** | OpenIM、知乎、你们的新方案 | 消息在 MQ 中持久化，Gateway 重启后客户端重连到新实例，MQ 消息重新消费 |
| **一致性哈希 + 减少迁移** | 知乎 | Nginx preread + 一致性哈希，客户端重连大概率回到同一个实例 |
| **零停机分片迁移** | Discord | 新旧 Session 并行运行，逐步迁移 |
| **离线消息队列** | WhatsApp | 消息存入 Mnesia 离线队列，重连后补发 |
| **Envoy Connection Draining** | Slack | Envoy 在 Pod 终止时优雅地排空连接 |
| **offset 恢复** | Centrifugo | 客户端记录消费 offset，重连后从断点恢复 |

**推荐组合：**
1. MQ 持久化（你们已设计）——保证 Gateway↔SkillServer 间消息不丢
2. 一致性哈希（参考知乎）——减少 Gateway 扩缩容时的连接迁移
3. Envoy Connection Draining——优雅关闭时给客户端重连时间
4. 客户端 seq/offset 机制——Agent 断线重连后通过 seq 号补齐遗漏的消息

### 6.4 路由信息存在哪里？

| 存储 | 使用者 | 内容 | 优缺点 |
|------|--------|------|--------|
| **内存** | Slack GS、Centrifugo | 本地连接→Channel 订阅关系 | 快但不持久，重启丢失 |
| **Redis KV** | OpenIM、知乎、你们 | userId/ak → gatewayInstanceId | 快、有 TTL 自清理、单点风险需 Cluster |
| **MySQL** | 你们的新方案 | 会话级路由表 session_route | 持久可靠，但查询需缓存（Caffeine） |
| **一致性哈希** | Discord | 无外部存储，纯算法 | 零存储开销，但只适合可哈希的场景 |
| **Mnesia** | WhatsApp | 分布式内存数据库 | 快+分布式+持久，但 Erlang 专属 |

---

## 七、对你们新方案的评价与建议

### 7.1 方案评价

你们的新方案（`routing-redesign-proposal.md`）与业界最佳实践高度吻合：

| 设计决策 | 你们的方案 | 业界对标 | 评价 |
|---------|-----------|---------|------|
| Gateway 无状态化 | 只管 WebSocket 连接 | OpenIM、知乎、微信 | 正确 |
| MQ 解耦 Gateway↔Skill Server | Kafka/RocketMQ | OpenIM (Kafka)、知乎 (Kafka) | 正确 |
| Redis 存 ak→gatewayId | `conn:ak:{ak}` | OpenIM、知乎 | 正确 |
| MySQL 存会话路由表 | session_route | 无直接对标（多数 IM 不需要多 Source） | 创新且合理 |
| MQ Tag 精确路由 | 方案 A | — | 优于广播方案 |

### 7.2 补充建议

基于调研，建议补充以下设计：

**1. 一致性哈希接入（参考知乎）**
```
当前：Agent 随机连接任意 Gateway 实例
建议：在负载均衡层基于 AK 做一致性哈希
效果：
  - Agent 重连大概率回到同一 Gateway → 减少 Redis 路由更新
  - 扩缩容时只迁移 1/N 的连接
```

**2. 客户端消息序号（参考微信、Centrifugo）**
```
当前：Agent 断连后可能丢失 in-flight 消息
建议：Gateway 为每个 AK 维护递增 seq
      Agent 重连时携带 lastSeq
      Gateway 从 MQ 或本地缓冲区补发 lastSeq 之后的消息
```

**3. Connection Draining（参考 Slack/Envoy）**
```
当前方案未提及 Gateway 优雅关闭流程
建议：
  1. Gateway 收到 SIGTERM 后标记为 draining
  2. 停止接受新连接
  3. 向所有已连接 Agent 发送 reconnect 指令
  4. 等待 N 秒让 Agent 迁移到其他实例
  5. 强制关闭剩余连接
  6. 退出
```

**4. 下行消息的 Fallback 策略**
```
MQ Tag 路由可能遇到：
  - Tag 指向的 Gateway 已下线但 Redis 尚未更新
建议：
  - 设置消息 TTL + 死信队列
  - Gateway 消费失败（本地无连接）时 NACK
  - MQ 重新投递到其他实例或进入死信
  - 监控死信队列告警
```

---

## 八、总结：方案选型建议

对于你们的场景（100万 Agent、多 Source 并发、双 AZ 部署、Java/Spring + Kafka + Redis），推荐的架构组合：

| 层次 | 推荐方案 | 参考来源 |
|------|---------|---------|
| 接入层 | Envoy/Nginx + 一致性哈希 | 知乎、Slack |
| 连接管理 | 无状态 Gateway（只管 WebSocket） | OpenIM、微信 |
| 消息传输 | Kafka（上行 + 下行 两个 Topic） | OpenIM、知乎 |
| 下行精确路由 | Redis 查 ak→gatewayId + MQ Tag | 你们方案 A + OpenIM |
| 会话路由 | MySQL session_route + Caffeine 缓存 | 你们原创设计 |
| 连接注册 | Redis KV（TTL 120s） | 业界通用 |
| 消息可靠性 | MQ 持久化 + 客户端 seq 补齐 | 微信、Centrifugo |
| 优雅关闭 | Connection Draining + Reconnect 指令 | Slack/Envoy |

你们现有的 `routing-redesign-proposal.md` 方案的方向完全正确，与 OpenIM 和知乎的架构高度一致。建议在此基础上补充一致性哈希接入、消息序号机制、优雅关闭流程、死信队列处理四个方面的设计。

---

## Sources

- [OpenIM Official](https://www.openim.io/en)
- [OpenIM Server - GitHub](https://github.com/openimsdk/open-im-server)
- [Centrifugo Official](https://centrifugal.dev/)
- [Centrifugo - GitHub](https://github.com/centrifugal/centrifugo)
- [Matrix Synapse Architecture Overview - DeepWiki](https://deepwiki.com/matrix-org/synapse/1.2-architecture-overview)
- [Matrix Federation Spec](https://spec.matrix.org/legacy/server_server/r0.1.4.html)
- [Discord Gateway Documentation](https://docs.discord.com/developers/events/gateway)
- [Discord.js Sharding Guide](https://discordjs.guide/sharding/)
- [How Discord Scaled to 15 Million Users - GeeksforGeeks](https://www.geeksforgeeks.org/system-design/how-discord-scaled-to-15-million-users-on-one-server/)
- [Slack Real-time Messaging Engineering](https://slack.engineering/real-time-messaging/)
- [Slack Migrating Millions of WebSockets to Envoy](https://slack.engineering/migrating-millions-of-concurrent-websockets-to-envoy/)
- [Slack Architecture - ByteByteGo](https://blog.bytebytego.com/p/how-slack-supports-billions-of-daily)
- [WhatsApp Architecture Deep Dive](https://getstream.io/blog/whatsapp-works/)
- [WhatsApp Erlang Architecture](https://scalewithchintan.com/blog/whatsapp-erlang-architecture-2-billion-users/)
- [How WhatsApp Handles 40 Billion Messages Per Day - ByteByteGo](https://blog.bytebytego.com/p/how-whatsapp-handles-40-billion-messages)
- [知乎千万级高性能长连接网关揭秘](https://zhuanlan.zhihu.com/p/66807833)
- [知乎长连接网关架构 - 腾讯云](https://cloud.tencent.com/developer/article/1444308)
- [微信通讯协议深度剖析](https://www.biaodianfu.com/wechat-protocol.html)
- [微信智能心跳方案 - 腾讯云](https://cloud.tencent.com/developer/article/1030660)
- [AWS IoT Core](https://aws.amazon.com/iot-core/)
- [AWS IoT Core Features](https://aws.amazon.com/iot-core/features/)
- [Azure SignalR Multi-Instance Scaling](https://learn.microsoft.com/en-us/azure/azure-signalr/signalr-howto-scale-multi-instances)
- [SignalR Scaleout Introduction](https://learn.microsoft.com/en-us/aspnet/signalr/overview/performance/scaleout-in-signalr)
- [Apache APISIX Sticky Sessions](https://api7.ai/blog/sticky-sessions-theory)
- [API Gateway WebSocket Support - API7.ai](https://api7.ai/learning-center/api-gateway-guide/api-gateway-dynamic-routing-management)
- [Envoy Gateway Graceful Shutdown](https://gateway.envoyproxy.io/docs/tasks/operations/graceful-shutdown/)
- [WebSocket Graceful Shutdown Best Practices](https://oneuptime.com/blog/post/2026-02-02-websocket-graceful-shutdown/view)
- [Telegram Bot Long Polling vs Webhook](https://gramio.dev/updates/webhook)
- [Microsoft Bot Framework Channel Connectivity - DeepWiki](https://deepwiki.com/microsoft/BotFramework-Services/2.2-channel-connectivity)
- [Azure Bot Service Architecture](https://moimhossain.com/2025/05/22/azure-bot-service-microsoft-teams-architecture-and-message-flow/)
- [WebSocket Architecture Best Practices - Ably](https://ably.com/topic/websocket-architecture-best-practices)
