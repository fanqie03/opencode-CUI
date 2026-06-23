# Skill Abort 第三方助手终止接口调用 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gateway 收到 `abort_session` 时，通过 remoteProperty 配置异步调用第三方助手的终止接口（fire-and-forget），请求体与 question 接口同构。

**Architecture:** 在 `CloudAgentService.handleInvoke()` 的 abort 分支中，新增 `invokeRemoteAbortIfConfigured()` 前置调用。该方法通过 `resolveRemoteRoute()` 匹配 `type="abort"` 的 remoteProperty，构造与 question 接口同构的 `ObjectNode` 请求体，通过 `CompletableFuture.runAsync()` + 专用线程池异步发送 HTTP POST。失败仅打 WARN 日志，不回传 tool_error。

**Tech Stack:** Java 17+, Spring Boot, java.net.http.HttpClient, ThreadPoolTaskExecutor, JUnit 5 + Mockito

**设计文档:** `docs/superpowers/specs/2026-06-10-skill-abort-third-party-stop-design.md`

---

## 文件结构

| 文件 | 操作 | 职责 |
|---|---|---|
| `ai-gateway/src/main/java/.../config/CloudAgentConfig.java` | **创建** | 声明 `cloudAbortExecutor` Bean（ThreadPoolTaskExecutor） |
| `ai-gateway/src/main/resources/application.yml` | **修改** | 新增 `gateway.cloud.abort.thread-pool.*` 配置项 |
| `ai-gateway/src/main/java/.../service/CloudAgentService.java` | **修改** | 新增 abort 第三方调用逻辑（3 方法 + 3 字段 + abilityType 分支） |
| `ai-gateway/src/test/java/.../service/CloudAgentServiceTest.java` | **修改** | 新增 7 个测试覆盖 abort 第三方调用 |

---

### Task 1: 创建 CloudAgentConfig（线程池 Bean）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudAgentConfig.java`
- Modify: `ai-gateway/src/main/resources/application.yml:117-133`

- [ ] **Step 1: 在 application.yml 中新增线程池配置项**

在 `gateway.cloud` 块内（约第 133 行后）追加：

```yaml
    # abort 第三方终止接口异步线程池
    abort:
      thread-pool:
        core-pool-size: ${GATEWAY_CLOUD_ABORT_CORE_POOL_SIZE:2}
        max-pool-size: ${GATEWAY_CLOUD_ABORT_MAX_POOL_SIZE:10}
        queue-capacity: ${GATEWAY_CLOUD_ABORT_QUEUE_CAPACITY:100}
```

- [ ] **Step 2: 创建 CloudAgentConfig.java**

```java
package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 云端 Agent 配置（线程池等基础设施）。
 *
 * <p>为 abort 第三方终止接口调用提供专用线程池，
 * 与 Redis 监听线程池隔离，独立配置、独立生命周期。</p>
 */
@Slf4j
@Configuration
public class CloudAgentConfig {

    /** abort 异步线程池核心线程数 */
    @Value("${gateway.cloud.abort.thread-pool.core-pool-size:2}")
    private int abortCorePoolSize;

    /** abort 异步线程池最大线程数 */
    @Value("${gateway.cloud.abort.thread-pool.max-pool-size:10}")
    private int abortMaxPoolSize;

    /** abort 异步线程池队列容量 */
    @Value("${gateway.cloud.abort.thread-pool.queue-capacity:100}")
    private int abortQueueCapacity;

    /**
     * abort 第三方终止接口异步线程池。
     *
     * <p>使用 {@link ThreadPoolTaskExecutor}（对齐 RedisConfig 模式），
     * 由 Spring 管理生命周期（自动 shutdown）。</p>
     */
    @Bean(name = "cloudAbortExecutor")
    public ThreadPoolTaskExecutor cloudAbortExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(abortCorePoolSize);
        executor.setMaxPoolSize(abortMaxPoolSize);
        executor.setQueueCapacity(abortQueueCapacity);
        executor.setThreadNamePrefix("cloud-abort-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `cd ai-gateway; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudAgentConfig.java
git add ai-gateway/src/main/resources/application.yml
git commit -m "feat(gateway): add CloudAgentConfig with cloudAbortExecutor thread pool bean"
```


---

### Task 2: 修改 CloudAgentService — abilityType 映射

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java:298-300`

- [ ] **Step 1: 修改 abilityType() 方法**

将第 298-300 行的：

```java
private static String abilityType(String action) {
    return "chat".equals(action) ? "chat" : "question";
}
```

改为：

```java
private static String abilityType(String action) {
    return switch (action) {
        case "chat" -> "chat";
        case "abort_session" -> "abort";
        default -> "question"; // question_reply, permission_reply
    };
}
```

