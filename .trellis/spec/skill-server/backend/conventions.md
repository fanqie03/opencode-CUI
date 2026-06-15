# 编码约定

> `skill-server` 当前实现中的通用编码规范。只记录已经在代码中稳定存在的模式。

---

## 概览

当前代码库的几个核心约定：

- 统一使用**构造函数注入**，不使用字段注入。
- 异步任务使用**命名 `Executor` + 事务提交后触发**，当前没有使用 `@Async`。
- WebSocket 生命周期集中在 `SkillStreamHandler`，连接、回放、订阅、清理都在同一个 handler 完成。
- Redis pub/sub 统一走 `RedisMessageBroker`，不要直接在业务类里操作 `RedisMessageListenerContainer`。
- miniapp / IM / external 的统一出站，已经收敛到 `StreamMessageEmitter`。

---

## 依赖注入

**必须使用构造函数注入**。`SkillMessageService` 是当前最典型的样式：依赖都声明为 `final`，构造函数中一次性注入。

```java
@Service
public class SkillMessageService {

    private final SkillMessageRepository messageRepository;
    private final SkillMessagePartRepository partRepository;
    private final SkillSessionService sessionService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;
    private final MessageHistoryCacheService messageHistoryCacheService;
    private final Executor messageHistoryRefreshExecutor;

    public SkillMessageService(SkillMessageRepository messageRepository,
            SkillMessagePartRepository partRepository,
            SkillSessionService sessionService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper,
            MessageHistoryCacheService messageHistoryCacheService,
            @Qualifier("messageHistoryRefreshExecutor") Executor messageHistoryRefreshExecutor) {
        ...
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:38-60`

禁止事项：

- 不要新增 `@Autowired` 字段注入。
- 不要把“懒得传”的依赖藏进静态工具类。

---

## 异步执行模式

项目当前**没有使用 `@Async`**；异步执行的真实模式是：

1. 在 `AsyncConfig` 中暴露命名 `Executor`
2. 在事务内注册 `afterCommit`
3. 提交后再把刷新任务扔到线程池

`AsyncConfig` 只负责声明线程池：

```java
@Configuration
public class AsyncConfig {

    @Bean(name = "messageHistoryRefreshExecutor")
    public Executor messageHistoryRefreshExecutor(
            @Value("${skill.message-history.refresh.core-pool-size:2}") int corePoolSize,
            @Value("${skill.message-history.refresh.max-pool-size:4}") int maxPoolSize,
            @Value("${skill.message-history.refresh.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("history-refresh-");
        executor.initialize();
        return executor;
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/config/AsyncConfig.java:10-25`

调用侧在事务提交后再触发异步刷新：

```java
public void scheduleLatestHistoryRefreshAfterCommit(Long sessionId) {
    if (sessionId == null) {
        return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                triggerAsyncLatestHistoryRefresh(sessionId);
            }
        });
        return;
    }
    triggerAsyncLatestHistoryRefresh(sessionId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:201-215`

规则：

- 新增异步逻辑时，优先复用命名 `Executor`。
- 不要在事务未提交前刷新 cache、发外部请求、或读取“刚写入但尚未提交”的状态。
- 不要为了省事引入 `@Async`，除非整个模块统一迁移。

---

## 外部 fire-and-forget 上报 / 埋码模式

埋码（telemetry）、审计、第三方推送这类**只关心成功率不关心确认**的旁路上报，必须严格隔离业务路径。核心不变量：**上报链路任何异常都不得抛回业务线程，最坏情况只 WARN 一行日志**。

样板：`skill-server/src/main/java/com/opencode/cui/skill/telemetry/`（WeLink chat 埋码 reporter）。

### 1) 独立 `Executor`，禁止复用业务线程池

埋码必须 own 一个 dedicated `ThreadPoolTaskExecutor`（有界队列 + `DiscardPolicy`），**不要**复用 `messageHistoryRefreshExecutor` 之类的业务执行器。原因：上报背压不能反向挤压 chat 主路径；混池就把这个保证打穿了。

```java
// ✅ telemetry 自己的线程池：bounded queue + DiscardPolicy + 命名前缀
this.delegate = new ThreadPoolTaskExecutor();
this.delegate.setCorePoolSize(cfg.getCorePoolSize());
this.delegate.setMaxPoolSize(cfg.getMaxPoolSize());
this.delegate.setQueueCapacity(cfg.getQueueCapacity());
this.delegate.setThreadNamePrefix("welink-telemetry-");
this.delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
this.delegate.initialize();
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/telemetry/core/TelemetryExecutor.java:24-32`

### 2) `@ConditionalOnProperty` 总开关：disabled 时零 bean、零开销

Reporter / Listener / Aspect / Client **每一个** bean 都用同一个 `havingValue="true"` 守门。配置关闭 → 所有 bean 不装载 → 切面不织入、不查 DB、不建线程池，运行时开销真正为 0。

```java
// ✅ 同一 flag 守 4 个 bean：disabled 时整条上报链路不存在
@Configuration
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class WelinkTelemetryAutoConfiguration { ... }

@Component
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class ChatTelemetryEventListener { ... }

@Aspect @Component
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class ChatReplyAspect { ... }
```

来源：`telemetry/config/WelinkTelemetryAutoConfiguration.java:24`、`telemetry/chat/ChatTelemetryEventListener.java:36`、`telemetry/chat/ChatReplyAspect.java:22`

### 3) 必填配置缺失 → soft-disable，禁止 fail-fast

即使 `enabled=true`，如果 `url` / `token` / `publicKey` / `tenantId` 等必填连接参数为空，autoconfig **必须启动时 WARN 一次然后视同关闭**——而不是 throw 让应用启不来。原因：埋码/可观测性是非关键路径，配置 typo 不应能搞挂 prod chat。

```java
// ✅ effectiveEnabled=false 时 reporter 还在但 report() 直接 return
boolean effectiveEnabled = validate(properties);
if (!effectiveEnabled) {
    log.warn("[WelinkTelemetry] enabled=true but required config missing "
            + "(url/token/publicKey/tenantId) - reporter will be silently disabled");
}
return new WelinkTelemetryReporter(properties, client, executor, effectiveEnabled);

// ❌ 不要这么写：埋码配置错 = 整个 app 起不来
if (!validate(properties)) {
    throw new IllegalStateException("Welink telemetry config incomplete");
}
```

来源：`telemetry/config/WelinkTelemetryAutoConfiguration.java:43-59`

### 4) 每个异步边界都顶层 `try-catch`

业务线程 → 事件发布 → listener → executor.submit → HTTP 客户端，每一段都**独立**包顶层 `try-catch (Throwable)`，把异常吞成 WARN 日志。任意一段漏掉，业务线程就有暴露面。

- 切面 advice：catch 后 WARN，绝不让 `publishEvent` 抛回 caller。
  来源：`telemetry/chat/ChatReplyAspect.java:34-44`
- Listener `@EventListener` 方法：catch 后 WARN，DB 查询失败 / 字段缺失 / NPE 都走同一兜底。
  来源：`telemetry/chat/ChatTelemetryEventListener.java:84-86, 122-125`
- `executor.submit` 内部 Runnable：再包一层，防 reporter 自身 bug 杀线程。
  来源：`telemetry/core/TelemetryExecutor.java:41-47`
- HTTP 客户端：catch `Exception`（含 `HttpStatusCodeException`），只 WARN `httpCode` 不抛。
  来源：`telemetry/client/WelinkTelemetryClient.java:79-88`

### 5) 日志禁止任何 secret / 明文 / 全栈

WARN 行允许的字段：`eventId`、`sessionId`、`httpCode`、`durationMs`、异常**消息**。**禁止**直接 `log.error("...", e)`（栈会带上 request body / inner cause 里的 token）或 `e.toString()`。如果一定要打异常类型，用 `e.getClass().getSimpleName() + ":" + e.getMessage()`。token / publicKey / 明文 payload 任何场景都不出现在日志里。

```java
// ✅ 只暴露关联键 + httpCode + error message
log.warn("[EXT_CALL] WelinkTelemetry.send http_failed: eventId={}, sessionId={}, httpCode={}, durationMs={}, error={}",
        eventId, sessionId, httpCode, elapsedMs, e.getMessage());

// ❌ 把整个栈/cause 链印出来，可能带出 request body 里的 secret
log.error("send failed", e);
```

来源：`telemetry/client/WelinkTelemetryClient.java:72-87`

### 6) Chat 埋码字段契约

`ChatTelemetryEventListener` 统一组装 `skill_chat_request` / `skill_chat_response` 的 `extendData`。当前固定字段为：

```text
businessSessionDomain, businessSessionType, businessSessionId,
senderUserAccount, assistantAccount, businessTag, robotId
```

字段来源约束：

- `robotId` 来源于 assistant info / assistant instance 接口返回的 `data.id`，在代码中落到 `AssistantInfo.id`。
- request 路径复用 `SkillMessageFlowService.routeToGateway(...)` 已解析出的 `scopeInfo`，发布 `ChatRequestTelemetryEvent(..., robotId)`，不要为了埋码再查一次上游。
- reply 路径没有 controller 局部 `scopeInfo`，由 listener 按 session 的 `ak + assistantAccount` 反查 `AssistantInfo`。
- 上游缺失或异常时 `robotId` 转空串，埋码仍继续上报；异常只打 WARN，不抛回主链路。
- 不上报群聊 `groupId`；如需群聊维度，使用现有 `businessSessionDomain/type/id` 组合判断。

