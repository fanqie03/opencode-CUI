# Target Routing Model v3

## Design Principles

Phase 1 的目标不是给历史实现找更多兜底，而是把 Gateway/Source 路由重新整理成一套更简单、可解释、能匹配现网拓扑的正式模型。目标模型遵循以下原则：

- 新版 `Source` 只连接统一的 `ALB` WebSocket 入口，不再把跨集群实例直连当作正式前提。
- `GW 共享 Redis` 是唯一的 GW 内部共享路由真源，本地内存只做缓存和加速。
- 业务路由与传输路由分离：会话归属决定“应该回到哪个 `sourceInstance`”，连接归属决定“应该落到哪条 WebSocket 连接”。
- 显式绑定优先于被动学习：新版协议通过控制消息明确声明路由关系，而不是继续依赖 `route_confirm` 的历史学习。
- legacy 兼容路径保留，但不再把兼容策略误当成正式主路径。

## Current vs Target

| 维度 | Current | Target |
| --- | --- | --- |
| Source 接入 | 默认保留 `mesh/full-connection` 心智，`GatewayWSClient` 假定能发现多个 `GW` 实例 | 新版 `Source` 仅连接统一 `ALB` 入口，内部连接是否落到哪个 `GW` 由负载均衡决定 |
| GW 路由学习 | 大量依赖被动学习、`route_confirm` 和 fallback 广播 | 路由由协议显式绑定，学习式逻辑只保留在 legacy 路径 |
| 路由真源 | 本地内存、`GW 共享 Redis`、`SS Redis`、repository/DB 反查并存 | `GW 共享 Redis` 是唯一共享真源，本地状态只是缓存 |
| owner 粒度 | 更接近“实例级 owner”或“会话级 owner”混合语义 | 业务路由认 `sourceInstance`，传输路由认连接级 owner |
| 吞吐扩展 | 单条连接和广播兜底混合，难以明确容量边界 | 通过连接池提供并发承载，通过路由索引保证回路由正确性 |

## GW Routing Source Of Truth

目标模型明确规定：`GW 共享 Redis` 是唯一的 GW 内部共享路由真源。任何 GW 只要拿到一条上行或下行消息，都应该先查这一份共享索引，而不是依赖别的组件去“猜”应该投递到哪里。

这条原则直接排除了两种做法：

- 不再把 `SS Redis` 当作 GW 内部回路由真源；它可以继续服务 legacy/现有 `skill-server` 逻辑，但不负责定义 GW 侧正式模型。
- 不再把 repository/DB 反查当作热路径的正式组成部分；历史数据可以作为兼容信息源，但不是主设计的一部分。

在实现上，`GW` 可以继续保留本地缓存来降低 Redis 查询成本，但缓存失效后的补救动作应该是“回到 Redis 重新查询”，而不是“继续广播试探并期待学习成功”。

## Routing Index Model

目标模型把共享索引拆成四类，分别回答不同问题：

- `toolSessionId -> sourceInstance`
- `welinkSessionId -> sourceInstance`
- `sourceInstance -> activeConnectionSet`
- `connectionId -> owningGw`

这四个索引有明确分工：

- 前两个索引属于业务回路由层，回答“当前会话应该回到哪个 `sourceInstance`”。
- `sourceInstance -> activeConnectionSet` 回答“这个实例当前有哪些可用连接”。
- `connectionId -> owningGw` 回答“某条具体连接当前挂在哪个 `GW` 实例上”。

这样的设计比 `sourceInstance -> owningGw` 的单层抽象更准确，因为新版 `Source` 会使用小连接池承载吞吐，连接池里的每一条连接都可能落到不同的 `GW`。如果仍然只保留实例级单 owner，就无法同时满足多连接并发和精确回路由。

## Connection-Level Ownership

目标模型把 owner 的语义明确收缩到“连接级”，而不是“实例级单 owner”。

这意味着：

- `sourceInstance` 是业务层的归属单位，用来表达“哪个 Source 实例拥有某个会话”。
- `connectionId` 是传输层的归属单位，用来表达“具体通过哪条长连接回发消息”。
- `owningGw` 表示这条连接当前挂在哪个 `GW` 上；如果当前处理消息的 `GW` 不是 `owningGw`，就需要做一次内部 relay。

这样分层后，目标路径就变成：

1. 先根据 `toolSessionId` 或 `welinkSessionId` 找到 `sourceInstance`。
2. 再从 `sourceInstance -> activeConnectionSet` 找出可用连接。
3. 通过连接选择策略选中一条 `connectionId`。
4. 再由 `connectionId -> owningGw` 判断是否需要跨 GW relay。