- [ ] **Step 2: 编译验证**

Run: `cd ai-gateway; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "feat(gateway): add abort_session -> abort abilityType mapping"
```

---

### Task 3: 修改 CloudAgentService — 新增字段与构造函数

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java:51-99`

- [ ] **Step 1: 新增 import 语句**

在文件头部 import 区域追加：

```java
import com.opencode.cui.gateway.service.cloud.CloudAuthService;
import org.springframework.beans.factory.annotation.Qualifier;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
```

- [ ] **Step 2: 新增字段声明**

在 `CloudAgentService` 类体中（约第 78 行 `activeStreamingConnections` 字段之后）追加：

```java
private final HttpClient httpClient;
private final Executor abortExecutor;
private final CloudAuthService cloudAuthService;
```

- [ ] **Step 3: 修改主 @Autowired 构造函数**

将第 81-99 行的构造函数改为：

```java
@Autowired
public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                         CloudRouteSwitchService cloudRouteSwitchService,
                         AssistantInstanceInfoService assistantInstanceInfoService,
                         CloudProtocolClient cloudProtocolClient,
                         WebHookExecutor webHookExecutor,
                         CloudTimeoutProperties timeoutProperties,
                         RedisMessageBroker redisMessageBroker,
                         ObjectMapper objectMapper,
                         CloudAuthService cloudAuthService,
                         @Qualifier("cloudAbortExecutor") Executor abortExecutor,
                         @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId) {
    this.sysConfigRouteProvider = sysConfigRouteProvider;
    this.cloudRouteSwitchService = cloudRouteSwitchService;
    this.assistantInstanceInfoService = assistantInstanceInfoService;
    this.cloudProtocolClient = cloudProtocolClient;
    this.webHookExecutor = webHookExecutor;
    this.timeoutProperties = timeoutProperties;
    this.redisMessageBroker = redisMessageBroker;
    this.objectMapper = objectMapper;
    this.cloudAuthService = cloudAuthService;
    this.abortExecutor = abortExecutor;
    this.gatewayInstanceId = gatewayInstanceId;
    this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
}
```

> **注意**：其余 3 个非 @Autowired 构造函数（101-124 行）保持不变，仅用于测试。新增字段在测试中通过 mock 注入。

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "feat(gateway): add httpClient, abortExecutor, cloudAuthService fields to CloudAgentService"
```


---

### Task 4: 修改 CloudAgentService — 新增 abort 调用逻辑

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java:155-158`

- [ ] **Step 1: 修改 handleInvoke() 的 abort 分支**

将第 155-158 行的：

```java
if (ACTION_ABORT_SESSION.equals(action)) {
    cancelStreamingConnection(invokeMessage, toolSessionId);
    return;
}
```

改为：

```java
if (ACTION_ABORT_SESSION.equals(action)) {
    // 异步调用第三方终止接口（fire-and-forget）
    invokeRemoteAbortIfConfigured(invokeMessage, toolSessionId, assistantAccount, businessTag);
    // 取消本地活跃 SSE/WS 连接
    cancelStreamingConnection(invokeMessage, toolSessionId);
    return;
}
```

- [ ] **Step 2: 新增 invokeRemoteAbortIfConfigured() 方法**

在 `CloudAgentService` 类体中（约第 516 行 `cancelStreamingConnection` 方法之后）追加：

```java
/**
 * 如果配置了 type=abort 的 remoteProperty，异步调用第三方终止接口。
 *
 * <p>fire-and-forget：使用 CompletableFuture + 专用线程池异步发送 HTTP POST，
 * 不阻塞 cancelStreamingConnection()。失败仅打 WARN 日志，不回传 tool_error。</p>
 */
private void invokeRemoteAbortIfConfigured(GatewayMessage invokeMessage,
                                           String toolSessionId,
                                           String assistantAccount,
                                           String businessTag) {
    RemoteRoute route = resolveRemoteRoute(assistantAccount, ACTION_ABORT_SESSION, businessTag);
    if (route == null) {
        log.debug("[CLOUD_AGENT] No remote abort route configured, skipping third-party stop call");
        return;
    }

    // 构造请求体（与 question 接口同构）
    ObjectNode body = buildAbortBody(invokeMessage, toolSessionId);

    // 异步发送（fire-and-forget）
    CompletableFuture.runAsync(() -> {
        try {
            sendAbortRequest(route, body, invokeMessage.getTraceId());
        } catch (Exception e) {
            log.warn("[CLOUD_AGENT] Async abort request failed: traceId={}, error={}",
                    invokeMessage.getTraceId(), e.getMessage());
        }
    }, abortExecutor);
}
```

- [ ] **Step 3: 新增 buildAbortBody() 方法**

在 `invokeRemoteAbortIfConfigured` 方法之后追加：

```java
/**
 * 构造终止请求体（与 question 接口同构）。
 *
 * <p>需求明确"只是接口地址变了，鉴权header和入参和原来的question接口一致"。
 * 因此不定义独立 DTO，而是构造与 cloudRequest 同构的 JSON 对象。</p>
 */
