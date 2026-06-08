# GW-SS AsyncSender 与连接观测修复实施计划

## Session Update - Target-GW L2 Stream Decision

本轮会话最终选择保留 `gw:l2:source:skill-server:{targetGw}` target-GW mailbox stream。原因是短期目标要保持路由简单清楚：当前 GW 本地有 SS link 就只走本地；没有本地 SS link 时，只选择一台“当前 Redis source-conn 表明有 SS link 的 GW”，然后只写入这台 GW 的 mailbox。相比 fixed shard + lease owner，target-GW stream 不需要额外 shard 订阅/抢租机制，也不会出现“某个 shard 暂时没有 GW owner 消费”的空窗。

### Revised Architecture

- L1 本地优先不变：当前 GW 有可用 SS link 时，只走本地 link，不进入 L2。
- L2 保留单目标 mailbox：`gw:l2:source:{sourceType}:{targetGwInstanceId}`。
- L2 入队前必须从 Redis source connection registry 里选出一个当前有 `skill-server` link 的 `targetGwInstanceId`，且只写入一个 target GW stream。
- 目标 GW 只消费自己的 mailbox：`readSourceL2Work(sourceType, gatewayInstanceId, gatewayInstanceId, ...)`。
- 同一个 `messageId` 的稳定性仍由 `routingKey` 的 target-GW 选择算法保证，routingKey 优先级保持 `messageId > traceId > toolSessionId > payload.toolSessionId > welinkSessionId > ak`。
- 滚动升级隐患通过 bounded lifecycle 治理，而不是改成 shard：mailbox stream 有 TTL；GW 启动/定时清理自己的 stale source-conn；后续只对已无活跃 source connection 的旧 mailbox 做观测/缩短保留，不直接把未确认消息静默删除。
- 当前 ACK 边界仍是“目标 GW 已放入本地 SS sender 队列”，不是 SS 业务 ACK；日志中必须继续区分 queued / enqueue / error，不能把本地入队说成端到端 delivered。

### Revised Files

- `RedisMessageBroker.java`: 保留 target-GW mailbox API；增加/保留 mailbox TTL、pending 数量、source link 诊断与旧 mailbox 观测能力。
- `SkillRelayService.java`: L2 producer 只选择一个当前有 SS link 的 target GW；consumer 只读本 GW mailbox；失败重投和 ACK 均使用 targetGwId。
- `application.yml`: 保留 `gateway.l2-source-stream.*` 配置，避免引入 shard/lease 配置。
- `RedisMessageBrokerTest` / `SkillRelayServiceV2Test`: 保持 target-GW mailbox 断言，补充升级/无效 mailbox 生命周期相关覆盖。
- `.trellis/spec/ai-gateway/backend/database-guidelines.md`: 后续通过 `trellis-update-spec` 同步 target-GW mailbox 的生命周期治理说明。

### Acceptance

- 每条 L2 消息只写入一个 target-GW stream。
- Producer 不会写入没有活跃 SS source connection 的 target GW。
- Consumer 只消费自己的 target-GW mailbox，不存在多个 GW 抢同一个 target stream。
- target-GW mailbox 不会无限增长：有 TTL、max-len 和可观测清理策略。
- 不引入 fixed shard、shard lease、或多 GW 同时消费同一 shard 的复杂度。

## Goal

修复 SS+GW 链路中 `AsyncSessionSender` 生命周期、日志分级、pending 可观测性和 SS-GW websocket 连接状态不可查的问题。短期目标是让 GW 侧投递路径单一、异常可见、连接状态可诊断、sender 线程资源可配置；不在本计划内扩展 SS 侧端到端业务 ACK。

## Architecture

GW 作为 SS 与 agent/cloud 之间的投递路由和连接状态所有者，必须保证每条消息只进入一种投递路径：本地可投递则只走本地；本地不可投递则只选择一个远端 GW mailbox；不能本地和远端同时尝试。`AsyncSessionSender` 是本地 websocket session 的串行写入器，负责单 link 内 FIFO 出队和失败上报，不负责跨 GW 重新选路。

