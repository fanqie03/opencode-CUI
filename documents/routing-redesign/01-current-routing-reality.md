# Current Routing Reality

## Production Topology Constraints

当前生产环境不是单集群单入口，而是 `SS` 和 `GW` 双集群部署，每个集群都有 8 个实例。集群内部的实例之间可以通过服务 `IP:port` 建立长连接，但跨集群无法依赖同样的方法直连，因此“实例之间互相发现后建立全连通长连接”的假设，只在部分局部网络范围内成立。

在真正对外的访问路径上，Pod 外面先有 `server`，再包一层 `ingress`，最终两个集群的入口地址和端口被挂到统一的 `ALB` 上，对外暴露成一个域名。因此从外部视角看，`Source` 端最稳定、最长期可依赖的入口只有统一的 `ALB`，而不是某个具体 `GW` 实例的内网地址。

这意味着当前代码里同时存在的“实例级直连”和“统一入口接入”两套心智模型，会天然增加团队理解成本。尤其当 `GatewayWSClient` 继续保留实例发现和连接池逻辑，而部署现实又要求统一从 `ALB` 进入时，文档描述和实际运行路径很容易偏离。

## Source -> GW -> Agent

当前 `Source -> GW -> Agent` 路径的入口主要在 `skill-server` 侧的 `GatewayRelayService` 与 `GatewayWSClient`。

第一步，`GatewayRelayService` 在 `skill-server` 收到下行调用后，会优先尝试 `sendViaHash(command.ak(), messageText)`。这条路径隐含了一个假设：`GatewayWSClient` 已经为当前 `skill-server` 实例持有一组到多个 `GW` 实例的长连接，并且可以基于 `ak` 做一致性哈希挑选一个目标 `GW`。

第二步，如果一致性哈希路径失败，`GatewayRelayService` 会回退到 `conn:ak`。具体做法是通过 `redisMessageBroker.getConnAk(ak)` 查询 Redis 中记录的 `Agent -> GW` 连接位置，再尝试把消息发给那个具体的 `GW` 实例。这说明当前路径已经部分引入了“连接位置注册表”的概念，但它和哈希路由是并存而不是替代关系。

第三步，如果 `conn:ak` 查不到，或者查到了但目标连接已经不可用，`GatewayRelayService` 还会继续广播到所有 `GW` 连接。这条广播兜底保证了在局部状态缺失时消息仍有机会被目标 `GW` 收到，但也把系统从“定向路由”拉回了“碰运气广播”。

`GatewayWSClient` 的类注释和实现进一步说明了这一点。它保留了 `GatewayDiscoveryService`、seed 地址、`gwConnections` 和一致性哈希环；也就是说，当前 `skill-server` 端的主设计仍然带有明显的 `mesh/full-connection` 色彩。对集群内部来说，这种做法能工作；但对跨集群和 `ALB` 统一入口来说，它并不是一个天然对齐的模型。

整体上，当前 `Source -> GW -> Agent` 路径更像“三段式兜底链路”：

1. `GatewayRelayService` 先走 `GatewayWSClient` 的一致性哈希。
2. 哈希失败后，回退到 `conn:ak` 对应的具体 `GW`。
3. 仍然失败时，广播到所有已知 `GW` 连接。

这条链路可以解释当前代码为什么能在多种混合部署条件下继续工作，但也暴露出主路径和兜底路径并存、职责边界模糊的问题。

## Agent -> GW -> Source

当前 `Agent -> GW -> Source` 路径跨越 `ai-gateway` 和 `skill-server` 两侧，而且状态学习方式并不统一。

在 `GW` 侧，`Agent` 与 `GW` 的连接位置会通过 `conn:ak` 记录下来，这让 `Source -> GW -> Agent` 方向有了一个相对明确的入口。但在反向路径上，`SkillRelayService` 面对的是另一个问题：到底该把上行事件送回哪个 `Source` 连接。

