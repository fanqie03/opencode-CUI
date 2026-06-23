# 后端开发规范索引

> `skill-server` 后端规范导航页。开发前先读本页，再按需进入细分文档。

---

## 本次校准说明

- 2026-04-22：移除了仅属于 `ai-gateway` 的说明，本目录现在只描述 `skill-server`。
- 2026-04-22：未新增规范文件；`StreamMessageEmitter`、personal-scope cloud protocol、`senderUserAccount` 信封层迁移，已并入现有文档。
- 2026-05-10：`conventions.md` 新增"定时任务调度"（`ApplicationReadyEvent` + `TaskScheduler` 取代 `@Scheduled`）和"测试 mock 不能跨过抽象层"（深 mock 防"生产炸 / 测试全绿"）；`database-guidelines.md` 新增"Lettuce native API 调用"（`PUBSUB NUMSUB` 等无封装命令的 cast 路径 + 关键修正）。来源：`physicalSubscriberCount` Lettuce decode bug 修复。
- 2026-05-10（晚）：`conventions.md` 新增"RedisTemplate.execute 包 connection 成 proxy"段落（cast guard 永远 false 的真根因 + Approach B 走 `connectionFactory.getConnection()` + `RedisConnectionUtils.releaseConnection`）；同步把"测试 mock 不能跨过抽象层"和"Lettuce native API 调用"段的代码示例切到新路径，并把"禁止事项"表格新增一条 "在 `redisTemplate.execute` callback 内做 cast" 的反模式。`database-guidelines.md` 同步替换 `physicalSubscriberCount` 示例代码。来源：本次 cast guard 真根因修复 + 三个 PR (#20/#21/#22) 的事后 retrospective（修了次要 bug 但没动 cast guard）。
- 2026-05-19：`type-safety.md` 把 `InvokeCommand` 示例从老 5 参更新为 10 参 canonical（platformExtParam PR1 + allowed-slash-commands v3 收口）；`directory-structure.md` 同步把 `PendingChatRequest` 示例升到 9 字段（含 `businessSessionDomain` / `businessSessionType` / `allowedSlashCommands`），并标注向后兼容 frozen 语义。`database-guidelines.md` 新增"sys_config personal scope 允许 slash 命令清单"段落（`config_type=allowed_slash_commands`、下划线 key、严格 string[] 校验、5min TTL + 双重门控 + frozen 语义）。来源：任务 05-19-allowed-slash-commands。
- 2026-05-20：`error-handling.md` 新增 "Inbound chat senderUserAccount 必填且不再 fallback"（7 段 code-spec：两入口 × 4 action 强制 blank 校验、`dispatchChatToGateway` 移除 `ownerWelinkId` 兜底、`createSessionAsync` 真实 sender 入队、Pending Redis 兼容、Wrong vs Correct 防回归）；`type-safety.md` 同步把 senderUserAccount 段的 `effectiveSender` 示例从老的 group/direct fallback 切到无兜底版本，"常见错误"新增第 6 条禁止 ownerWelinkId 默认值。来源：任务 05-20-single-chat-sender-fallback-removal。
- 2026-05-20（晚）：`conventions.md` 新增 "外部 fire-and-forget 上报 / 埋码模式"（5 条不变量：独立 executor + `@ConditionalOnProperty` 总开关 + 必填缺失 soft-disable + 每个异步边界顶层 try-catch + 日志禁 secret/栈），并在 "测试 mock 不能跨过抽象层" 下增子节 "Spring AOP 切面 + Mockito mock target bean = 静默失活"（advice 直调 vs 半 mock 容器陷阱）；"禁止事项" 表新增 4 条（复用业务 Executor / 配置缺失 fail-fast / 日志带栈或 secret / `@MockBean` mock 切面 target）。来源：任务 05-20-chat-telemetry-welink-reporter，canonical 实现 `skill-server/src/main/java/com/opencode/cui/skill/telemetry/`。
- 2026-05-23：`conventions.md` 将 Redis pub/sub 自愈从 `PUBSUB NUMSUB` 硬判活更新为 loopback probe（`verifySubscriptionDelivery`），避免 Redis 6 Cluster / 云 Redis 代理下节点局部订阅统计误判；单通道恢复失败后不再整容器 `stop/start`，避免打断 `user-stream:*` 跨实例流式投递。来源：任务 05-23-diagnose-redis-relay-self-check-and-multi-instance-streaming-gaps。
- 2026-06-12：新增 `design-and-refactoring.md`，沉淀 Java 软件设计、重构阈值、业务一致性八荣八耻、兜底边界、Wrong vs Correct 与测试要求。来源：任务 06-12-java。
- 2026-06-22（晚）：`conventions.md` "异步执行模式"新增"新增 Executor 时 @Value 默认值必须同步到 application.yml"规则（yml 不可见会导致运维 grep 不到配置，来源 `asyncTaskExecutor` / `MultiSyncProperties` 两次踩坑修复）。
- 2026-06-22：`design-and-refactoring.md` 新增"Composite/Router 模式 @Primary 标注入口"（接口多实现时 Composite 必须 @Primary，防止 `NoUniqueBeanDefinitionException`；叶子实现不加、List<> 注入不受影响），来源 PR #104 review。新增"事务 + 事件发布原子性"（编排方法调用 @Transactional 子方法 + publishEvent 时外层加 @Transactional，消除 commit→publish 崩溃窗口），来源 PR #106 review。
- 2026-06-17：`type-safety.md` 新增"外部 API 模型类 @JsonProperty vs @JsonNaming"（每字段显式 wire format，禁止类级别 SnakeCaseStrategy）、"外部 API 响应类型化 record 替代 JsonNode"（`RestTemplate.postForEntity(url, entity, TypedRecord.class)` + accessor 访问，禁用 `JsonNode.path()` fallback 链）、"外部 API URL 配置化"（完整 URL 走配置属性，禁止 `joinUrl` 硬编码路径）。来源：任务 06-17-remove-jsonnode-from-im-app-notify-response-use-typed-response-object。
- 本次校准依据的近期代码变更：`9454a8c`（personal-scope cloud protocol + `StreamMessageEmitter`）与 `d10d64a`（`senderUserAccount` 信封层迁移）。

## 技术栈概览

| 项目 | 当前实现 |
|------|----------|
| 框架 | Spring Boot 3.4 + Java 21 |
| 持久化 | MySQL + MyBatis XML Mapper |
| 缓存 / 协调 | Redis（Lettuce、pub/sub、TTL ownership、INCR seq） |
| WebSocket | Spring WebSocket (`TextWebSocketHandler`) |
| 序列化 | Jackson |
| 模型生成 | Lombok + Java `record` |
| 日志 | SLF4J + Log4j2 |
| ID 生成 | `SnowflakeIdGenerator` |
| 构建 | Maven |

## 当前模块快照

- `controller/`：6 个入口控制器，覆盖 miniapp 会话 / 消息 API、IM 入站、external 入站、Agent 查询、系统配置。
- `service/`：39 个顶层服务类，外加 `cloud/` 4 个、`delivery/` 6 个、`scope/` 4 个子包。
- `repository/`：5 个 MyBatis Mapper 接口，对应 5 个 XML Mapper。
- `model/`：23 个持久化实体 / 协议 DTO / 命令 record。
- `ws/`：3 个 WebSocket 相关类，分别处理 miniapp 流、gateway 下游连接、external 连接。
- `config/`：13 个配置类（属性、MVC、Redis、拦截器、异常兜底、Executor）。
- `logging/`：4 个日志基础设施类（`LogTimer` / `MdcHelper` / `MdcConstants` / `SensitiveDataMasker`）。
- `resources/db/migration/`：当前迁移脚本已到 `V10__create_sys_config.sql`。

代码与资源证据：

- Java 根包：`skill-server/src/main/java/com/opencode/cui/skill/`
- 资源目录：`skill-server/src/main/resources/`
- 目录实况详见 [directory-structure.md](directory-structure.md)

---

## 规范文件

### 开发前必读清单

| 任务类型 | 必读文件 |
|---------|----------|
| 所有 skill-server 后端任务 | `directory-structure.md`, `conventions.md`, `design-and-refactoring.md` |
| Controller / 入站协议 / 错误返回 | `error-handling.md` |
| MyBatis / Redis / 事务 / 迁移 | `database-guidelines.md` |
| 日志 / MDC / 外部调用可观测性 | `logging-guidelines.md` |
| 模型 / DTO / 协议字段 / record | `type-safety.md` |

### 文件列表

本目录当前共 **8** 个 Markdown 文件：本页 `index.md` + 下列 7 个专题文档。

| 文件 | 当前覆盖重点 |
|------|--------------|
| [directory-structure.md](directory-structure.md) | 最新包结构、子包职责、资源目录、命名放置规则 |
| [conventions.md](conventions.md) | 构造注入、异步执行器、WebSocket 生命周期、Redis 订阅、`StreamMessageEmitter` 约束 |
| [design-and-refactoring.md](design-and-refactoring.md) | Java 软件设计、重构阈值、业务一致性八荣八耻、兜底边界、Review Checklist |
| [error-handling.md](error-handling.md) | `ApiResponse` / `InboundResult` / `ProtocolException`、WebSocket 与 IM / external 入站错误语义 |
| [database-guidelines.md](database-guidelines.md) | MyBatis 接口 + XML、迁移脚本清单、Redis key / TTL / pub-sub 约定、事务边界 |
| [logging-guidelines.md](logging-guidelines.md) | Log4j2 pattern、MDC key、`[ENTRY]/[EXIT]/[SKIP]/[EXT_CALL]` 约定 |
| [type-safety.md](type-safety.md) | `SkillSession` / `SkillMessage` / `SkillMessagePart` / `StreamMessage` / `InvokeCommand` / `ApiResponse` / `senderUserAccount` |

## 近期重点模式

- `StreamMessageEmitter` 现在是统一出站入口：负责 `sessionId` / `welinkSessionId` / `emittedAt` enrich，并在需要时准备消息上下文。
- personal-scope 事件不再只走 OpenCode；`PersonalScopeStrategy` 会按顶层 `protocol` 字段在 `OpenCodeEventTranslator` 与 `CloudEventTranslator` 之间分派。
- external / IM 入站都要求 `senderUserAccount` 位于信封层；`payload.senderUserAccount` 已被明确废弃并在测试中覆盖。
- 旁路上报 / 埋码（首例：WeLink chat telemetry）走"独立 executor + `@ConditionalOnProperty` 总开关 + 配置缺失 soft-disable + 每个异步边界顶层 try-catch + 日志禁 secret/栈"的五件套；canonical 实现 `skill-server/src/main/java/com/opencode/cui/skill/telemetry/`。同时定型 AOP 切面测试约定：advice 方法直调单元测，禁用 `@MockBean` mock target bean 的"伪集成测"。