## Tech Stack

Spring Boot / Spring WebSocket / RedisTemplate / Java record DTO / JUnit 5 / Mockito / Maven。Sender 设计采用 `AsyncSessionSenderFactory` 统一管理 `linkId -> AsyncSessionSender`，每个 sender 拥有一个独立串行发送线程和有界队列，确保同一条 link 内不会并发调用 `session.sendMessage()`。Redis `PUBLISH` 返回订阅客户端数量，官方文档说明该值是投递到客户端数量，集群场景只统计同节点连接，因此只能作为诊断信号，不能作为业务 ACK。

## Baseline/Authority Refs

- Trellis task: `.trellis/tasks/06-05-ss-gw-asyncsender-session/prd.md`
- Existing merged baseline: PR #92 `fix(gateway): 收敛 SS 回源路由与 sender 生命周期`
- User requirement: warning 降噪、pending 日志、连接状态接口、丢消息点 error、asyncSender 线程池可配置
- Official refs:
  - Redis `PUBLISH`: https://redis.io/docs/latest/commands/publish/

## Compatibility Boundary

保留现有 `/api/gateway/*` 内部接口鉴权模型和 `ApiResponse<T>` 响应包裹。新增诊断接口只读，不改变现有投递协议字段。Redis source-connection registry 需要兼容旧 field 格式 `gwInstanceId` 和新 field 格式 `gwInstanceId#linkId`。新增配置项必须有默认值，旧环境未配置时可正常启动。

## Verification

主要验证命令：

```powershell
cd D:\02_Lab\Projects\sandbox\opencode-CUI\ai-gateway
mvn test
```

补充检查：

```powershell
cd D:\02_Lab\Projects\sandbox\opencode-CUI
git diff --check
```

GitNexus 提交前检查：

```text
gitnexus_detect_changes(scope="all")
```

## Plan Basis

Fact:

- 当前工作流集中在 `ai-gateway`，不改 SS 侧业务协议。
- 现有线上日志中 `Delivered to-source relay` 只能证明本地 enqueue，不等价于 SS 已收到。
- 用户要求把真实可能丢消息的位置改成 `ERROR`，把冗余 `WARN` 降噪。

Assumption:

- 短期可接受“可观测 + 单路径 + 本地 sender 有效性”修复，不要求端到端 ACK。
- 诊断接口使用内部 Bearer 鉴权即可，不新增外部权限模型。

Unknown:

- 线上不同 Redis 部署模式下 `PUBLISH` 返回值可能受 cluster node 影响，因此只能用于日志诊断，不作为投递成功判定。

## Architecture Integrity Lens

- Invariant: 同一消息进入 GW 后只能有一个最终投递 owner；同一 link 内由一个 `AsyncSessionSender` 串行写 websocket。
- Canonical owner / contract: `SkillRelayService` 管 SS 回源路由和 source link 选择；`EventRelayService` 管 GW relay 接收和本地 agent/source 投递；`AsyncSessionSender` 只管本地 session 写入和失败回调；`RedisMessageBroker` 只管 Redis registry / pending / publish 基础设施。
- Responsibility overlap: 不允许 controller 重新实现连接扫描，不允许 sender 重新做路由选择。
- Higher-level simplification: 新增诊断接口应聚合 service/broker 快照，不改变业务投递路径。
- Retirement / falsifier: 如果后续上线 SS 端 ACK，则需要重新评估“enqueue 成功”日志语义，并退役当前仅诊断级的 delivered 表达。
- Verdict: proceed，按现有 owner 切分即可，不需要新建跨服务路由框架。

## Plan Pressure Test

- Owner / contract / retirement: owner 清晰，旧 `WARN`/`Delivered` 语义需要降噪或改名，后续 ACK 可替代部分诊断日志。
- Architecture integrity / higher-level path: 不新增 SS 依赖，不改变 REST/WS 协议，新增只读诊断接口。
- Verification scope: controller/service/ws/unit 全量 Maven 测试足够覆盖短期改动。
- Task executability: 每个任务可单独落地和测试。
- Pressure result: proceed。