这条路径既保留了会话级稳定归属，又为连接池并发和负载分散留出了空间。

## Source Protocol v3

`Source Protocol v3` 的目标是把“路由关系”从隐式副作用升级成显式协议语义。

握手阶段继续沿用 `Sec-WebSocket-Protocol: auth.{Base64(JSON)}` 这一封装方式，但 JSON 字段需要定稿为：

- `source`
- `instanceId`
- `protocolVersion`

其中：

- `source` 表示 Source 类型，例如 `skill-server` 或其他三方 Source。
- `instanceId` 在 v3 语义上等价于 `sourceInstanceId`，继续沿用现有字段名是为了降低接入成本，但文档中以后统一按 `sourceInstanceId` 理解。
- `protocolVersion=3` 代表连接走新版显式绑定协议。

控制消息也需要显式化：

- `route_bind`：由新版 Source 主动声明某个 `welinkSessionId` 和可选 `toolSessionId` 归属于当前 `sourceInstanceId`
- `route_unbind`：当会话结束、连接迁移或 Source 主动释放绑定时显式撤销
- `protocol_error`：当 v3 消息缺少必要字段、违反约定或无法建立合法绑定时，由 GW 返回明确协议错误

`route_confirm` 和 `route_reject` 不再属于 v3 正式主路径。它们只保留给 legacy 路径，用于兼容现有被动学习和历史反查行为。

## Route Lookup Priority

目标模型要求 GW 在回路由时使用固定且可解释的优先级：

`toolSessionId > welinkSessionId > legacy source fallback`

这条规则的含义是：

- 如果消息携带 `toolSessionId`，优先按 `toolSessionId -> sourceInstance` 查找，因为这是粒度最细、稳定性最高的业务路由键。
- 如果没有 `toolSessionId`，则回退到 `welinkSessionId -> sourceInstance`。
- 只有在 legacy 消息没有显式 v3 路由键，或者兼容场景下索引缺失时，才允许使用 `legacy source fallback`。

固定优先级的意义在于，团队不再需要依赖“这次命中了哪个历史分支”来解释消息为何落到某个 Source。

## Connection Pool And Ordering

新版 `Source` 连接模型不再把多连接只当成冗余，而是把连接池同时当成吞吐扩展层。

正式约束如下：

- 每个新版 `Source` 实例接入统一 `ALB` 时，`默认 4 条长连接`
- 当压测或高并发回复场景需要更高吞吐时，连接池 `可配置到 8 条`
- 连接池同时承担冗余和吞吐，不再要求所有会话共用单条连接
- 同一个会话必须稳定落在同一条连接上，以保证事件顺序和用户感知一致

为满足“同会话有序、不同会话可并行”的目标，连接选择策略采用 `一致性哈希`：

- 先用 `toolSessionId` 做哈希；如果没有 `toolSessionId`，再退到 `welinkSessionId`
- 哈希结果映射到 `sourceInstance -> activeConnectionSet` 中的某一条连接
- 连接断开时，只重建失效连接，并重新注册这条连接的 `connectionId -> owningGw`

这样可以避免把所有回复都堵在单条 WebSocket 上，同时保持同一个业务会话的有序回包。

## Message Flow Summary

目标模型下，消息流可以收敛成一套更清晰的双向路径。

`Source -> GW -> Agent`

1. 新版 `Source` 仅通过统一 `ALB` 建立连接池，不再发现并直连所有 `GW` 实例。
2. `Source` 发送 `invoke` 或其他下行动作时，接入的 `GW` 先查本地 Agent 连接，再查 `GW 共享 Redis` 中的共享索引。
3. 若目标 Agent 在其他 `GW` 上，则通过 GW 内部 relay 把消息转发到目标 `GW`，再投递给 Agent。

`Agent -> GW -> Source`

1. `Agent` 事件先到当前接入的 `GW`。
2. `GW` 按 `toolSessionId > welinkSessionId > legacy source fallback` 查询共享路由索引，得到目标 `sourceInstance`。
3. 再根据 `sourceInstance -> activeConnectionSet` 和 `一致性哈希` 选出一条连接。
4. 如果当前 `GW` 不是这条连接的 `owningGw`，就做一次内部 relay；否则直接把消息下发到对应连接。

这套总结把现有“哈希、广播、历史学习、owner 判断”并存的链路，收敛成“显式绑定 + 共享真源 + 连接级 owner”的单一目标模型。