`SkillRelayService` 的核心做法是同时维护本地连接池、哈希环和历史学习结果。对于来自 `Agent` 的 `tool_event`、`tool_done`、`tool_error` 等消息，它会先根据 `GatewayMessage` 里的路由键尝试命中本地缓存，再通过 `UpstreamRoutingTable` 解析 `sourceType`，把消息定向发给某个 `Source` 类型连接；如果仍然无法确定目标，就回退到面向同类 `Source` 的广播。

在 `skill-server` 侧，`GatewayMessageRouter` 负责最终判断这条消息是不是应该由当前实例消费。对于有明显会话归属的消息类型，它会调用 `SessionRouteService` 解析 `welinkSessionId` 对应的 owner。如果消息里只有 `toolSessionId`，则还会走历史反查，通过 `sessionService.findByToolSessionId()` 去找出对应的 `welinkSessionId`，再继续判断归属。

这也是当前 `route_confirm` / `route_reject` 仍然存在的重要背景。`GatewayMessageRouter.resolveSessionId(...)` 在能够根据历史记录反推出会话归属时，会向 `GW` 发送 `route_confirm`，帮助 `SkillRelayService` 学会后续回路由；如果反查失败，则发送 `route_reject`。在 `GW` 侧，`route_confirm` 真的会更新 `routingTable`，而 `route_reject` 主要用于记录日志和说明命中失败，没有形成一条对称、稳定的恢复链路。

因此，当前 `Agent -> GW -> Source` 路径不是单一机制，而是几种机制叠加：

1. `SkillRelayService` 基于本地连接和 `UpstreamRoutingTable` 推断上行目标。
2. `GatewayMessageRouter` 基于 `SessionRouteService` 判断当前 `skill-server` 实例是否拥有会话。
3. `route_confirm` / `route_reject` 再把结果回流给 `GW`，让下一次消息更容易命中。

这种设计在历史演进阶段是合理的，因为它允许系统边运行边学习；但随着双集群、统一 `ALB` 和 legacy/new 混跑并存，这种“被动学习”开始越来越难以解释。

## Current Fallback Paths

当前实现中，正向和反向链路都内置了多层 fallback：

- `Source -> GW -> Agent`：一致性哈希失败后退到 `conn:ak`，再退到广播所有 `GW`。
- `Agent -> GW -> Source`：本地路由命中失败后，退到 `UpstreamRoutingTable` 的 `sourceType` 解析，再退到同类 `Source` 广播。
- `skill-server` 会话归属不明确时：先查 `welinkSessionId`，查不到再按 `toolSessionId` 做历史反查，最后通过 `route_reject` 表示学习失败。
- legacy 连接无法通过新模型解释时：继续保留旧的 owner/fallback 逻辑，避免一次性切换造成不可用。

这些 fallback 让系统更抗脏数据和缓存 miss，但也意味着任何一条消息的“真正路径”都可能随上下文而变化。

## Why The Current Model Is Hard To Reason About

当前模型最难解释的地方，不是单个类写得复杂，而是多个类分别保存了一部分“看起来像真相”的状态。

`GatewayWSClient` 暗示 `skill-server` 应该能连上所有 `GW`，`GatewayRelayService` 又把 `conn:ak` 当作一个精确入口，`SkillRelayService` 维护自己的连接池和学习结果，`GatewayMessageRouter` 则依赖 `SessionRouteService` 和 repository/DB 的历史反查来兜底。每一层都能独立解释部分行为，但没有哪一层是所有人都公认的唯一真源。

结果就是，同一个问题会在团队里出现多种答案，例如：

- “下行到底是按哈希路由，还是按 `conn:ak` 路由？”
- “上行到底是靠 `SkillRelayService` 直接找连接，还是靠 `GatewayMessageRouter` 判断 owner？”
- “`route_confirm` 是正式协议的一部分，还是历史兼容副作用？”
- “`toolSessionId` 到底是显式路由键，还是从历史数据里反查出来的补丁？”

只要这些问题还要靠“看这次命中了哪条 fallback”才能回答，路由模型就很难成为团队共享的正式设计。