## Plan-Time Complexity Check

- Target files: `SkillRelayService`, `EventRelayService`, `RedisMessageBroker`, `AsyncSessionSender`, `AgentController`。
- Existing size / shape signals: service 类较大，继续加大量逻辑会增加 owner 混杂风险。
- Owner fit: 连接状态本地快照放 `SkillRelayService`；Redis 集群快照放 `RedisMessageBroker`；API 聚合放 `AgentController`。
- Add-in-place risk: controller 聚合逻辑可接受，但 DTO 必须独立 record，避免 Map 响应。
- Better file boundary: 新增 `AsyncSessionSenderFactory`，避免 service 各自维护 sender 生命周期。
- Recommendation: edit-in-place for log levels and existing routing failure paths; add owner files for config/factory/DTOs.

## Files

Create:

- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSenderFactory.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/SourceConnectionLinkResponse.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/SourceConnectionOverviewResponse.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/AsyncSessionSenderTest.java`

Modify:

- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayMessageIdentityService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`
- `ai-gateway/src/main/resources/application.yml`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/AgentControllerTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerSourceConnTest.java`

## Tasks

### Task 1 - Log level audit and loss-risk escalation

Files:

- Modify `GatewayMessageIdentityService.java`
- Modify `AgentWebSocketHandler.java`
- Modify `SkillRelayService.java`
- Modify `EventRelayService.java`
- Modify `RedisMessageBroker.java`

Why: 降低无害 `WARN` 噪声，把真正可能丢消息的路径显性化为 `ERROR`。

Repair Track:

- Root cause: 过去日志把“可恢复字段缺失”和“不可恢复投递失败”混在 `WARN`。
- Canonical owner: 各链路 owner 自己判断是否已失去消息。
- Stable repair: 可恢复降 `DEBUG`，不可恢复升 `ERROR`。

Retirement Track:

- Old fallback: `Delivered` 类日志继续保留但必须明确是 enqueue 或 local send，不作为业务 ACK。
- Deletion trigger: 端到端 ACK 上线后，重新命名或删除易误导的 delivered 词汇。

Steps:

1. Write test: 更新现有 service/ws 测试，使 route failed、queue failed 场景仍触发业务失败路径。
2. Verify RED: 运行 `mvn -Dtest=EventRelayServiceTest,SkillRelayServiceTest test`，确认旧实现下失败或日志语义不完整。
3. Minimal code: 将 recovered trace/message 日志降为 `debug`；将 queue full、session closed after drain、publish no subscriber、missing target link、dead-letter 改为 `error`。
4. Verify GREEN: 运行 `mvn -Dtest=EventRelayServiceTest,SkillRelayServiceTest,GatewayMessageIdentityServiceTest,AgentWebSocketHandlerTest test`。
5. Commit: 暂不单独提交，纳入最终观测性提交。

### Task 2 - Pending count logging

Files:

- Modify `RedisMessageBroker.java`
- Modify `AsyncSessionSender.java`
- Modify `SkillRelayService.java`
- Modify `EventRelayService.java`

Why: 线上看到“入队”时必须知道当前堆积量，方便区分瞬时转发和持续积压。

Repair Track:

- Root cause: 原日志只记录动作，不记录 pending 数，无法判断是否接近容量。
- Canonical owner: Redis pending list 长度由 `RedisMessageBroker.enqueuePending` 返回；本地 sender 队列长度由 `AsyncSessionSender.pendingCount()` 返回。
- Stable repair: 入队路径统一记录 `pending`。

Retirement Track:

- Old fallback: 无 pending 数的 enqueue 日志不再新增。
- Deletion trigger: 若后续接入 metrics，可将高频 debug pending 日志降采样或迁移到指标。

Steps:

1. Write test: 在 `RedisMessageBrokerSourceConnTest` 或 pending 相关测试中校验 `enqueuePending` 返回 list size。
2. Verify RED: 运行 `mvn -Dtest=RedisMessageBrokerSourceConnTest test`。
3. Minimal code: `enqueuePending` 返回 `long`；sender enqueue 成功后日志带 `pending`；调用方记录返回值。
4. Verify GREEN: 运行 `mvn -Dtest=RedisMessageBrokerSourceConnTest,PendingQueueTest test`。
5. Commit: 暂不单独提交，纳入最终观测性提交。

### Task 3 - SS-GW websocket connection diagnostic API

Files:

- Create `SourceConnectionLinkResponse.java`
- Create `SourceConnectionOverviewResponse.java`
- Modify `AgentController.java`
- Modify `SkillRelayService.java`
- Modify `RedisMessageBroker.java`
- Modify `AgentControllerTest.java`
- Modify `RedisMessageBrokerSourceConnTest.java`

Why: 运维需要直接查询 `linkId / ssInstanceId / gwInstanceId`，判断 SS 到底连在哪台 GW、哪条 link 是否 open、sender 是否 running、pending 是否堆积。

Impact/Compatibility:

- 新增只读内部接口，不影响现有业务接口。
- 返回 record DTO，遵守 ai-gateway 新接口响应规范。

Steps:

1. Write test: 在 `AgentControllerTest` 增加 `GET /api/gateway/source-connections?sourceType=skill-server` 成功和未授权测试。
2. Verify RED: 运行 `mvn -Dtest=AgentControllerTest test`。
3. Minimal code: controller 聚合 `SkillRelayService.getLocalSourceConnectionSnapshots` 和 `RedisMessageBroker.listSourceConnectionLinks`，返回 `ApiResponse<SourceConnectionOverviewResponse>`。
4. Verify GREEN: 运行 `mvn -Dtest=AgentControllerTest,RedisMessageBrokerSourceConnTest test`。
5. Commit: 暂不单独提交，纳入最终观测性提交。

### Task 4 - AsyncSessionSender per-link factory and bounded queue

Files:

- Create `AsyncSessionSenderFactory.java`
- Modify `AsyncSessionSender.java`
- Modify `SkillRelayService.java`
- Modify `EventRelayService.java`
- Modify `application.yml`
- Create `AsyncSessionSenderTest.java`

Why: 原 sender 由多个 service 各自管理，同一条 link 可能被多套 sender 竞争；改为 factory 统一管理后，从入口保证一个 link 只有一个 sender，并通过 `gateway.async-sender.queue-capacity` 配置有界队列。

Impact/Compatibility:

- 保留兼容构造器，测试和旧调用不被迫改造。
- 新 service 通过 factory 创建 sender，factory 是 sender 生命周期 owner。
- 默认配置保证旧环境不配置也能启动。

Steps:

1. Write test: `AsyncSessionSenderTest` 覆盖正常 drain、queue full reject、失败回调。
2. Verify RED: 运行 `mvn -Dtest=AsyncSessionSenderTest test`。
3. Minimal code: 新增 factory 注入 queue capacity；sender 内部使用一个 daemon thread 串行 drain 单个 link 队列。
4. Verify GREEN: 运行 `mvn -Dtest=AsyncSessionSenderTest,SkillRelayServiceTest,EventRelayServiceTest test`。
5. Commit: 暂不单独提交，纳入最终观测性提交。

### Task 5 - Redis source connection link compatibility

Files:

- Modify `RedisMessageBroker.java`
- Modify `RedisMessageBrokerSourceConnTest.java`

Why: 诊断接口需要返回 link 级数据，但线上可能已有旧格式 registry。

Impact/Compatibility:

- 兼容旧 field `gwInstanceId`。
- 新 field `gwInstanceId#linkId` 存在时优先展示新格式，避免重复。
- stale timestamp 清理仍保持现有 TTL 语义。