测试要求：

- `ChatTelemetryEventListenerTest` 覆盖 request / reply 两个事件的 `robotId` 映射。
- `AssistantInfoServiceTest` 覆盖 `data.id` 解析与 instance fallback 透传。

### 规则速查

- 新增旁路上报模块**必须**有独立 executor、独立 `@ConditionalOnProperty` 守门、4 层 try-catch 兜底。
- 必填配置缺失走 soft-disable + 一次性 WARN，不抛异常。
- 日志只打关联键 + 状态码 + `e.getMessage()`，不打栈、不打 secret、不打明文 payload。

---

## 定时任务调度

`@Scheduled` 注解默认 `initialDelay=0`，**应用启动后会立刻首次执行**。如果调度任务依赖其他 bean 的 `@PostConstruct` 副作用（Redis 订阅、外部连接、缓存加载、心跳 listener 注册等），第一次执行时这些副作用可能还没完成，触发"伪故障 → 自愈"路径，重连风暴 / 误判失联 / 全员 takeover。

正确做法：用 `@EventListener(ApplicationReadyEvent.class)` 显式在所有 bean ready 后再 `TaskScheduler.scheduleWithFixedDelay`。

```java
public SkillInstanceRegistry(StringRedisTemplate redisTemplate,
        RedisMessageBroker redisMessageBroker,
        TaskScheduler taskScheduler,
        @Value("${HOSTNAME:skill-server-local}") String instanceId,
        @Value("${skill.instance-registry.refresh-interval-ms:10000}") long refreshIntervalMs) {
    // ... 构造注入
}

@PostConstruct
public void register() {
    writeHeartbeat();  // 写初始状态保护 startup grace 窗口
    log.info("[ENTRY] SkillInstanceRegistry.register: instanceId={}", instanceId);
}

@EventListener(ApplicationReadyEvent.class)
public void startScheduling(ApplicationReadyEvent event) {
    ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
            this::refreshHeartbeat, Duration.ofMillis(refreshIntervalMs));
    heartbeatFutureRef.set(future);
    log.info("[ENTRY] SkillInstanceRegistry.startScheduling: instanceId={}, intervalMs={}",
            instanceId, refreshIntervalMs);
}

@PreDestroy
public void destroy() {
    ScheduledFuture<?> future = heartbeatFutureRef.get();
    if (future != null) future.cancel(false);
    redisTemplate.delete(redisKey());
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java:54-138`

规则：

- 新定时任务**优先**用 `ApplicationReadyEvent` + `TaskScheduler`；不用 `@Scheduled` 直接注解。
- 例外：任务**不依赖其他 bean 副作用**（纯无状态计算）可用 `@Scheduled`。
- `@PostConstruct` 写**初始状态**保护 startup grace 窗口，但**不**在 PostConstruct 注册周期任务。
- `@PreDestroy` 必须 `cancel(false)` 持有的 `ScheduledFuture`，避免 bean 销毁后仍触发任务。
- `@EnableScheduling` 在 `SkillConfig` 上保留（仍有其他 `@Scheduled`），`TaskScheduler` bean 由 Spring Boot autoconfigure 提供。

> **如何判断是否是 race**：不是看 `@PostConstruct` 和 `@Scheduled` 同居，而是看 schedule 任务**依不依赖** PostConstruct 的副作用（特别是异步副作用如 Redis 订阅）。
>
> 反例 — `ExternalStreamHandler.java:218-225` 表面看像同款：`subscribeRelayChannel`（订阅 Redis）+ `@Scheduled checkHeartbeatTimeouts`。但 `checkHeartbeatTimeouts` 只读 in-memory 字段（`ConnectionPool` 字段初始化、`lastActivity` `ConcurrentHashMap`），**完全不依赖** `subscribeRelayChannel` 的 Redis 副作用。最坏情况空 pool 循环，**不是 schedule-vs-postconstruct race**。
>
> 正例 — `SkillInstanceRegistry.refreshHeartbeat` 在引入 ApplicationReadyEvent 前会调用 `RedisMessageBroker.physicalSubscriberCount` 检查 Redis 端订阅数，而该订阅由**另一个 bean** `GatewayMessageRouter.initSsRelaySubscription` 通过 `RedisMessageListenerContainer.addMessageListener` 异步建立 — schedule 任务依赖跨 bean 的异步 Redis 副作用，**是 race**。

> **另一种独立的 race：subscribe vs container lifecycle**：`ExternalStreamHandler.subscribeRelayChannel` 本身（独立于上方 schedule 讨论）也曾经是 race 的另一种形态 —— 不是 schedule 依赖 PostConstruct 副作用，而是 PostConstruct 内部调用 `RedisMessageBroker.subscribe*()` 时 `RedisMessageListenerContainer`（SmartLifecycle）尚未 `start()`，SUBSCRIBE 不会真实发到 Redis。详见下一节"PostConstruct 不能注册 Redis listener"。两种 race 性质不同：上方 race 是任务**之间**的依赖，本 race 是 PostConstruct 与 Spring lifecycle 自身的依赖。

---

## PostConstruct 不能注册 Redis listener

`RedisMessageListenerContainer` 是 Spring `SmartLifecycle`（默认 `autoStartup=true`），会在**所有 `@PostConstruct` 方法都执行完毕之后**才 `start()`。在 `@PostConstruct` 阶段调 `RedisMessageBroker.subscribe()` / `subscribeToChannel()` / `subscribeToSsRelay()` 时，container 还没 running，`addMessageListener` 把 listener 加进 `channelMapping` 但**不会真实把 SUBSCRIBE 命令发到 Redis**（spring-data-redis 3.4.6 + Lettuce 6.4.2 实测）。

正确做法：监听 `ApplicationReadyEvent`，此时 container 已 `start()`，`addMessageListener` 会立即同步触发 SUBSCRIBE。

```java
// ❌ PostConstruct: container 还没 start, SUBSCRIBE 不会真实发到 Redis
@PostConstruct
void initSsRelaySubscription() {
    redisMessageBroker.subscribeToSsRelay(instanceId, this::handleMessage);
}

// ✅ ApplicationReadyEvent: container 已 start, addMessageListener 立即触发 SUBSCRIBE
@EventListener(ApplicationReadyEvent.class)
void initSsRelaySubscription(ApplicationReadyEvent event) {
    redisMessageBroker.subscribeToSsRelay(instanceId, this::handleMessage);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:282-287`、`skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java:196-202`

规则：

- 任何 `RedisMessageBroker.subscribe*()` 调用必须发生在 `ApplicationReadyEvent` 之后或运行期（如 WebSocket 连接到来时）。
- 不要为了"启动一致性"把 subscribe 塞进 `@PostConstruct`。
- 隐式时序约束：`SkillInstanceRegistry.startScheduling` 也用 `ApplicationReadyEvent`，多个 listener 之间默认无序，但 `firstRunAt = now + interval`（10s）已经给 SUBSCRIBE 留出 settle 窗口，因此**不需要** `@Order`。如果未来把首次延迟改回立即触发，需要重新评估 `@Order`。

> **历史踩坑**：本约定建立前，`ss:relay:{instanceId}` / `ss:external-relay:{instanceId}` 长期 PUBSUB NUMSUB = 0，`SkillInstanceRegistry` 自检每 10s 失败一次并触发 `forceReconnectListenerContainer` 风暴。多 pod 生产场景下跨实例 relay 完全失效。被 PR #20/#21 的 Lettuce decode bug 掩盖，修了那个之后才看清根因。

---

## WebSocket 会话生命周期

miniapp stream 的生命周期全部集中在 `SkillStreamHandler`：

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String userId = extractUserIdFromCookie(session);
    if (userId == null) {
        session.close(CloseStatus.BAD_DATA.withReason("Missing userId cookie"));
        return;
    }

    registerUserSubscriber(session, userId);
    sendInitialStreamingState(session, userId);
}

@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    unregisterSubscriber(session);
    log.info("Skill stream subscriber disconnected: wsId={}, status={}", session.getId(), status);
}