private ObjectNode buildAbortBody(GatewayMessage invokeMessage, String toolSessionId) {
    JsonNode payload = invokeMessage.getPayload();
    ObjectNode body = objectMapper.createObjectNode();

    body.put("type", "abort");
    body.put("assistantAccount", firstNonBlank(
            invokeMessage.getAssistantAccount(),
            textAt(payload, "assistantAccount"),
            textAt(payload, "partnerAccount")));
    body.put("sendUserAccount", firstNonBlank(
            invokeMessage.getUserId(), textAt(payload, "sendUserAccount")));
    body.put("topicId", toolSessionId);

    // 可选字段
    putIfText(body, payload, "imGroupId");
    putIfText(body, payload, "messageId");
    String clientLang = textAt(payload, "clientLang");
    body.put("clientLang", (clientLang != null && !clientLang.isBlank()) ? clientLang : "zh");
    putIfText(body, payload, "clientType");

    // extParameters：与 question 接口对齐
    ObjectNode extParams = objectMapper.createObjectNode();
    extParams.set("businessExtParam",
            (payload != null && payload.has("businessExtParam")
                    && payload.get("businessExtParam").isObject())
                    ? payload.get("businessExtParam") : objectMapper.createObjectNode());
    extParams.set("platformExtParam", objectMapper.createObjectNode());
    body.set("extParameters", extParams);

    return body;
}
```

- [ ] **Step 4: 新增 sendAbortRequest() 方法**

在 `buildAbortBody` 方法之后追加：

```java
/**
 * 发送终止 HTTP POST 请求到第三方助手。
 *
 * <p>内联发送而非复用 WebHookExecutor：abort 是 fire-and-forget 旁路通知，
 * 失败不回传 tool_error；WebHookExecutor 失败会回调 onRelay 产生 tool_error，
 * 语义不匹配。</p>
 */