Steps:

1. Write test: 覆盖 compound link、legacy duplicate filtering、stale cleanup。
2. Verify RED: 运行 `mvn -Dtest=RedisMessageBrokerSourceConnTest test`。
3. Minimal code: 新增 `SourceConnectionLink` record 和 `listSourceConnectionLinks(sourceType)`。
4. Verify GREEN: 运行 `mvn -Dtest=RedisMessageBrokerSourceConnTest test`。
5. Commit: 暂不单独提交，纳入最终观测性提交。

### Task 6 - Full verification and GitNexus safety check

Files:

- All changed files above

Why: 本次改动触达 GW 核心投递链路，需要 full module test 和 GitNexus 影响面确认。

Steps:

1. Run formatting check:

```powershell
git diff --check
```

2. Run full ai-gateway tests:

```powershell
cd D:\02_Lab\Projects\sandbox\opencode-CUI\ai-gateway
mvn test
```

Expected output:

```text
Tests run: 442, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

3. Run GitNexus:

```text
gitnexus_detect_changes(scope="all")
```

Expected interpretation:

- Affected flows include GW relay/source relay/pending/AsyncSender.
- High or critical risk is acceptable only if caused by expected core routing flow touch points and tests are green.

4. Review `git status -sb` and ensure only intended files plus Aegis plan files are dirty.
5. Commit with Chinese message after user approval or phase 3.4 execution:

```powershell
git add ai-gateway docs/aegis
git commit -m "fix(gateway): 增强 GW-SS sender 观测与连接诊断"
```

## Risks

- `ERROR` 日志增加后，测试和压测中故障路径会更显眼，需要区分预期故障用例和线上真实异常。
- Redis `PUBLISH` subscriber count 在 cluster 中不是业务 ACK，不能据此判断 SS 已收到。
- 诊断接口展示的是快照，连接在返回后可能立即变化。
- 单 link 单 sender 线程会随连接数增长而增长；当前选择它是为了消除同一 link 多 sender 竞争和并发 send 风险，上线后需要结合连接数和 pending 观察线程资源。

## Rollout

1. 先以默认配置发布。
2. 观察 `source-connections` 接口中 `pending` 和 `senderRunning`。
3. 如果 pending 持续堆积，调大 `GATEWAY_ASYNC_SENDER_THREAD_POOL_SIZE` 或 `GATEWAY_ASYNC_SENDER_TASK_QUEUE_CAPACITY`。
4. 如果某个 `ssInstanceId` 出现 stale link，检查 SS 重连和 Redis source-conn 清理日志。

## Rollback

- 删除新增诊断接口和 DTO 不影响业务协议。
- 移除 `AsyncSessionSenderFactory` 后，可恢复 service 自建 sender 模型，但不建议回滚到多 owner sender。
- 配置项均为新增项，不需要清理旧环境变量。
- 若 `ERROR` 噪声过高，可只回滚日志级别，但不应回滚真实 drop/error 的可观测性。

## Retirement

- 后续若 SS 侧补齐业务 ACK，应退役“enqueue/delivered”作为成功语义的日志，改为 delivery state：queued、frame-write-success、remote-ack、business-consumed。
- 后续若引入统一 transport metrics，可把高频 pending debug 日志迁移到 metrics，保留 error 日志。
- 后续若 source connection registry 完全切到 `gwInstanceId#linkId`，可删除 legacy field filtering。

## Self-Review

- Spec coverage: 覆盖 warning 降噪、pending、连接状态接口、丢消息 error、sender 线程池配置。
- Placeholder scan: 无 TBD/TODO/未定义执行步骤。
- Type consistency: 新 API 使用 record DTO 和 `ApiResponse<T>`。
- Compatibility: 不改变既有业务接口；Redis registry 兼容旧格式；配置项有默认值。
- Complexity: 新 owner 文件只承担 config/factory/DTO，避免 service 继续膨胀。
- Verification: 包含 targeted tests、full Maven test、GitNexus detect changes。
- Dual-track: 每个修复任务保留 repair/retirement 视角，避免把临时日志语义固化为长期 ACK。