@Override
public void handleTransportError(WebSocketSession session, Throwable exception) {
    ...
    unregisterSubscriber(session);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:90-146`

客户端控制消息只支持 `resume` / `ping`：

```java
JsonNode node = objectMapper.readTree(message.getPayload());
String action = node.path("action").asText(null);
if (ACTION_RESUME.equals(action)) {
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (userId != null) {
        sendInitialStreamingState(session, userId);
    }
    return;
}

if (ACTION_PING.equals(action)) {
    log.debug("Received stream heartbeat: wsId={}", session.getId());
    return;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:103-126`

规则：

- 握手失败直接拒绝连接，不做隐式降级。
- 连接关闭和 transport error 都要走同一个 `unregisterSubscriber` 清理逻辑，保证幂等。
- 不要在其他类里复制“回放 snapshot + streaming state”逻辑；统一留在 handler 内部。

---

## Redis 订阅注册

Redis 订阅注册只通过 `RedisMessageBroker` 完成：

```java
private void subscribe(String channel, Consumer<String> handler) {
    unsubscribe(channel);

    MessageListener listener = (Message message, byte[] pattern) -> {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            handler.accept(json);
            log.info("Received from Redis channel {}", channel);
        } catch (Exception e) {
            log.error("Failed to process message from channel {}: {}",
                    channel, e.getMessage(), e);
        }
    };

    activeListeners.put(channel, listener);
    listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
    log.info("Subscribed to Redis channel: {}", channel);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java:140-159`

业务层只表达“订阅哪个用户流”，不直接操作 listener container：

```java
private void subscribeToUserStream(String userId) {
    if (redisMessageBroker.isUserSubscribed(userId)) {
        return;
    }
    redisMessageBroker.subscribeToUser(userId, message -> handleUserBroadcast(userId, message));
    log.info("Subscribed to user stream: userId={}", userId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:212-219`

规则：

- 不要绕过 `RedisMessageBroker` 直接注册 `MessageListener`。
- 订阅前先做幂等检查，避免重复监听。
- handler 必须自己兜底异常，不能让 Redis listener 线程抛出未捕获错误。

---

## Redis pub/sub 自愈

`SkillInstanceRegistry.refreshHeartbeat` 的订阅自检只负责判断本实例
`ss:relay:{instanceId}` 是否能完成真实 pub/sub 投递。自检必须调用
`RedisMessageBroker.verifySubscriptionDelivery(String channel, long timeoutMs)`：broker 向自己的
relay channel 发布内部 loopback probe，并在同一个 JVM 的 `MessageListener` 收到 probe 后完成 ack。

不要把 `PUBSUB NUMSUB` 作为运行期硬判活。Redis 6 Cluster / 云 Redis 代理场景下，
`PUBSUB` 统计只报告处理该命令的节点 Pub/Sub 上下文，不是整个集群真值；发布连接与订阅连接落在不同节点时，
健康订阅也可能被查成 0。`physicalSubscriberCount` 只保留为诊断工具，不参与 heartbeat 自检。

### 1. Scope / Trigger

- Trigger：`SkillInstanceRegistry.refreshHeartbeat` 刷新心跳前探测本实例 relay channel。
- 目标：发现 listener silent failure，同时避免 Redis Cluster 节点局部统计导致启动期误自检失败。

### 2. Signatures

```java
public boolean verifySubscriptionDelivery(String channel, long timeoutMs)
public boolean forceReconnectListenerContainer(String verifyChannel, long timeoutMs)
public long physicalSubscriberCount(String channel) // 仅诊断，不做 heartbeat 硬判活
```

### 3. Contracts

- `verifySubscriptionDelivery` 发布内部 probe 到目标 channel；probe 被 broker 自己拦截，不进入业务 handler。
- probe 在 `timeoutMs` 内被当前 JVM listener 收到 → 返回 `true`。
- publish 异常、等待超时、线程中断、blank channel → 返回 `false`，不抛给调度线程。
- `forceReconnectListenerContainer` 只重订阅单个 `verifyChannel`：`removeMessageListener(listener, topic)` 后再
  `addMessageListener(listener, topic)`，再用 loopback probe 确认投递恢复。
- 单通道恢复失败后不再整容器 `stop()` / `start()`；共享 container 还承载 `user-stream:*`，整容器重启会打断 miniapp 跨实例流式投递。

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| loopback probe ack received | 自检通过，`refreshHeartbeat` 正常写 heartbeat |
| probe timeout | 调 `forceReconnectListenerContainer` 尝试单 channel 重订阅 |
| 单 channel 重订阅后 probe ack received | 返回 `true`，继续写 heartbeat |
| 单 channel 重订阅后 probe 仍超时 | 返回 `false`，`refreshHeartbeat` 跳过本轮 heartbeat，让 takeover TTL 兜底 |
| `activeListeners` 找不到 verify channel | 返回 `false`，不操作共享 container |
| publish/probe 异常 | WARN 后返回 `false`，不让调度线程崩溃 |

### 5. Good / Base / Bad Cases

Good:

```java
if (!redisMessageBroker.verifySubscriptionDelivery("ss:relay:" + instanceId, 2000L)) {
    redisMessageBroker.forceReconnectListenerContainer("ss:relay:" + instanceId, 2000L);
}
```

Base:

```java
long count = redisMessageBroker.physicalSubscriberCount(channel); // 只用于诊断日志 / 手工排查
```

Bad:

```java
if (redisMessageBroker.physicalSubscriberCount(channel) == 0L) {
    listenerContainer.stop();
    listenerContainer.start();
}
```

### 6. Tests Required

- `RedisMessageBrokerTest` 覆盖 loopback probe 成功、probe 超时、probe 不进入业务 handler。
- `RedisMessageBrokerTest` 覆盖单 channel 重订阅成功 / 失败 / 超时 / 重入时都不触发 container `stop/start`。
- `SkillInstanceRegistryTest` 覆盖 probe 成功写 heartbeat、probe 失败且重连成功写 heartbeat、probe 失败且重连失败跳过 heartbeat。

### 7. Wrong vs Correct

Wrong：把 `PUBSUB NUMSUB = 0` 当作跨 Redis Cluster 的全局真值，并用整容器 `stop/start` 自愈。

Correct：运行期健康检查走 loopback probe；`PUBSUB NUMSUB` 只做诊断。恢复只重订阅当前 relay channel，
失败时保留 heartbeat-skip takeover 兜底，把连接级恢复交给 Spring Data Redis recovery/backoff。

---

## External WS 跨实例投递

External WS 出站由 `ExternalWsDeliveryStrategy` 分两层处理：

1. L1：`ExternalStreamHandler.pushToOne(domain, payload)` 本机投递。
2. L2：`ExternalWsRegistry.findInstancesWithConnection(domain)` 找远程候选 SS 实例，
   逐个调用 `RedisMessageBroker.publishToExternalRelayBestEffort(targetInstance, envelope)`。

不再有 L3 IM REST fallback。external 出站路径不能在 WebSocket/relay 不可用时再向 IM REST 发送一份 assistant 文本，否则 external 客户端与 IM 会看到重复回复。

候选查询沿用 owner-only registry：

- 每个实例只写自己的 `external-ws:held-by:{instanceId}`，字段为 `{domain -> connectionCount}`。
- 查询时先用 `SkillInstanceRegistry.listAliveInstances()` 拿活实例花名册，排除 self。
- 对候选实例 pipeline HGET 对应 `external-ws:held-by:{id}` 的 `{domain}` 字段。
- 只返回 `count > 0` 的实例，且保留 alive roster 顺序。

规则：

- Redis `PUBLISH` / `RedisTemplate.convertAndSend` 返回的 subscriber count 只能做诊断日志。在 Redis Cluster / 云 Redis 代理场景下，该值可能只是 same-node count，不是端到端 delivery ACK。
- L2 成功条件是 `PUBLISH` 命令成功返回；只有 `convertAndSend` 抛异常才尝试下一个候选。
- external relay envelope 必须携带 `traceId`、`welinkSessionId`、`ak`、`userId`，接收实例用 `MdcHelper.fromJsonNode(...)` 恢复 MDC 后再写 `[RELAY-RX]` 日志。
- 不要在 delivery strategy 里手写 `ss:external-relay:`；统一通过 `RedisMessageBroker.publishToExternalRelayBestEffort` /
  `subscribeToExternalRelay`。
- 回归测试必须覆盖：same-node subscriber count 为 0 仍视为 publish accepted、所有候选 publish 异常时停止且不走 IM REST、Pub/Sub relay 后 MDC 可恢复。

---

## SS relay 投递与 takeover 判定

`ss:relay:{instanceId}` 是 SS 实例间回源投递通道。`GatewayMessageRouter.route(...)`
发现会话 owner 在远端实例时，先调用
`RedisMessageBroker.publishToSsRelayBestEffort(targetInstanceId, message)`。

规则：

- `publishToSsRelayBestEffort` 的成功条件是 `RedisTemplate.convertAndSend(...)`
  没有抛异常；返回的 subscriber count 只允许写诊断日志，不表示端到端投递 ACK。
- accepted publish 后，当前实例必须直接返回，不能因为 subscriber count 为 0 继续执行
  takeover。Redis Cluster / 云 Redis 代理场景下这个 count 可能只是 same-node 视图。
- 只有 publish 抛异常或 owner 缺失时才进入 takeover 判定；`shouldTakeover(...)` 只能依据
  owner 是否为空、`SkillInstanceRegistry.isInstanceAlive(owner)` / heartbeat 租约判断，不得读取
  `PUBSUB NUMSUB` 或 publish count。
- publish 异常但 owner heartbeat 仍存活时，必须放弃本次处理并等待 owner 消费或后续事件重试；
  不要本地持久化同一条 gateway 事件，也不要向 external WS 再发一份。
- 回归测试必须覆盖：same-node subscriber count 为 0 仍不 takeover、publish 异常但
  heartbeat alive 不 takeover、publish 异常且 heartbeat missing 才 takeover。

---

## MyBatis 调用风格

service 层直接调用 `Repository` 接口；参数命名与 XML 保持一致，不做“二次包装 Mapper”。

```java
@Transactional
SkillMessage saveMessage(SaveMessageCommand cmd) {
    int nextSeq = messageRepository.findMaxSeqBySessionId(cmd.sessionId()) + 1;
    ...
    messageRepository.insert(message);
    scheduleLatestHistoryRefreshAfterCommit(cmd.sessionId());
    return message;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:66-90`

对应的 Repository 接口：

```java
@Mapper
public interface SkillMessageRepository {
    SkillMessage findById(@Param("id") Long id);
    List<SkillMessage> findBySessionId(@Param("sessionId") Long sessionId,
                    @Param("offset") int offset,
                    @Param("limit") int limit);
    int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);
    int insert(SkillMessage message);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/repository/SkillMessageRepository.java:13-45`  
配套 XML：`skill-server/src/main/resources/mapper/SkillMessageMapper.xml:7-110`

规则：

- 新增查询优先扩展现有 Mapper 接口 + XML，不要引入第二套 ORM。
- 参数一旦超过一个，必须在 Repository 上加 `@Param`。
- 事务边界在 service，不在 Mapper。

---

## 统一出站入口

`StreamMessageEmitter` 是当前权威出站入口：负责 enrich、用户解析、buffer、Redis user-stream 发布。

```java
private void enrich(String sessionId, StreamMessage msg) {
    if (msg == null || sessionId == null) return;

    msg.setSessionId(sessionId);
    msg.setWelinkSessionId(sessionId);

    if (!EMITTED_AT_EXCLUDED_TYPES.contains(msg.getType())
            && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
        msg.setEmittedAt(Instant.now().toString());
    }

    if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            persistenceService.prepareMessageContext(numericId, msg);
        }
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:64-81`

旧入口已经在 `GatewayMessageRouter` 中退化为兼容包装：

```java
@Deprecated
public void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
    emitter.emitToClient(sessionId, userIdHint, msg);
}

@Deprecated
public void publishProtocolMessage(String sessionId, StreamMessage msg) {
    emitter.emitToClientWithBuffer(sessionId, msg);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:855-870`

规则：

- 新代码不要再直接往 Redis user channel 发 `StreamMessage`。
- 不要手动补 `welinkSessionId` / `emittedAt` / message context；让 emitter 做 canonical enrich。
- 如果只是给 miniapp 前端推协议消息，优先用 `emitToClient` / `emitToClientWithBuffer`。

---

## 入站恢复路径按 scope 分流

当 `processChat` / `processRebuild` 需要处理 "session 存在但 `toolSessionId` 缺失" 或主动重生 toolSessionId 时，必须按 `assistantScope` 分流，不能一条路径打到底。

```java
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
        assistantInfoService.getCachedScope(ak));
String generated = strategy.generateToolSessionId();
if (generated != null) {
    // business：本地补齐 / 重生 cloud-* ID，不走 create_session
    ...
} else {
    // personal / scope 识别降级：走 sessionManager.requestToolSession → create_session 回调链路
    sessionManager.requestToolSession(session, prompt);
}
```

- `BusinessScopeStrategy.generateToolSessionId()` 返回非 null（`cloud-` + UUID），代表该助手不依赖 `session_created` 回调。business 路径应**本地持久化** toolSessionId + 直接走正常 chat 投递，不要转发 `create_session` 给 Gateway（云端链路没有对应 handler）。
- `PersonalScopeStrategy.generateToolSessionId()` 返回 null，代表 toolSessionId 必须由 Agent 回调绑定。personal 路径保持 `sessionManager.requestToolSession(...)` / `createSessionAsync` 的现行设计。
- `AssistantInfoService.getCachedScope(...)` 在上游故障时**降级返回 `"personal"`**，所以 scope 分流天然具备 "不确定时落到 personal 兜底" 的安全性。
- business 的本地补/重生必须加 `skill:im-session:heal:{welinkSessionId}` 分布式锁（见 `database-guidelines.md`），防止两个实例同时生成不同 `cloud-*` 覆盖 Redis mapping 导致上行路由丢失。锁 timeout 必须降级到 `requestToolSession` 保留消息，不能抛异常让消息丢失。

### pending 列表只服务 personal rebuild 链路

`SessionRebuildService` 的 `ss:pending-rebuild:{welinkSessionId}` 列表消费者只有 `GatewayMessageRouter.handleSessionCreated` → `retryPendingMessages`，**仅 personal 链路能走到**。business 助手没有 consumer，因此：

- business 路径在 `dispatchChatToGateway` 中**必须不** `appendPendingMessage`（否则积压无人消费，且会被 self-heal 分支的 `consumePendingMessages` 误当 legacy 重放，并发下产生"消息放大"）。
- self-heal 分支在补齐 toolSessionId 后需要 `consumePendingMessages` 把历史残留（可能来自 `handleSessionNotFound` / `handleContextOverflow`）取出重放，重放时对每条消息同样按 business 规则 **不** 再 append。
- personal 路径（case C）保持原有 `appendPendingMessage`，因为它的 rebuild 回调会消费。

来源：`InboundProcessingService.java` — `tryHealBusinessToolSessionId`、`dispatchChatToGateway(..., boolean appendToPending)`。

---

## 测试 mock 不能跨过抽象层

如果代码通过 `RedisCallback` / `Function<Connection, T>` 等回调包装一层 raw 操作，mock **必须深到 callback 内部依赖的接口**，**禁止**直接 mock `redisTemplate.execute(callback)` 让 callback 完全不运行。否则 callback 内的真实路径（Lettuce decode、cast 防御、timeout 行为）一行都没被覆盖。

错误模式 — callback 不运行，"生产爆炸 / 测试全绿"：

```java
// ❌ callback 被绕过，Lettuce decode / cast 路径完全未覆盖
when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(expectedResult);
```

正确模式 — 直接拿 raw connection（推荐）或让 callback 真实运行，mock 深到 callback 内调到的接口：

```java
// 推荐：与生产代码 `RedisConnectionUtils.getConnection(factory)` 路径完全对齐
@Mock private RedisConnectionFactory connectionFactory;
@Mock private LettuceConnection lettuceConnection;
@Mock private RedisClusterAsyncCommands<byte[], byte[]> nativeAsync;
@Mock private RedisFuture<Map<byte[], Long>> pubsubFuture;

@BeforeEach
void setUp() {
    when(redisTemplate.getRequiredConnectionFactory()).thenReturn(connectionFactory);
    when(connectionFactory.getConnection()).thenReturn(lettuceConnection);
    when(lettuceConnection.getNativeConnection()).thenReturn(nativeAsync);
}

@Test
void physicalSubscriberCount_returnsValue() throws Exception {
    when(nativeAsync.pubsubNumsub(any(byte[].class))).thenReturn(pubsubFuture);
    when(pubsubFuture.get(2L, TimeUnit.SECONDS)).thenReturn(Map.of("ch".getBytes(), 3L));
    assertEquals(3L, broker.physicalSubscriberCount("ch"));
}
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/service/RedisMessageBrokerTest.java`

规则：

- 任何使用 raw `connection.*` / native API cast 的代码，测试**必须** mock 到 callback 内部依赖的接口（不是 `RedisTemplate.execute` 这一层）。
- 测试质量自检：如果未来 cast 路径或 native API 签名变更，**测试是否会破？** 破了说明覆盖到位；一直绿说明 mock 跨过了抽象层。
- **方法论**：调查 Redis 客户端层 bug 时，**先用 raw 协议 / 独立 client（PowerShell + RESP、`redis-cli`）验证 server 端真实状态**，再去推测客户端实现。否则容易被 NUMSUB=0 这种"看起来 server 端有问题"的表象误导，做出方向不对的客户端修复。

> **历史踩坑**：`physicalSubscriberCount` 长期返回 0L，触发 `Self-check failed: own relay channel has 0 subscribers` + `Force reconnecting` 风暴。表象 1：单元测试三个 case 全绿（mock 直接 return `redisTemplate.execute(callback)` 的结果，callback 根本没运行）。表象 2：PR #20/#21/#22 轮番修了 Lettuce decode bug、`scheduleWithFixedDelay` 立即触发、`@PostConstruct` 阶段 listener race 三个真实存在的次要问题，但风暴依旧。**真根因**直到用 PowerShell + RESP 直连 Redis 6379 验证 `PUBSUB NUMSUB ss:relay:skill-server-local = :1` 才暴露：是下一节"RedisTemplate.execute 包 connection 成 proxy"导致 cast guard 永远 false。修复时一并把测试 mock 重做到 native API 层 + 切到 `connectionFactory.getConnection()` 路径。

### Spring AOP 切面 + Mockito mock target bean = 静默失活

同一类"测试跨过抽象层"的另一种形态：用 `@SpringBootTest` 或 `@EnableAspectJAutoProxy` 装容器、把被切的 target bean 用 `@MockBean` / `mock(...)` 注入，然后 verify 切面是否触发。结果：**测试全绿，advice 一行没跑**。

机制：Mockito mock 出来的 bean 已经是 CGLIB 代理子类；Spring AOP 看到"已经是代理了"就不会再叠一层 advisor 代理。pointcut 对 mock 调用永远拿不到 advice。

正确切分（参考 `ChatReplyAspectIntegrationTest`，名字带 Integration 但实际是定向单元测试）：

- **切面逻辑** → 单元测：`new MyAspect(deps)` 直接调 advice 方法（如 `aspect.afterFinalize(123L)`）。覆盖 happy path + publisher / collaborator 抛异常的兜底分支。
- **切面装配** → 靠 `@Aspect @Component` 注解 + `pom.xml` 的 `spring-boot-starter-aop` 充当 contract。装配错（注解缺失 / starter 缺失 / pointcut 表达式写炸）会在应用启动时炸，不靠测试 catch。
- **不要尝试合成**："半真的 Spring context + mock 掉的 target bean" 给假绿色。要么纯单元（不要 Spring），要么纯端到端（全部 real bean + `@SpringBootTest`），中间地带是陷阱。

```java
// ❌ 走不通：mock 已是 CGLIB 代理，Spring AOP 不会再叠 advisor 代理
@SpringBootTest
class BadAspectTest {
    @MockBean MessagePersistenceService persistence; // 已是 mock proxy
    @Autowired ApplicationContext ctx;

    @Test
    void aspectShouldFire() {
        persistence.finalizeActiveAssistantTurn(1L); // ChatReplyAspect.afterFinalize 永不触发
        // verify(publisher).publishEvent(any()); // 永远 fail，或者你不验就全绿
    }
}

// ✅ 单元直接调 advice，覆盖 advice 本身的逻辑分支
@Test
void afterFinalizePublishesEvent() {
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    ChatReplyAspect aspect = new ChatReplyAspect(publisher);
    aspect.afterFinalize(123L);
    verify(publisher).publishEvent(any(ChatReplyTelemetryEvent.class));
}

// ✅ 兜底分支：collaborator 抛也不能让 advice 抛回业务线程
@Test
void publisherFailureSwallowed() {
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    doThrow(new RuntimeException("boom")).when(publisher).publishEvent(any());
    new ChatReplyAspect(publisher).afterFinalize(7L); // 不抛
}
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/telemetry/chat/ChatReplyAspectIntegrationTest.java`（Javadoc 解释了为何退化为切面单测）

规则：

- AOP 切面测试默认走"advice 方法直调"单元测；不要相信 mock 出来的 target bean 会触发 advice。
- 切面装配的正确性由 `@Aspect` + `spring-boot-starter-aop` 这条 Spring 标准链兜底，不靠 mock 容器测试。
- 真要端到端验 AOP 织入，target bean 必须是 real bean（不是 mock）；这种测一般不值得，除非 pointcut 表达式很复杂、容易写错。

---

## RedisTemplate.execute 包 connection 成 proxy

`RedisTemplate.execute(RedisCallback)` 默认 `exposeConnection=false`，传给 callback 的 **不是** raw `LettuceConnection`，而是 `CloseSuppressingInvocationHandler` 把 `RedisConnection` 包成的 JDK 动态代理（线上 stack 实测看到的 class 是 `jdk.proxy2.$Proxy134`）。代理的目的是防止 callback 内部误调 `connection.close()` 关闭共享连接（`LettuceConnectionFactory.shareNativeConnection=true` 时），但副作用是：**代理实现 `RedisConnection` interface，但不是 `LettuceConnection` 类的实例**。

后果：

```java
// ❌ 永远走早 return，因为 conn 是 jdk.proxy2.$ProxyN，不是 LettuceConnection 类
redisTemplate.execute((RedisCallback<Long>) conn -> {
    if (!(conn instanceof LettuceConnection lettuce)) return 0L;  // 永远 true → 0L
    ...
});
```

正确做法 — 直接拿 raw connection，绕开 RedisTemplate.execute 的 proxy 包装：

```java
RedisConnectionFactory factory = redisTemplate.getRequiredConnectionFactory();
RedisConnection conn = RedisConnectionUtils.getConnection(factory);
try {
    if (!(conn instanceof LettuceConnection lettuce)) return 0L;  // 这次能真实通过
    Object nativeConn = lettuce.getNativeConnection();
    if (!(nativeConn instanceof BaseRedisAsyncCommands<?, ?> base)) return 0L;
    @SuppressWarnings("unchecked")
    BaseRedisAsyncCommands<byte[], byte[]> async =
            (BaseRedisAsyncCommands<byte[], byte[]>) base;
    Map<byte[], Long> result = async.pubsubNumsub(channel.getBytes(UTF_8))
            .get(2, TimeUnit.SECONDS);
    return result.values().stream().findFirst().orElse(0L);
} catch (Exception e) {
    return 0L;
} finally {
    RedisConnectionUtils.releaseConnection(conn, factory);
}
```

参考实现：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java::physicalSubscriberCount`

规则：

- 需要把 raw connection cast 成具体子类（`LettuceConnection`、`JedisConnection` 等）调 native API 时，**禁止**使用 `redisTemplate.execute(callback)`，必须走 `getRequiredConnectionFactory().getConnection()` + `RedisConnectionUtils.releaseConnection`。
- `RedisConnectionUtils` 是 Spring 推荐的 lifecycle 工具，正确处理 share / dedicated 连接的 close 语义；不要自己 `try-with-resources`，那会误关共享连接。
- 备选方案 `redisTemplate.execute(callback, true /* exposeConnection */)` 不被采用：它依赖 RedisTemplate 内部约定（"true 时不包 proxy"），未来 Spring 升级时风险更大；项目 spring-data-redis 固定 3.4.6，但仍优先选不依赖 internal 行为的 Approach B。
- 测试 mock 路径必须与生产代码对齐：mock `redisTemplate.getRequiredConnectionFactory()` → mock `factory.getConnection()` 返回 mock `LettuceConnection`，**不要** mock `redisTemplate.execute(callback)`（见上一节）。

> **历史踩坑**：本约定建立前，`RedisMessageBroker.physicalSubscriberCount` 用 `redisTemplate.execute((RedisCallback<Long>) conn -> ...)`，cast guard `if (!(conn instanceof LettuceConnection))` **永远 false** → 永远早 return 0L → `SkillInstanceRegistry` self-check 永远失败 → 每 10s 触发 `forceReconnectListenerContainer` 风暴。配套 unit test mock `redisTemplate.execute(callback)` 让 callback 不运行，三个 case 全绿掩盖了真根因。修复用 PowerShell + RESP 直连 Redis 验证 `PUBSUB NUMSUB = :1`（server 端订阅健康）后，才把焦点定位到客户端 cast guard。

---

## 运维可配置的"套餐" = SysConfig 数据 + Registry 运行时拼装

新增"多策略可自由组合"维度（例：业务 vendor × 出/入参协议）时，**不要**为每个组合写一个 `@Component`。让策略本身保持 `@Component`，再用 record / POJO 表达"套餐"，由 `@Service` Registry 运行时按两段 SysConfig 查找拼装：①`<feature>_profile:<dimensionKey>` → profile name；②`<feature>_profile_def:<profileName>` → JSON 列出选哪些策略 bean。`profile_def` 缺失时约定 fallback 为 "profile name == strategy bean name"。

Registry 必须 in-memory TTL cache（5min 量级），SysConfig 不要进热路径。跨服务（SS↔GW）只共享 profile name 字符串。参考：`CloudRequestProfileRegistry`、`BusinessScopeStrategy.buildInvoke` 调用点。

## 云端 abort_session 是控制帧，不构造 cloudRequest

### 1. Scope / Trigger

适用于 `BusinessScopeStrategy.buildInvoke(...)` 与
`DefaultAssistantScopeStrategy.buildInvoke(...)` 处理
`GatewayActions.ABORT_SESSION`。`abort_session` 用来通知 GW 取消已有 SSE/WebSocket
流，不是一次新的云端请求，因此不能进入 `CloudRequestStrategy.build(...)`。

### 2. Signatures

- 入口：`GatewayRelayService.sendInvokeToGateway(InvokeCommand)`。
- business scope：`BusinessScopeStrategy.buildInvoke(InvokeCommand, AssistantInfo)`。
- default assistant scope：`DefaultAssistantScopeStrategy.buildInvoke(InvokeCommand, AssistantInfo)`。
- GW 取消键：`payload.toolSessionId`，profile hint：`payload.cloudProfile` 或顶层 `businessTag`。

### 3. Contracts

当 `action == abort_session` 时，SS 只发送控制 payload：

- `type=invoke`
- `action=abort_session`
- `assistantScope=business`
- `payload.toolSessionId=<current toolSessionId>`
- `payload.cloudProfile=<resolved profile name>`
- 可选顶层 `assistantAccount`、`businessTag`、`userId`

`payload.cloudRequest` 必须缺失。`abort_session` 不需要 `content`、
`assistantAccount`、`sendUserAccount` 组成 `CloudRequestContext`，也不得触发
`DefaultCloudRequestStrategy` / `AssistantSquareCloudRequestStrategy` 的账号 fast-fail。
chat / question_reply / permission_reply 仍按原逻辑构造 `cloudRequest`，保持
`sendUserAccount` 强校验。

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Business cloud session abort, payload only has `toolSessionId` | Build control invoke; do not call `CloudRequestStrategy.build`. |
| Default-assistant session abort | Inject rule `assistantAccount` for top-level routing; still omit `cloudRequest`. |
| chat / reply missing `sendUserAccount` | Preserve existing fast-fail; do not borrow abort's relaxed behavior. |
| `toolSessionId` missing | Emit control invoke without `payload.toolSessionId`; GW logs no connection key/no active connection. |

### 5. Good / Base / Bad Cases

Good:

```java
if (GatewayActions.ABORT_SESSION.equals(action)) {
    return buildAbortInvoke(command, toolSessionId, assistantAccount, businessTag, profile);
}
```

Base:

```java
payload.put("cloudProfile", profile.name());
payload.put("toolSessionId", toolSessionId);
```

Bad:

```java
CloudRequestContext context = CloudRequestContext.builder()
        .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
        .build();
return strategy.build(context); // wrong for abort_session
```

### 6. Tests Required

- `BusinessScopeStrategyTest`: `abort_session` has no `payload.cloudRequest` and never calls `CloudRequestStrategy.build`.
- `DefaultAssistantScopeStrategyTest`: default-assistant abort does not require `sendUserAccount`.
- `DefaultAssistantRuleE2EIntegrationTest`: rule-injected abort wire payload contains `payload.toolSessionId` and omits `payload.cloudRequest`.

### 7. Wrong vs Correct

Wrong: fixing abort by adding fake `sendUserAccount` / fake `content` only to satisfy cloud request validation.

Correct: treat abort as a GW control frame and keep the cloud request builders reserved for real cloud callback actions.

## JSON wire format 与 Java 字段名解耦

`StreamMessage` / `SendMessageRequest` / 类似跨服务 DTO 序列化 JSON 时，**wire format（JSON 字段名）是契约，Java 字段名只是内部代号**——两者绝不能耦合。

默认下 Lombok `@Data` + Jackson 用 Java 字段名 == JSON key，意味着**重命名 Java 字段会静默改 JSON 协议**，下游 miniapp / plugin / 上游 SS-Gateway / 历史回放数据都会一起爆。

**规则**：

- 跨进程 DTO 字段，wire name 与 Java 字段名"看起来一致"时，仍**建议**加 `@JsonProperty("wireName")` 锚定 wire name——成本极低、收益是后续 Java 字段重命名零协议风险
- wire name 与 Java 字段名**必须不同**时（历史包袱 / 多端命名分歧），`@JsonProperty` 是**强制**的
- 修改任何带 `@JsonProperty` 的字段时，先决定要改的是"Java 内部名"还是"协议 wire name"：
  - 只改 Java 内部名 → 改字段名，**保持 `@JsonProperty` 的字符串不变**
  - 改协议 wire name → 改 `@JsonProperty` 的字符串，同步推 miniapp / plugin / 协议文档全链路

```java
// ✅ 安全：Java 字段叫 questionId，JSON wire key 也叫 questionId，但显式锚定，未来重命名 Java 字段零协议风险
@Data @Builder
public static class QuestionInfo {
    @JsonProperty("questionId")
    private String questionId;
}

// ❌ 危险：Lombok @Data + Jackson 默认 → 字段重命名 = wire key 静默改名
@Data @Builder
public static class QuestionInfo {
    private String questionId; // 重命名为 qid 时 JSON 协议立刻变 {"qid": ...}
}
```

**遵守这条约定时配套的 grep 习惯**：改动 `StreamMessage.*` 任何字段前先 `grep -rn "<oldFieldName>" skill-miniapp plugins .trellis/spec` 看协议文档与下游消费者，决定是只改 Java 还是全链路改名。

> **历史踩坑**：`StreamMessage.QuestionInfo.requestId` 原本无 `@JsonProperty`，依赖 Java 字段名 == wire name。重命名为 `questionId` 时必须同步改 miniapp / plugin contracts / `.trellis/spec/business/protocol/01-consumer-stream-protocol.md`；任何一处遗漏都会让 question_reply 快路径静默退化为 D8 fallback。

## 跨 vendor 的 toolSessionId 必须 Long-parseable

`toolSessionId` 是 SS 内部生成、可能被任意云端 vendor 当作业务 ID（如助手广场 `topicId: long`）使用。生成端（`*ScopeStrategy.generateToolSessionId`）**必须用 Snowflake**（`SnowflakeIdGenerator.nextId().toString()`），不要用 `"prefix-" + UUID`。否则下游 `Long.parseLong(toolSessionId)` 在新 vendor 接入时炸裂，且改不动（已下发的 ID 收不回）。

## 禁止事项

| 禁止 | 原因 | 正确做法 |
|------|------|---------|
| `@Autowired` 字段注入 | 隐藏依赖、测试成本高 | 构造函数注入 |
| 在 `@PostConstruct` 里 `taskScheduler.schedule*` 注册周期任务 | 启动顺序不确定，可能在依赖 bean 的 PostConstruct 之前跑 | 用 `@EventListener(ApplicationReadyEvent.class)` |
| 在 `@PostConstruct` 里调 `RedisMessageBroker.subscribe*()` | `RedisMessageListenerContainer` 是 SmartLifecycle，PostConstruct 阶段尚未 `start()`，SUBSCRIBE 不真实发到 Redis（NUMSUB=0） | 用 `@EventListener(ApplicationReadyEvent.class)` |
| `mock(redisTemplate.execute(any())).thenReturn(...)` 让 callback 不运行 | Lettuce decode / cast / timeout 路径全部没覆盖；生产爆炸测试全绿 | mock 深到 callback 内部依赖（`connectionFactory.getConnection()` → `LettuceConnection.getNativeConnection()` 等） |
| 在 `redisTemplate.execute((RedisCallback) conn -> ...)` 内 `instanceof LettuceConnection` cast | 默认 `exposeConnection=false`，conn 是 `CloseSuppressingInvocationHandler` JDK 代理，cast 永远 false → 早 return | 走 `redisTemplate.getRequiredConnectionFactory().getConnection()` + `RedisConnectionUtils.releaseConnection` |
| 直接在业务类里注册 Redis listener | 难以清理、难以幂等 | 统一走 `RedisMessageBroker` |
| 在事务内立即刷新历史 cache | 可能读到未提交状态 | `afterCommit` + 命名 `Executor` |
| 绕过 `StreamMessageEmitter` 手写前端推送 | 容易漏掉 enrich / buffer / context | 调用 emitter |
| 把 personal / business 分支散落到 Controller | scope 规则难以维护 | 使用 `AssistantScopeDispatcher` |
| business 路径调 `appendPendingMessage` / `requestToolSession` | 云端链路无 `session_created` 回调，消息积压或静默丢失 | 本地 `generateToolSessionId` + `updateToolSessionId`，加 `skill:im-session:heal:*` 锁 |
| 跨 scope 共用的 helper 不区分 append-pending | business 和 personal 对 pending 列表的消费语义不同，并发下造成消息放大或丢消息 | helper 必须显式接收 `appendToPending` 参数，由调用方按 scope 决策 |
| 旁路上报（telemetry / audit / 外推）复用业务命名 `Executor` | 上报背压会反向挤压 chat 主路径，破坏隔离不变量 | 模块自带独立 `ThreadPoolTaskExecutor`（bounded queue + `DiscardPolicy` + 命名前缀） |
| 旁路上报必填配置缺失时 fail-fast（throw / 启动失败） | 可观测性不应能搞挂 prod 业务；config typo 不该让 app 起不来 | `@ConditionalOnProperty` 总开关 + soft-disable + 启动 WARN 一次（参见 `WelinkTelemetryAutoConfiguration`） |
| 旁路上报日志带栈或 secret（`log.error("...", e)` / `e.toString()` / 打 token / 打明文 payload） | 异常栈和 cause 链可能带出 token / request body | 只 WARN 关联键（eventId / sessionId / httpCode）+ `e.getMessage()`；要带异常类型时用 `e.getClass().getSimpleName() + ":" + e.getMessage()` |
| `@SpringBootTest` / `@MockBean` mock 掉被切的 target bean，期望切面会触发 | Mockito mock 已是 CGLIB 代理；Spring AOP 不会再叠 advisor，advice 永不触发，测试全绿但实际失活 | 切面方法直接单元测（`new MyAspect(deps).adviceMethod(...)`）；装配靠 `@Aspect @Component` + `spring-boot-starter-aop` 兜底 |

## Gateway tool_event scope selection

### 1. Scope / Trigger

This applies to `GatewayMessageRouter.handleToolEvent(String sessionId, String ak, String userId, JsonNode node)`.
Gateway upstream events cross the ai-gateway -> skill-server -> miniapp boundary, so the translator strategy must be selected from the persisted `SkillSession` context when a session is available.

### 2. Signatures

- Session lookup: `SkillSession session = resolveToolEventSession(sessionId, node)`.
- DB recovery order: persisted `welinkSessionId` lookup first, then persisted `toolSessionId` lookup when the session is still missing.
- Default-assistant aware strategy lookup: `scopeDispatcher.getStrategy(session.getBusinessSessionDomain(), session.getBusinessSessionType(), info)`.
- Fallback legacy lookup: `scopeDispatcher.getStrategy(info)` when no session is available.
- Assistant info lookup with account context: `assistantInfoService.getAssistantInfo(session.getAk(), session.getAssistantAccount())`.

### 3. Contracts

Inbound gateway `tool_event` may carry only `ak`, `toolSessionId`, `welinkSessionId`, and `event`.
Do not assume `ak` alone identifies the assistant scope. Default assistants use virtual AK values that the upstream assistant-info API may not know.
When `SkillSession` exists or can be recovered from DB, its `businessSessionDomain`, `businessSessionType`, `ak`, and `assistantAccount` are the authoritative routing context.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Session exists and `(domain,type)` matches `default_assistant_rule` | Use `DefaultAssistantScopeStrategy`; translate cloud events via `CloudEventTranslator`; do not require virtual AK assistant-info lookup. |
| Session exists and no default rule matches | Resolve assistant info from `(ak, assistantAccount)` when possible, then dispatch through `scopeDispatcher.getStrategy(domain,type,info)`. |
| `resolveSession(sessionId)` misses but `toolSessionId` exists | Recover `SkillSession` through `SkillSessionService.findByToolSessionId`, refresh the Redis tool-session mapping, and use the recovered session id for translation/delivery. |
| Session is still missing after DB recovery | Preserve legacy behavior: resolve by `ak` and dispatch through `scopeDispatcher.getStrategy(info)`. |
| Translator returns `null` | Log ignored event with the selected strategy scope; do not persist or deliver a partial message. |

### 5. Good / Base / Bad Cases

Good:

```java
SkillSession session = resolveToolEventSession(sessionId, node);
if (session != null && session.getId() != null) {
    sessionId = session.getId().toString();
}
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
        session.getBusinessSessionDomain(),
        session.getBusinessSessionType(),
        info);
```

Base:

```java
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```

Bad:

```java
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```

The bad case drops default-assistant cloud replies because a virtual AK can resolve to `null`, which falls back to personal/OpenCode translation and ignores cloud `text.delta` / `text.done`.

### 6. Tests Required

- Add or keep a router-level regression test where a default-assistant session has a virtual AK, `assistantInfoService.getAssistantInfo(ak)` would be `null`, and an inbound `tool_event` with `event.type=text.delta` still reaches `StreamMessageEmitter.emitToSession`.
- Add a regression test where primary `welinkSessionId` lookup misses or is non-numeric, `toolSessionId` DB lookup recovers the session, and translation/delivery use the recovered session id.
- Keep personal cloud protocol tests (`event.protocol=cloud`) green to ensure the session-aware path does not break personal-scope cloud translation.
- Keep relay/takeover tests green because `handleToolEvent` is reached through `dispatchLocally` and SS relay paths.

### 7. Wrong vs Correct

Wrong: choosing the translator from the top-level gateway AK for every upstream event.

Correct: recover session context from `welinkSessionId` / `toolSessionId` before choosing a translator; when a session exists, first use the session `(domain,type)` to preserve default-assistant routing, then resolve assistant info only for non-default assistant paths.

## Assistant instance lookup for no-AK remote assistants

### 1. Scope / Trigger

This applies to IM / external inbound chat and miniapp session flows that identify an assistant by `assistantAccount`.
The assistant instance API is now the shared source for assistant metadata, and cloud/remote assistants may not have an `appKey`.

### 2. Signatures

- Instance lookup: `AssistantInstanceInfoService.lookup(String partnerAccount)`.
- Resolver entry: `ResolveOutcome AssistantAccountResolverService.resolveWithStatus(String assistantAccount)`.
- Route identity: `AssistantSessionIdentity.fromResolveOutcome(outcome, requestedAssistantAccount)` or `AssistantSessionIdentity.defaultAssistant(ak, senderUserAccount, assistantAccount)`.
- Scope lookup with account context: `AssistantInfo AssistantInfoService.getAssistantInfo(String ak, String assistantAccount)`.
- Local session DB lookup: `findByBusinessSessionAndAkAndAssistantAccount(domain,type,businessSessionId,ak,assistantAccount)`.
- Session DB lookup for no-AK remote sessions: `findByBusinessSessionAndAssistantAccount(domain,type,businessSessionId,assistantAccount)`.

### 3. Contracts

`AssistantInstanceInfo.remoteType` is authoritative: `0` means local/non-remote, `1` means assistant-square protocol/profile, and `2` means default protocol/profile. The upstream instance API no longer provides `isRemote`; do not infer remote routing from `isRemote` or `remoteProperty`.
`AssistantInstanceInfo.businessRoutableAssistant()` means `remoteType` is `1` or `2`.
For cloud profile selection, use the direct instance hint (`remoteType=1 -> assistant_square`, `remoteType=2 -> default`) and resolve that profile directly by name; do not read SysConfig businessTag-to-profile or profile-def mappings for assistant-instance-driven remoteType routes.
Blank `appKey` alone must not imply remote/cloud. A no-AK assistant is routable only when `businessRoutableAssistant()` is true.
For local assistants, blank `appKey` or blank owner identity is upstream incomplete data and must remain `UNKNOWN`, not `NOT_EXISTS`.
The owner identity for local assistants comes from instance API `createdBy`; do not synthesize it from `partnerAccount` or `assistantAccount`.
Cloud/remote assistants have no per-user owner; their route identity owner must remain null.

`ResolveOutcome.EXISTS` for a remote assistant may contain `ak == null`, but must carry `assistantAccount`, `remote=true`, and optional `businessTag` when available.
`assistantAccount:status:{account}` cache values must preserve `remote` and `businessTag` so cached remote no-AK assistants do not degrade to local unknown.

Inbound chat / question-reply / permission-reply / rebuild must derive a single `AssistantSessionIdentity` before session lookup:

- `LOCAL`: lookup with `ak + assistantAccount`; downstream invokes keep the real `ak` and `createdBy` owner where an owner route hint is needed.
- `REMOTE`: lookup with `assistantAccount`; `ak` may be null and must not force personal rebuild.
- `DEFAULT`: lookup with `assistantAccount`; downstream invokes keep the configured virtual `ak` and sender user id.

Newly-created `SkillSession.userId` is a session owner/current-user field, not an assistant owner field:
`direct` IM/external sessions use the current sender identity (`senderUserAccount`), miniapp uses cookie `userId`, and IM `group` sessions must store `NULL`.
Do not use `SkillSession.userId` to infer local assistant owner or group sender; group replay/fallback paths must use the structured pending request sender or fail loudly.
For local assistants, Gateway `InvokeCommand.userId` may carry the `createdBy` owner as an internal route hint, while payload `sendUserAccount` always stays the per-message sender.
When an existing personal IM session has blank `toolSessionId` or `processRebuild(...)` recreates a tool session, `InboundProcessingService` must call the route-user-aware `ImSessionManager.requestToolSession(...)` overload with `ownerWelinkId` as the optional route hint. This must not repopulate `SkillSession.userId` for group sessions; group sender identity remains in `PendingChatRequest.sendUserAccount`.

`ImSessionManager#createSessionAsync(..., AssistantSessionIdentity, ...)` must reuse the same identity inside the create lock. If the lock-internal second lookup finds an existing business session, it must initialize the tool session via `AssistantScopeStrategy.generateToolSessionId()` and send the pending chat immediately. Do not fall back to the legacy `requestToolSession(session, String)` overload for remote/default business sessions.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Instance lookup returns NOT_EXISTS or empty data | Return `NOT_EXISTS`; cache with NOT_EXISTS TTL. |
| Instance data has `remoteType=1` and no `appKey` | Return `EXISTS`; no online AK check; route as business with `cloudProfile=assistant_square`. |
| Instance data has `remoteType=2` and no `appKey` | Return `EXISTS`; no online AK check; route as business with `cloudProfile=default`. |
| Instance data has `remoteType=0`, even with legacy `remoteProperty`, and no `appKey` | Return `UNKNOWN`; do not write status cache. |
| Instance data omits `remoteType` but has legacy `isRemote=true` or non-empty `remoteProperty` and no `appKey` | Return `UNKNOWN`; do not write status cache. |
| Instance lookup is non-2xx, body code is not 200, parse fails, or times out | Return `UNKNOWN`; do not write status cache. |

### 5. Good / Base / Bad Cases

Good:

```java
ResolveOutcome outcome = assistantAccountResolverService.resolveWithStatus(assistantAccount);
AssistantInfo info = assistantInfoService.getAssistantInfo(outcome.ak(), outcome.assistantAccount());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(domain, type, info);
```

Base:

```java
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
```

Bad:

```java
if (ak == null) {
    return ExistenceStatus.NOT_EXISTS;
}
```

The bad case blocks real remote assistants that intentionally do not expose an `appKey`, and it also cannot distinguish local incomplete data from missing assistants.

### 6. Tests Required

- `AssistantAccountResolverServiceTest`: no-AK `remoteType=1/2` instance returns `EXISTS`; no-AK `remoteType=0` local instance returns `UNKNOWN` even if legacy `remoteProperty` is present.
- `AssistantInfoServiceTest`: no-AK `remoteType=1/2` instance returns business `AssistantInfo` with the matching `cloudProfile`; no-AK local instance returns `null`.
- `InboundProcessingServiceTest`: no-AK remote inbound chat is not blocked by `assistant_check_unknown` and creates or uses a business session.
- `InboundProcessingServiceTest`: local/default/remote routes assert the expected session lookup keys; no-AK remote rebuild uses `assistantAccount` and business local toolSessionId regeneration.
- `ImSessionManagerTest`: no-AK remote existing business session found during async creation does not call legacy rebuild and sends the chat invoke with generated `cloud-*` toolSessionId.
- `AssistantInstanceInfoServiceTest`: instance API empty data maps to `NOT_EXISTS`, failures map to `UNKNOWN`, and success maps fields used by resolver/gateway.

### 7. Wrong vs Correct

Wrong: treating `appKey` absence as "assistant does not exist" or as "cloud assistant".

Correct: first decide existence from the instance API result, then decide routability from `remoteType` (`1/2` remote, `0` local). Missing `remoteType` is not remote, even if legacy `isRemote` or `remoteProperty` fields are present.

Wrong: scattering `ak == null` checks at individual session lookup / rebuild call sites.

Correct: build `AssistantSessionIdentity` once at the entry point, use its `lookupAk()` / `lookupAssistantAccount()` for session lookup, and keep its real invoke identity for Gateway payloads.

---

## Streaming Snapshot Assistant Turn Identity

Streaming persistence treats one assistant turn as a canonical DB message, even when upstream
events inside that turn carry different `messageId` values for text, tool calls, tool errors,
or final text. `messageId` changes are transport metadata unless a newer user message has
already started the next turn.

### 1. Scope / Trigger

- Trigger: `MessagePersistenceService` receives assistant streaming parts from Gateway or
  snapshot replay paths.
- Scope: text, thinking, tool, and other assistant parts that share the same user turn.
- Goal: keep history and streaming snapshots reconstructing the same assistant bubble.

### 2. Signatures

- Active turn resolver: `ActiveMessageTracker.resolveActiveMessage(...)`.
- User-turn guard: `ActiveMessageTracker.hasNewerUserMessage(...)`.
- User lookup: `SkillMessageService.findLastUserMessage(Long sessionId)`.
- Persistence entry: `MessagePersistenceService.handleStreamingData(...)`.

### 3. Contracts

- A generated placeholder message id such as `msg_{session}_{seq}` may be adopted by the first
  real upstream assistant `messageId` in the same turn.
- After adoption, `ActiveAssistantMessage.messageId` is the canonical persisted message id.
  Later upstream ids stay in `sourceMessageId` and must not split the DB row by themselves.
- A new upstream assistant `messageId` may finalize the active assistant row only when the
  latest persisted user message has `seq > active.seq`.
- Tool parts must be ordered and persisted by `partId` / `partSeq`, not by forcing a new
  assistant message whenever `toolCallId` or upstream `messageId` changes.
- Snapshot payloads must be safe to merge back into history by stable part identity:
  `partId` first, then `toolCallId` for tool parts.
- Redis stream snapshots are live replay state, not durable history. `busy` / `retry`
  `session.status` marks the session as streaming; `idle` / `completed` and stream-level
  `error` / `session.error` are terminal and must clear live replay state. Once terminal
  content is persisted, resume/refresh must recover it from history, not from snapshot parts.
- REST abort is also a local terminal boundary. `SkillSessionFlowService.abortSession(...)`
  must send `abort_session` when needed, persist buffered live replay parts as terminal parts,
  persist `session.status=idle`, mark the DB session `IDLE`, and only then clear
  `StreamBufferService`.
- Router-level `tool_error` does not pass through the normal `routeAssistantMessage(...)`
  buffer accumulation path; miniapp sessions must clear `StreamBufferService` explicitly after
  the real-time error event is emitted.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| text -> tool -> text in one assistant turn with different upstream ids | Persist all parts under one canonical assistant message id. |
| placeholder id followed by real upstream id | Adopt the real id without duplicating the assistant row. |
| new user message after active assistant seq, then new assistant id | Finalize previous active row and create the next assistant row. |
| tool error arrives after text | Keep tool error as a part of the same assistant message unless a newer user turn exists. |
| snapshot contains parts already present in history shells | Frontend restore may remove duplicate parts by stable part id/tool call id. |
| `busy` / `retry` status arrives before parts | Snapshot reports streaming/busy even if no parts have been buffered yet. |
| `idle` / `completed` / `error` / `session.error` arrives | Clear live replay state so resume falls back to DB history. |
| user aborts while live replay has `text.delta` / `thinking.delta` but no done event | Convert the buffered delta to `text.done` / `thinking.done`, persist it, persist idle, mark session `IDLE`, then clear live replay state. |
| router emits `tool_error` directly | Emit the real-time error, persist it, then clear miniapp live replay state. |

### 5. Good / Base / Bad Cases

Good:

```java
if (hasNewerUserMessage(sessionId, active)) {
    finalizeActiveAssistantTurn(sessionId);
    return startNewAssistantMessage(upstreamMessageId);
}
return active.withSourceMessageId(upstreamMessageId);
```

Base:

```java
active.appendPart(partId, partSeq, type, content);
```

Bad:

```java
if (!Objects.equals(active.messageId(), upstreamMessageId)) {
    finalizeActiveAssistantTurn(sessionId);
}
```

### 6. Tests Required

- `MessagePersistenceServiceTest`: same-turn upstream id switches across text/tool/text stay on
  one canonical assistant message.
- `MessagePersistenceServiceTest`: a newer user message still allows the next assistant id to
  create a new assistant message.
- Snapshot / stream tests must include at least one tool error or tool done part when changing
  assistant turn reconstruction.
- `StreamBufferServiceTest` / `SnapshotServiceTest` / router tests must cover busy before
  parts, terminal error clear, and direct `tool_error` buffer clear.
- `SkillSessionControllerTest` / flow tests must cover abort finalization: buffered delta is
  persisted before idle, `StreamBufferService.clearSession(...)` is called, and
  `SkillSessionService.markSessionIdle(...)` runs.

### 7. Wrong vs Correct

Wrong: treating each assistant upstream `messageId` as a persisted bubble boundary.

Correct: use the persisted canonical assistant message as the turn identity, and use the latest
user `seq` as the only guard that permits an upstream id switch to start a new assistant turn.

### 8. Overlapping External / IM Streaming Guard

When an external or IM client sends a second user message before the first assistant reply has
fully drained, late events from reply 1 may arrive after reply 2 has become the active assistant
turn. SS must route those late events by the upstream reply identity they already carry, not by
the currently active reply.

#### Scope / Trigger

- Trigger: `GatewayMessageRouter.handleToolEvent(...)` / `handleToolDone(...)` receives
  overlapping assistant events for the same `welinkSessionId`.
- Trigger: `MessagePersistenceService.prepareMessageContext(...)` or `persistIfFinal(...)`
  sees `msg.messageId` / `msg.sourceMessageId` for an older persisted assistant message while
  a newer assistant message is active.
- Default cloud protocol note: cloud events may omit `partId`; `step.start` / `step.done` may
  carry `messageId` without `partId`. SS must not repair this by using the user request
  `messageId` as the assistant reply id.

#### Signatures

- `ActiveMessageTracker.resolveActiveMessage(Long sessionId, StreamMessage msg)`
- `SkillMessageService.findBySessionIdAndMessageId(Long sessionId, String messageId)`
- `GatewayMessageRouter.accumulateCloudImText(String sessionId, StreamMessage msg, SkillSession session, String traceId)`
- `GatewayMessageRouter.flushAccumulatedCloudImTextDone(String sessionId, String userId, SkillSession session, String traceId)`

#### Contracts

- If `requestedMessageId` exists in DB and `existing.seq < active.seq`, apply that existing
  message context to the current `StreamMessage` and return it, but do not replace
  `activeMessages[sessionId]`.
- `StreamMessage.messageId` is the delivery / canonical bubble id. `sourceMessageId` remains
  the upstream event's original id and must not be overwritten when older events are rebound to
  their old persisted message.
- IM fallback text accumulation is scoped by `sourceMessageId || messageId`; when both are
  missing, use the top-level Gateway `traceId`; only then use a missing-id bucket.
- A real upstream `text.done` removes only the matching bucket. It must not clear all
  `text.delta` content for the session.
- A synthesized fallback `text.done` must include the accumulator's `messageId`,
  `sourceMessageId`, and last known `partId` so `StreamMessageEmitter` and external WS delivery
  cannot enrich it onto the newer active reply.
- `tool_done` with a `traceId` flushes only the matching trace bucket. If no bucket matches that
  trace, do not synthesize `text.done` from another bucket.
- `tool_done` completion marks are trace-scoped when `traceId` exists. A later reply finishing
  first must not suppress residual `tool_event` / real `text.done` events from an earlier reply.
- If there is no trace and multiple buckets exist, do not guess by oldest bucket. Only the
  single-bucket no-trace fallback may synthesize `text.done`.

#### Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| reply 1 event arrives while reply 2 is active | Emit and persist under reply 1 `messageId`; keep reply 2 active. |
| reply 1 and reply 2 both have buffered IM `text.delta` | `tool_done(traceId=reply1)` emits only reply 1 fallback `text.done`. |
| reply 2 sends real `text.done`, then `tool_done(traceId=reply2)` while reply 1 is still streaming | Do not emit reply 1 buffered text as reply 2 fallback; reply 1 later events are still accepted. |
| upstream sends real `text.done` for reply 1 | Remove reply 1 bucket only; leave reply 2 bucket intact. |
| event has no `messageId` but has `traceId` | Use `trace:{traceId}` as the temporary accumulator key. |
| event has no `messageId` and no `traceId` | Use the missing-id bucket; do not borrow the inbound request message id. Multiple no-trace buckets must not be guessed. |

#### Good / Base / Bad Cases

Good:

```java
SkillMessage existing = messageService.findBySessionIdAndMessageId(sessionId, requestedMessageId);
if (existing != null && existing.getSeq() < active.messageSeq()) {
    applyMessageContext(msg, toActiveMessageRef(existing), role);
    return existingRef; // activeMessages keeps the newer reply
}
```

Base:

```java
String key = firstNonBlank(msg.getSourceMessageId(), msg.getMessageId());
if (key == null) {
    key = firstNonBlank(traceId, "__missing_message_id__");
}
```

Bad:

```java
cloudImTextBuffer.computeIfAbsent(sessionId, k -> new StringBuilder()).append(delta);
cloudImTextBuffer.remove(sessionId); // clears unrelated overlapping reply content
```

#### Tests Required

- `MessagePersistenceServiceTest`: late older upstream `messageId` keeps its original message
  context while a newer turn remains active.
- `GatewayMessageRouterTest`: IM fallback `text.done` flushes only the matching `traceId` /
  message bucket and does not emit the other reply's buffered text.
- `GatewayMessageRouterTest`: when reply 2 completes before a long reply 1, reply 2 `tool_done`
  neither flushes reply 1's buffer nor suppresses reply 1's later `text.delta` / `text.done`.
- Any future Gateway fallback change must assert assistant reply ids are generated reply ids,
  never the inbound user request `messageId`.