private void sendAbortRequest(RemoteRoute route, ObjectNode body, String traceId) {
    String bodyStr = objectMapper.writeValueAsString(body);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(route.channelAddress()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .timeout(Duration.ofSeconds(10));
    if (traceId != null) {
        builder.header("X-Trace-Id", traceId);
    }
    cloudAuthService.applyAuth(builder, route.appId(), route.authType());

    HttpResponse<String> resp = httpClient.send(builder.build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 200) {
        log.info("[CLOUD_AGENT] Abort request sent successfully: url={}, status={}, traceId={}",
                route.channelAddress(), resp.statusCode(), traceId);
    } else {
        log.warn("[CLOUD_AGENT] Abort request returned non-2xx: url={}, status={}, body={}, traceId={}",
                route.channelAddress(), resp.statusCode(), resp.body(), traceId);
    }
}
```

- [ ] **Step 5: 新增 putIfText() 辅助方法**

在 `textAt` 方法（约第 716 行）之后追加：

```java
/**
 * 如果 payload 中指定字段存在且为非空文本，则写入目标 ObjectNode。
 */
private static void putIfText(ObjectNode target, JsonNode source, String fieldName) {
    String value = textAt(source, fieldName);
    if (value != null) {
        target.put(fieldName, value);
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `cd ai-gateway; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "feat(gateway): add third-party abort call via remoteProperty in CloudAgentService"
```


---

### Task 5: 修改 CloudAgentServiceTest — 新增 mock 字段与构造函数适配

**Files:**
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java:1-100`

- [ ] **Step 1: 新增 import**

在 import 区域追加：

```java
import com.opencode.cui.gateway.service.cloud.CloudAuthService;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executor;
```

- [ ] **Step 2: 新增 @Mock 字段**

在现有 `@Mock` 声明区域（约第 72 行 `onRelay` 之后）追加：

```java
@Mock
private HttpClient httpClient;
@Mock
private CloudAuthService cloudAuthService;
@Mock
private Executor abortExecutor;
```

- [ ] **Step 3: 修改 setUp() 构造函数调用**

将 setUp() 中（第 93-96 行）的：

```java
cloudAgentService = new CloudAgentService(
        sysConfigRouteProvider, cloudRouteSwitchService, assistantInstanceInfoService,
        cloudProtocolClient, webHookExecutor, cloudTimeoutProperties,
        redisMessageBroker, objectMapper, "gw-local");
```

改为：

```java
cloudAgentService = new CloudAgentService(
        sysConfigRouteProvider, cloudRouteSwitchService, assistantInstanceInfoService,
        cloudProtocolClient, webHookExecutor, cloudTimeoutProperties,
        redisMessageBroker, objectMapper, cloudAuthService, abortExecutor, "gw-local");
```

- [ ] **Step 4: 修改 abortExecutor mock 行为**

在 setUp() 方法末尾追加（使 `CompletableFuture.runAsync` 同步执行，便于测试验证）：

```java
// 让 abortExecutor 同步执行提交的任务（便于测试验证）
doAnswer(invocation -> {
    Runnable task = invocation.getArgument(0);
    task.run();
    return null;
}).when(abortExecutor).execute(any(Runnable.class));
```

- [ ] **Step 5: 运行现有测试确保未破坏**

Run: `cd ai-gateway; mvn test -pl . -Dtest=CloudAgentServiceTest -q`
Expected: Tests run: all existing tests PASS

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "test(gateway): add httpClient, cloudAuthService, abortExecutor mocks to CloudAgentServiceTest"
```

---

### Task 6: 新增 abort 第三方调用测试（路由匹配 + 请求体）

**Files:**
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java:424`

- [ ] **Step 1: 在 AbortSessionTests 中新增测试 1 — abilityType 映射**

在 `AbortSessionTests` 类体中（约第 424 行 `}` 之前）追加：

```java
@Test
@DisplayName("abilityType(abort_session) returns abort")
void abilityType_abortSession_returnsAbort() {
    // abilityType is package-private static, tested via resolveRemoteRoute behavior
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

    GatewayMessage invoke = buildRemoteInvoke("abort_session");
    cloudAgentService.handleInvoke(invoke, onRelay);

    // resolveRemoteRoute matched type=abort -> route found -> sendAbortRequest called
    // (verify via httpClient.send was invoked)
    verify(httpClient, atLeastOnce()).send(any(HttpRequest.class),
            eq(HttpResponse.BodyHandlers.ofString()));
}
```

- [ ] **Step 2: 新增测试 2 — 无 abort 配置时跳过**

```java
@Test
@DisplayName("abort_session without abort remoteProperty skips third-party call")
void handleInvoke_abortSessionWithoutAbortRemoteProperty_skipsThirdPartyCall() {
    // remoteProperty has chat and question but NOT abort
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat"));

    GatewayMessage invoke = buildRemoteInvoke("abort_session");
    cloudAgentService.handleInvoke(invoke, onRelay);

    // No HTTP call made
    verifyNoInteractions(httpClient);
    verifyNoInteractions(onRelay);
}
```

- [ ] **Step 3: 新增测试 3 — 请求体字段映射正确**

```java
@Test
@DisplayName("abort_session builds correct request body matching question interface")
void handleInvoke_abortSession_buildsCorrectRequestBody() throws Exception {
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

    HttpResponse<String> mockResp = mock(HttpResponse.class);
    when(mockResp.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResp);

    GatewayMessage invoke = buildRemoteInvoke("abort_session");
    cloudAgentService.handleInvoke(invoke, onRelay);

    ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(reqCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));

    HttpRequest req = reqCaptor.getValue();
    assertEquals("POST", req.method());
    assertTrue(req.uri().toString().contains("https://remote.example.com/stop"));

    // Parse body and verify fields
    String body = req.bodyPublisher()
            .map(p -> {
                try {
                    var baos = new java.io.ByteArrayOutputStream();
                    p.subscribe(java.nio.channels.Channels.newChannel(baos));
                    return baos.toString();
                } catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElse("{}");
    ObjectNode bodyJson = (ObjectNode) objectMapper.readTree(body);

    assertEquals("abort", bodyJson.path("type").asText());
    assertEquals("tool-session-001", bodyJson.path("topicId").asText());
    assertEquals("bot-001", bodyJson.path("assistantAccount").asText());
    assertEquals("user-001", bodyJson.path("sendUserAccount").asText());
    assertEquals("zh", bodyJson.path("clientLang").asText());
    assertTrue(bodyJson.has("extParameters"));
    assertTrue(bodyJson.path("extParameters").has("businessExtParam"));
    assertTrue(bodyJson.path("extParameters").has("platformExtParam"));
}
```

- [ ] **Step 4: 运行新增测试**

Run: `cd ai-gateway; mvn test -pl . -Dtest=CloudAgentServiceTest -q`
Expected: 3 new tests PASS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "test(gateway): add abort third-party call tests - routing and body mapping"
```


---

### Task 7: 新增 abort 第三方调用测试（错误处理 + 非阻塞）

**Files:**
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java:424`

- [ ] **Step 1: 新增测试 4 — 第三方返回 500 不影响本地流程**

```java
@Test
@DisplayName("abort_session third-party returns 500 does not affect local cancel")
void handleInvoke_abortSession_thirdParty500_doesNotAffectLocalCancel() throws Exception {
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

    HttpResponse<String> mockResp = mock(HttpResponse.class);
    when(mockResp.statusCode()).thenReturn(500);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResp);

    // Also set up an active streaming connection to verify it gets cancelled
    when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
            .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

    doAnswer(invocation -> {
        CloudConnectionContext context = invocation.getArgument(1);
        CloudConnectionHandle handle = context.getConnectionHandle();

        // Send abort_session (with assistantAccount so remoteProperty is checked)
        GatewayMessage abort = buildRemoteInvoke("abort_session");
        cloudAgentService.handleInvoke(abort, onRelay);

        // Local stream should still be cancelled despite third-party 500
        assertTrue(handle.isCancelled());
        return null;
    }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

    cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

    // Verify HTTP was attempted
    verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    // No tool_error relayed
    verifyNoInteractions(onRelay);
}
```

- [ ] **Step 2: 新增测试 5 — 异步发送不阻塞 cancelStreamingConnection**

```java
@Test
@DisplayName("abort_session third-party call is async and does not block local cancel")
void handleInvoke_abortSession_thirdPartyCallIsAsync() throws Exception {
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

    // Simulate slow third-party response (but executor runs sync in test)
    HttpResponse<String> mockResp = mock(HttpResponse.class);
    when(mockResp.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResp);

    when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
            .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

    doAnswer(invocation -> {
        CloudConnectionContext context = invocation.getArgument(1);
        CloudConnectionHandle handle = context.getConnectionHandle();

        GatewayMessage abort = buildRemoteInvoke("abort_session");
        cloudAgentService.handleInvoke(abort, onRelay);

        // After handleInvoke returns, both cancel AND HTTP should have happened
        assertTrue(handle.isCancelled());
        verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
        return null;
    }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

    cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

    verifyNoInteractions(onRelay);
}
```

- [ ] **Step 3: 新增测试 6 — HTTP 发送异常不影响本地流程**

```java
@Test
@DisplayName("abort_session third-party HTTP exception does not affect local cancel")
void handleInvoke_abortSession_httpException_doesNotAffectLocalCancel() throws Exception {
    when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
            .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenThrow(new java.io.IOException("Connection refused"));

    when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
            .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

    doAnswer(invocation -> {
        CloudConnectionContext context = invocation.getArgument(1);
        CloudConnectionHandle handle = context.getConnectionHandle();

        GatewayMessage abort = buildRemoteInvoke("abort_session");
        cloudAgentService.handleInvoke(abort, onRelay);

        // Local stream still cancelled despite HTTP exception
        assertTrue(handle.isCancelled());
        return null;
    }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

    cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

    verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    verifyNoInteractions(onRelay);
}
```

- [ ] **Step 4: 新增测试 7 — 无 assistantAccount 时跳过**

```java
@Test
@DisplayName("abort_session without assistantAccount skips third-party call")
void handleInvoke_abortSession_withoutAssistantAccount_skipsThirdPartyCall() {
    // buildInvoke (not buildRemoteInvoke) has no assistantAccount
    cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

    verifyNoInteractions(httpClient, assistantInstanceInfoService);
}
```

- [ ] **Step 5: 运行所有测试**

Run: `cd ai-gateway; mvn test -pl . -Dtest=CloudAgentServiceTest -q`
Expected: ALL tests PASS (existing + 7 new)

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "test(gateway): add abort third-party error handling and async tests"
```

---

### Task 8: 全量回归验证

**Files:**
- (none — verification only)

- [ ] **Step 1: 运行 ai-gateway 全量测试**

Run: `cd ai-gateway; mvn test -q`
Expected: ALL tests PASS, BUILD SUCCESS

- [ ] **Step 2: 检查 GitNexus 变更范围**

Run: `npx gitnexus detect_changes --scope all`
Expected: 仅 `CloudAgentConfig.java`（新增）、`CloudAgentService.java`（修改）、`CloudAgentServiceTest.java`（修改）、`application.yml`（修改）

- [ ] **Step 3: 验证未引入 lint 错误**

Run: `cd ai-gateway; mvn compile -q`
Expected: BUILD SUCCESS, zero warnings
