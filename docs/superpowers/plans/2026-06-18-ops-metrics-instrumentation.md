# 运维埋码 (Ops Metrics Instrumentation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 skill-server 和 ai-gateway 接入 Prometheus/Micrometer 指标上报 + 慧眼告警业务标识 + TTFT Welink 双上报 + 消息生命周期抽象。

**Architecture:** Micrometer 作为指标 facade，通过 Spring Boot Actuator 的 `/actuator/prometheus` 端点暴露。第三方接口埋码用侵入式注入 `ApiCallMetricsService.recordApiCall`（13 个接入点）。流式效率指标通过 `MessageTurnLifecycle` 编排器委托给 `ChatStreamMetricsService`（Caffeine Cache 按 messageId 维护状态）+ `WelinkTelemetryReporter`（TTFT 双上报）。慧眼告警通过 MDC `businessDomain` 字段（接口级粒度）实现日志可搜索。

**Tech Stack:** Java 21, Spring Boot 3.4.6, Micrometer, Prometheus Registry, Caffeine Cache, Log4j2, MDC, JUnit 5 + Mockito

**Spec:** `.trellis/human-docs/001运维埋码-需求分析与设计.md` + `.trellis/human-docs/001运维埋码-慧眼告警语法清单.md`

---

## File Structure

### skill-server 新增文件

| 文件 | 职责 |
|------|------|
| `telemetry/metrics/MetricServiceEnum.java` | 第三方服务枚举（13 个接口级值，id + comment） |
| `telemetry/metrics/ApiCallMetricsService.java` | `recordApiCall` 实现（URL 去 query + 3 counter + 1 timer + 失败 ERROR 日志） |
| `telemetry/metrics/ApiMetricsInterceptor.java` | 对外 API 拦截器（preHandle/postHandle 统计 cost） |
| `telemetry/metrics/ChatStreamMetricsService.java` | 流式效率指标（Caffeine Cache + 4 个 onStream* 方法） |
| `telemetry/metrics/MessageTurnLifecycle.java` | 消息轮次生命周期编排器（委托 ChatStreamMetricsService + WelinkTelemetryReporter） |
| `telemetry/chat/ChatFirstTokenTelemetryEvent.java` | TTFT 上报 Welink 的事件（implements TelemetryEvent） |

### skill-server 修改文件

| 文件 | 改动 |
|------|------|
| `pom.xml` | 新增 actuator + micrometer-registry-prometheus + caffeine |
| `application.yml` | 新增 management 配置段 + skill.metrics.stream 配置 |
| `logging/MdcConstants.java` | 新增 BUSINESS_DOMAIN 常量 + ALL_KEYS |
| `logging/MdcHelper.java` | 新增 putBusinessDomain |
| `src/main/resources/log4j2-spring.xml` | pattern 新增 `[%X{businessDomain}]` |
| `service/ImOutboundService.java` | 注入 ApiCallMetricsService + businessDomain MDC |
| `service/ImMessageService.java` | 同上 |
| `service/GatewayApiClient.java` | 同上 |
| `service/GatewayRelayService.java` | 同上 |
| `service/AssistantInfoService.java` | 同上 |
| `service/AssistantInstanceInfoService.java` | 同上 |
| `service/AssistantIdResolverService.java` | 同上 |
| `telemetry/client/WelinkTelemetryClient.java` | 同上 |
| `service/SkillMessageFlowService.java` | 注入 MessageTurnLifecycle，onTurnStart |
| `ws/GatewayMessageRouter.java` | 注入 MessageTurnLifecycle，onFirstToken/onToken/onTurnEnd |
| `ws/GatewayWSClient.java` | 注入 MeterRegistry，WS 连接数 Gauge + Counter |
| `config/WebMvcConfig.java` | 注册 ApiMetricsInterceptor |

### ai-gateway 修改文件

| 文件 | 改动 |
|------|------|
| `pom.xml` | 新增 actuator + micrometer-registry-prometheus |
| `application.yml` | 新增 management 配置段 |
| `service/SkillRelayService.java` | 注入 MeterRegistry，WS 连接数 Gauge + Counter |

---

## Task 1: skill-server 依赖 + 配置

**Files:**
- Modify: `skill-server/pom.xml`
- Modify: `skill-server/src/main/resources/application.yml`

- [ ] **Step 1: 新增 pom.xml 依赖**

在 `skill-server/pom.xml` 的 `<dependencies>` 中（`spring-boot-starter-test` 之前）新增：

```xml
<!-- Actuator + Prometheus metrics -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<!-- Caffeine cache for stream metrics session state -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: 新增 application.yml 配置**

在 `skill-server/src/main/resources/application.yml` 顶层新增：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
      base-path: /actuator
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: skill-server

skill:
  metrics:
    stream:
      max-sessions: ${SKILL_METRICS_STREAM_MAX_SESSIONS:10000}
      session-ttl: ${SKILL_METRICS_STREAM_SESSION_TTL:30m}
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/pom.xml skill-server/src/main/resources/application.yml
git commit -m "feat(metrics): add actuator + prometheus + caffeine deps and config"
```

---

## Task 2: MetricServiceEnum

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MetricServiceEnum.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/MetricServiceEnumTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricServiceEnumTest {

    @Test
    void enumHas13Values() {
        assertEquals(13, MetricServiceEnum.values().length);
    }

    @Test
    void imGroupChatHasCorrectIdAndComment() {
        assertEquals("im_group_chat", MetricServiceEnum.IM_GROUP_CHAT.getId());
        assertEquals("群聊消息发送", MetricServiceEnum.IM_GROUP_CHAT.getComment());
    }

    @Test
    void gatewayWsInvokeHasCorrectIdAndComment() {
        assertEquals("gateway_ws_invoke", MetricServiceEnum.GATEWAY_WS_INVOKE.getId());
        assertEquals("Gateway WS invoke 指令", MetricServiceEnum.GATEWAY_WS_INVOKE.getComment());
    }

    @Test
    void telemetryWelinkUploadHasCorrectIdAndComment() {
        assertEquals("telemetry_welink_upload", MetricServiceEnum.TELEMETRY_WELINK_UPLOAD.getId());
        assertEquals("WeLink 埋码上报", MetricServiceEnum.TELEMETRY_WELINK_UPLOAD.getComment());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=MetricServiceEnumTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 MetricServiceEnum**

```java
package com.opencode.cui.skill.telemetry.metrics;

import lombok.Getter;

/**
 * 第三方服务枚举 — 按具体接口枚举（非服务粗粒度）。
 * id 为接口级 snake_case 标识，同时用作 Prometheus tag 和慧眼告警 MDC businessDomain 值。
 */
@Getter
public enum MetricServiceEnum {
    // IM（3 个端点）
    IM_GROUP_CHAT("im_group_chat", "群聊消息发送"),
    IM_DIRECT_CHAT("im_direct_chat", "单聊消息发送"),
    IM_MESSAGE_SEND("im_message_send", "IM 消息发送"),

    // Gateway（6 个端点：3 REST + 3 WS）
    GATEWAY_AGENTS_LIST("gateway_agents_list", "查询在线 Agent 列表"),
    GATEWAY_AGENTS_BY_AK("gateway_agents_by_ak", "按 AK 查询 Agent"),
    GATEWAY_AGENT_AVAILABILITY("gateway_agent_availability", "查询 Agent 可及性"),
    GATEWAY_WS_INVOKE("gateway_ws_invoke", "Gateway WS invoke 指令"),
    GATEWAY_WS_ROUTE_CONFIRM("gateway_ws_route_confirm", "Gateway WS 路由确认"),
    GATEWAY_WS_ROUTE_REJECT("gateway_ws_route_reject", "Gateway WS 路由拒绝"),

    // 业务中心（3 个端点）
    BUSINESS_CENTER_ASSISTANT_INFO("business_center_assistant_info", "查询助手信息"),
    BUSINESS_CENTER_INSTANCE_QUERY("business_center_instance_query", "查询助手实例"),
    BUSINESS_CENTER_PERSONA_QUERY("business_center_persona_query", "查询 Persona"),

    // 埋码上报（1 个端点）
    TELEMETRY_WELINK_UPLOAD("telemetry_welink_upload", "WeLink 埋码上报");

    private final String id;
    private final String comment;

    MetricServiceEnum(String id, String comment) {
        this.id = id;
        this.comment = comment;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=MetricServiceEnumTest -q`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MetricServiceEnum.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/MetricServiceEnumTest.java
git commit -m "feat(metrics): add MetricServiceEnum with 13 interface-level values"
```

---

## Task 3: ApiCallMetricsService

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiCallMetricsService.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ApiCallMetricsServiceTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiCallMetricsServiceTest {

    private MeterRegistry registry;
    private ApiCallMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ApiCallMetricsService(registry);
    }

    @Test
    void recordApiCallSuccess_incrementsTotalAndSuccessCounters() {
        service.recordApiCall(MetricServiceEnum.IM_GROUP_CHAT, "/v1/chat/app-group-chat", true, 150);

        Counter total = registry.find("external_api_call_total").tag("serviceId", "im_group_chat").counter();
        Counter success = registry.find("external_api_call_success_total").tag("serviceId", "im_group_chat").counter();
        Counter failure = registry.find("external_api_call_failure_total").tag("serviceId", "im_group_chat").counter();

        assertNotNull(total);
        assertEquals(1.0, total.count());
        assertNotNull(success);
        assertEquals(1.0, success.count());
        assertNull(failure);
    }

    @Test
    void recordApiCallFailure_incrementsTotalAndFailureCounters() {
        service.recordApiCall(MetricServiceEnum.GATEWAY_WS_INVOKE, "ws://gateway/ws/skill", false, 50);

        Counter total = registry.find("external_api_call_total").tag("serviceId", "gateway_ws_invoke").counter();
        Counter failure = registry.find("external_api_call_failure_total").tag("serviceId", "gateway_ws_invoke").counter();

        assertNotNull(total);
        assertEquals(1.0, total.count());
        assertNotNull(failure);
        assertEquals(1.0, failure.count());
    }

    @Test
    void recordApiCall_recordsDurationTimer() {
        service.recordApiCall(MetricServiceEnum.IM_MESSAGE_SEND, "/messages/send", true, 200);

        Timer timer = registry.find("external_api_call_duration_seconds").tag("serviceId", "im_message_send").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordApiCall_stripsQueryParamsFromUrl() {
        service.recordApiCall(MetricServiceEnum.GATEWAY_AGENTS_LIST,
                "/api/gateway/agents?userId=user123&ak=ak456", true, 100);

        Counter total = registry.find("external_api_call_total")
                .tag("url", "/api/gateway/agents").counter();
        assertNotNull(total);
        assertEquals(1.0, total.count());
    }

    @Test
    void recordApiCall_preservesPathTemplateInUrl() {
        service.recordApiCall(MetricServiceEnum.BUSINESS_CENTER_INSTANCE_QUERY,
                "/instance/query?partnerAccount={account}", true, 80);

        Counter total = registry.find("external_api_call_total")
                .tag("url", "/instance/query").counter();
        assertNotNull(total);
    }

    @Test
    void recordApiCall_includesServiceCommentTag() {
        service.recordApiCall(MetricServiceEnum.TELEMETRY_WELINK_UPLOAD, "/producer", true, 30);

        Counter total = registry.find("external_api_call_total")
                .tag("serviceComment", "WeLink 埋码上报").counter();
        assertNotNull(total);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=ApiCallMetricsServiceTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 ApiCallMetricsService**

```java
package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 第三方接口调用埋码服务。
 * 记录 3 个 Counter + 1 个 Timer，URL 去 query 参数，失败时写 [EXT_CALL] ERROR 日志。
 */
@Slf4j
@Service
public class ApiCallMetricsService {

    private final MeterRegistry meterRegistry;

    public ApiCallMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordApiCall(MetricServiceEnum service, String url, boolean success, long durationMs) {
        // 去 query 参数
        String cleanUrl = url;
        int q = cleanUrl.indexOf('?');
        if (q >= 0) {
            cleanUrl = cleanUrl.substring(0, q);
        }

        Tags tags = Tags.of(
            "serviceId", service.getId(),
            "serviceComment", service.getComment(),
            "url", cleanUrl
        );

        meterRegistry.counter("external_api_call_total", tags).increment();
        meterRegistry.counter(success
            ? "external_api_call_success_total"
            : "external_api_call_failure_total", tags).increment();
        meterRegistry.timer("external_api_call_duration_seconds", tags)
            .record(durationMs, TimeUnit.MILLISECONDS);

        if (!success) {
            log.error("[EXT_CALL] {} failed: durationMs={}", service.getId(), durationMs);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ApiCallMetricsServiceTest -q`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiCallMetricsService.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ApiCallMetricsServiceTest.java
git commit -m "feat(metrics): add ApiCallMetricsService with URL query stripping and EXT_CALL logging"
```

---

## Task 4: MDC businessDomain + log4j2 pattern (慧眼告警 #3)

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/logging/MdcHelper.java`
- Modify: `skill-server/src/main/resources/log4j2-spring.xml`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/logging/MdcBusinessDomainTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcBusinessDomainTest {

    @AfterEach
    void clearMdc() {
        MdcHelper.clearAll();
    }

    @Test
    void putBusinessDomain_setsValue() {
        MdcHelper.putBusinessDomain("im_group_chat");
        assertEquals("im_group_chat", MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void putBusinessDomain_nullRemoves() {
        MDC.put(MdcConstants.BUSINESS_DOMAIN, "im_group_chat");
        MdcHelper.putBusinessDomain(null);
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void putBusinessDomain_blankRemoves() {
        MDC.put(MdcConstants.BUSINESS_DOMAIN, "im_group_chat");
        MdcHelper.putBusinessDomain("  ");
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void clearAll_clearsBusinessDomain() {
        MdcHelper.putBusinessDomain("gateway_ws_invoke");
        MdcHelper.clearAll();
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void businessDomainIsInAllKeys() {
        assertTrue(MdcConstants.ALL_KEYS.contains(MdcConstants.BUSINESS_DOMAIN));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=MdcBusinessDomainTest -q`
Expected: FAIL (BUSINESS_DOMAIN constant not found)

- [ ] **Step 3: 修改 MdcConstants.java — 新增 BUSINESS_DOMAIN**

在 `SCENARIO` 常量后新增，并加入 `ALL_KEYS`：

```java
/** 业务域标识（接口级 serviceId，如 im_group_chat、gateway_ws_invoke）— 慧眼告警用 */
public static final String BUSINESS_DOMAIN = "businessDomain";

/** 所有自定义 MDC key 列表，用于批量清理 */
public static final List<String> ALL_KEYS = List.of(
        TRACE_ID, SESSION_ID, AK, USER_ID, SCENARIO, BUSINESS_DOMAIN
);
```

- [ ] **Step 4: 修改 MdcHelper.java — 新增 putBusinessDomain**

在 `putScenario` 方法后新增：

```java
public static void putBusinessDomain(String value) {
    safePut(MdcConstants.BUSINESS_DOMAIN, value);
}
```

- [ ] **Step 5: 修改 log4j2-spring.xml — pattern 新增占位符**

找到 pattern 中的 `[%X{scenario}]` 改为 `[%X{scenario}] [%X{businessDomain}]`。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=MdcBusinessDomainTest -q`
Expected: PASS (5 tests)

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java skill-server/src/main/java/com/opencode/cui/skill/logging/MdcHelper.java skill-server/src/main/resources/log4j2-spring.xml skill-server/src/test/java/com/opencode/cui/skill/logging/MdcBusinessDomainTest.java
git commit -m "feat(metrics): add MDC businessDomain for Huiyan alerting (#3)"
```

---

## Task 5: ApiMetricsInterceptor (对外 API 拦截器)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiMetricsInterceptor.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ApiMetricsInterceptorTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiMetricsInterceptorTest {

    private MeterRegistry registry;
    private ApiMetricsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new ApiMetricsInterceptor(registry);
    }

    @Test
    void postHandle_recordsTimerWithUrlTag() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skill/sessions/123/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        Thread.sleep(10);
        interceptor.postHandle(request, response, new Object(), null);

        Timer timer = registry.find("common_interface_duration_seconds").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void preHandle_setsStartTimeAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertNotNull(request.getAttribute("metrics.startTime"));
        assertTrue((long) request.getAttribute("metrics.startTime") > 0);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=ApiMetricsInterceptorTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 ApiMetricsInterceptor**

```java
package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.concurrent.TimeUnit;

@Component
public class ApiMetricsInterceptor implements HandlerInterceptor {

    private final MeterRegistry meterRegistry;

    public ApiMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("metrics.startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        Object startObj = request.getAttribute("metrics.startTime");
        if (startObj == null) {
            return;
        }
        long startTime = (long) startObj;
        long cost = System.currentTimeMillis() - startTime;
        String url = request.getRequestURI();

        meterRegistry.timer("common_interface_duration_seconds",
                "common_interface_url", url)
            .record(cost, TimeUnit.MILLISECONDS);
    }
}
```

- [ ] **Step 4: 注册拦截器到 WebMvcConfig**

在 `WebMvcConfig` 中注入 `ApiMetricsInterceptor` 并在 `addInterceptors` 中注册。先读取 `WebMvcConfig.java` 确认现有构造注入模式，按现有风格添加：

```java
private final ApiMetricsInterceptor apiMetricsInterceptor;

// 在 addInterceptors 方法中新增：
registry.addInterceptor(apiMetricsInterceptor).addPathPatterns("/api/**");
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ApiMetricsInterceptorTest -q`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiMetricsInterceptor.java skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ApiMetricsInterceptorTest.java
git commit -m "feat(metrics): add ApiMetricsInterceptor for inbound API duration tracking"
```

---

## Task 6: ChatStreamMetricsService (流式效率指标)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsService.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsServiceTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamMetricsServiceTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ChatStreamMetricsService(registry, 10000, Duration.ofMinutes(30));
    }

    @Test
    void onFirstToken_recordsTtftTimer() {
        service.onStreamStart("msg-1", "brain-A");
        sleep(10);
        service.onFirstToken("msg-1", "brain-A");

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer();
        assertNotNull(ttft);
        assertEquals(1, ttft.count());
    }

    @Test
    void onStreamEnd_recordsLatencyAndTps() {
        service.onStreamStart("msg-2", "brain-B");
        sleep(5);
        service.onFirstToken("msg-2", "brain-B");
        service.onToken("msg-2", "brain-B");
        service.onToken("msg-2", "brain-B");
        sleep(5);
        service.onStreamEnd("msg-2", "brain-B");

        Timer latency = registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer();
        assertNotNull(latency);
        assertEquals(1, latency.count());

        DistributionSummary tps = registry.find("chat_stream_tokens_per_second").tag("brain_tag", "brain-B").summary();
        assertNotNull(tps);
        assertEquals(1, tps.count());
    }

    @Test
    void nullMessageId_skipsAllRecording() {
        service.onStreamStart(null, "brain-A");
        service.onFirstToken(null, "brain-A");
        service.onToken(null, "brain-A");
        service.onStreamEnd(null, "brain-A");

        assertEquals(0, registry.getMeters().size());
    }

    @Test
    void missingBrainTag_usesUnknownFallback() {
        service.onStreamStart("msg-3", null);
        sleep(5);
        service.onFirstToken("msg-3", null);

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "UNKNOWN").timer();
        assertNotNull(ttft);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=ChatStreamMetricsServiceTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 ChatStreamMetricsService**

```java
package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式对话效率指标服务。
 * 按 messageId 维护每轮问答状态（Caffeine Cache），记录 TTFT / Latency / TPS。
 * messageId 为 null 时直接 return。brainTag 不存在则用 UNKNOWN 兜底。
 */
@Slf4j
@Service
public class ChatStreamMetricsService {

    private final MeterRegistry meterRegistry;
    private final Cache<String, Long> sessionStartTimes;
    private final Cache<String, Long> firstTokenTimestamps;
    private final Cache<String, AtomicInteger> tokenCounts;

    public ChatStreamMetricsService(MeterRegistry meterRegistry, long maxSessions, Duration sessionTtl) {
        this.meterRegistry = meterRegistry;
        this.sessionStartTimes = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        this.firstTokenTimestamps = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        this.tokenCounts = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
    }

    public void onStreamStart(String messageId, String brainTag) {
        if (messageId == null) return;
        sessionStartTimes.put(messageId, System.currentTimeMillis());
    }

    public void onFirstToken(String messageId, String brainTag) {
        if (messageId == null) return;
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) return;
        long now = System.currentTimeMillis();
        firstTokenTimestamps.put(messageId, now);
        long ttft = now - startTime;
        String tag = resolveBrainTag(brainTag);
        meterRegistry.timer("chat_stream_ttft_seconds", Tags.of("brain_tag", tag))
                .record(ttft, TimeUnit.MILLISECONDS);
    }

    public void onToken(String messageId, String brainTag) {
        if (messageId == null) return;
        AtomicInteger count = tokenCounts.get(messageId, k -> new AtomicInteger(0));
        count.incrementAndGet();
    }

    public void onStreamEnd(String messageId, String brainTag) {
        if (messageId == null) return;
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) return;
        long now = System.currentTimeMillis();
        long latency = now - startTime;
        String tag = resolveBrainTag(brainTag);

        meterRegistry.timer("chat_stream_latency_seconds", Tags.of("brain_tag", tag))
                .record(latency, TimeUnit.MILLISECONDS);

        AtomicInteger tokenCount = tokenCounts.getIfPresent(messageId);
        int tokens = tokenCount != null ? tokenCount.get() : 0;
        if (latency > 0 && tokens > 0) {
            double tps = (tokens * 1000.0) / latency;
            meterRegistry.summary("chat_stream_tokens_per_second", Tags.of("brain_tag", tag))
                    .record(tps);
        }

        sessionStartTimes.invalidate(messageId);
        firstTokenTimestamps.invalidate(messageId);
        tokenCounts.invalidate(messageId);
    }

    private String resolveBrainTag(String brainTag) {
        return (brainTag != null && !brainTag.isBlank()) ? brainTag : "UNKNOWN";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ChatStreamMetricsServiceTest -q`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsService.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsServiceTest.java
git commit -m "feat(metrics): add ChatStreamMetricsService with Caffeine cache for TTFT/Latency/TPS"
```

---

## Task 7: ChatFirstTokenTelemetryEvent (TTFT 上报 Welink #5)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/chat/ChatFirstTokenTelemetryEvent.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/chat/ChatFirstTokenTelemetryEventTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatFirstTokenTelemetryEventTest {

    private final ChatFirstTokenTelemetryEvent event = new ChatFirstTokenTelemetryEvent(
            "sess-1", "assistant-1", "brain-A", "msg-1");

    @Test
    void eventId_isSkillChatFirstToken() {
        assertEquals("skill_chat_first_token", event.eventId());
    }

    @Test
    void eventLabel_isFirstTokenArrived() {
        assertEquals("首token到达", event.eventLabel());
    }

    @Test
    void userId_returnsAssistantAccount() {
        assertEquals("assistant-1", event.userId());
    }

    @Test
    void sessionId_returnsSessionId() {
        assertEquals("sess-1", event.sessionId());
    }

    @Test
    void extendData_includesBusinessTagAndMessageId() {
        Map<String, Object> data = event.extendData();
        assertEquals("brain-A", data.get("businessTag"));
        assertEquals("msg-1", data.get("messageId"));
        assertNotNull(data.get("ttftReportedAt"));
    }

    @Test
    void nullBusinessTag_usesUnknownFallback() {
        ChatFirstTokenTelemetryEvent e = new ChatFirstTokenTelemetryEvent(
                "sess-1", "assistant-1", null, "msg-1");
        assertEquals("UNKNOWN", e.extendData().get("businessTag"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=ChatFirstTokenTelemetryEventTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 ChatFirstTokenTelemetryEvent**

```java
package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.telemetry.core.TelemetryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首 token 到达事件（skill_chat_first_token）。
 * 实现 TelemetryEvent 接口，供 MessageTurnLifecycle.onFirstToken 上报到 WelinkTelemetryReporter。
 */
public record ChatFirstTokenTelemetryEvent(
        String sessionId,
        String assistantAccount,
        String businessTag,
        String messageId
) implements TelemetryEvent {

    @Override
    public String eventId() {
        return "skill_chat_first_token";
    }

    @Override
    public String eventLabel() {
        return "首token到达";
    }

    @Override
    public String userId() {
        return assistantAccount;
    }

    @Override
    public Map<String, Object> extendData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessTag", businessTag != null ? businessTag : "UNKNOWN");
        data.put("messageId", messageId);
        data.put("ttftReportedAt", System.currentTimeMillis());
        return data;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ChatFirstTokenTelemetryEventTest -q`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/chat/ChatFirstTokenTelemetryEvent.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/chat/ChatFirstTokenTelemetryEventTest.java
git commit -m "feat(metrics): add ChatFirstTokenTelemetryEvent for TTFT Welink reporting (#5)"
```

---

## Task 8: MessageTurnLifecycle (消息生命周期编排器 #6/#8)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MessageTurnLifecycle.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/MessageTurnLifecycleTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.*;

class MessageTurnLifecycleTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService streamMetrics;
    private WelinkTelemetryReporter welinkReporter;
    private MessageTurnLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        streamMetrics = new ChatStreamMetricsService(registry, 10000, Duration.ofMinutes(30));
        welinkReporter = mock(WelinkTelemetryReporter.class);
        when(welinkReporter.isEffectiveEnabled()).thenReturn(true);
        lifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, true);
    }

    @Test
    void onTurnStart_delegatesToStreamMetrics() {
        lifecycle.onTurnStart("msg-1", "brain-A", "sess-1", "user-1", "brain-A");
        // StreamMetrics should have startTime recorded
        // Verify by calling onFirstToken and checking TTFT timer exists
        lifecycle.onFirstToken("msg-1", "brain-A", "sess-1", "assistant-1");
        assertNotNull(registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer());
    }

    @Test
    void onFirstToken_reportsToWelinkWhenEnabled() {
        lifecycle.onTurnStart("msg-2", "brain-A", "sess-2", "user-2", "brain-A");
        lifecycle.onFirstToken("msg-2", "brain-A", "sess-2", "assistant-2");

        verify(welinkReporter).report(argThat(event ->
            event instanceof ChatFirstTokenTelemetryEvent
            && "skill_chat_first_token".equals(event.eventId())));
    }

    @Test
    void onFirstToken_skipsWelinkWhenDisabled() {
        MessageTurnLifecycle disabledLifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, false);
        disabledLifecycle.onTurnStart("msg-3", "brain-A", "sess-3", "user-3", "brain-A");
        disabledLifecycle.onFirstToken("msg-3", "brain-A", "sess-3", "assistant-3");

        verify(welinkReporter, never()).report(any());
    }

    @Test
    void onTurnEnd_delegatesToStreamMetrics() {
        lifecycle.onTurnStart("msg-4", "brain-B", "sess-4", "user-4", "brain-B");
        lifecycle.onFirstToken("msg-4", "brain-B", "sess-4", "assistant-4");
        lifecycle.onToken("msg-4", "brain-B");
        lifecycle.onTurnEnd("msg-4", "brain-B", "sess-4", "assistant-4");

        assertNotNull(registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer());
    }

    private static <T> T assertNotNull(T value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
        return value;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl skill-server test -Dtest=MessageTurnLifecycleTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 MessageTurnLifecycle**

```java
package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import org.springframework.stereotype.Component;

/**
 * 消息轮次生命周期编排器。
 * 委托给 ChatStreamMetricsService（Prometheus）+ WelinkTelemetryReporter（TTFT 双上报）。
 * 不接管现有 ChatRequestTelemetryEvent / ChatReplyTelemetryEvent，避免双重上报。
 */
@Component
public class MessageTurnLifecycle {

    private final ChatStreamMetricsService streamMetrics;
    private final WelinkTelemetryReporter welinkReporter;
    private final boolean welinkEnabled;

    public MessageTurnLifecycle(ChatStreamMetricsService streamMetrics,
                                WelinkTelemetryReporter welinkReporter,
                                boolean welinkEnabled) {
        this.streamMetrics = streamMetrics;
        this.welinkReporter = welinkReporter;
        this.welinkEnabled = welinkEnabled;
    }

    public void onTurnStart(String messageId, String brainTag, String sessionId,
                            String senderUserAccount, String businessTag) {
        streamMetrics.onStreamStart(messageId, brainTag);
    }

    public void onFirstToken(String messageId, String brainTag, String sessionId,
                             String assistantAccount) {
        streamMetrics.onFirstToken(messageId, brainTag);
        if (welinkEnabled) {
            welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                sessionId, assistantAccount, brainTag, messageId));
        }
    }

    public void onToken(String messageId, String brainTag) {
        streamMetrics.onToken(messageId, brainTag);
    }

    public void onTurnEnd(String messageId, String brainTag, String sessionId,
                          String assistantAccount) {
        streamMetrics.onStreamEnd(messageId, brainTag);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=MessageTurnLifecycleTest -q`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MessageTurnLifecycle.java skill-server/src/test/java/com/opencode/cui/skill/telemetry/metrics/MessageTurnLifecycleTest.java
git commit -m "feat(metrics): add MessageTurnLifecycle orchestrator (#6/#8) with TTFT Welink reporting"
```

---

## Task 9: 第三方调用点接入 — ApiCallMetricsService + businessDomain MDC (13 个接入点)

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImOutboundService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInstanceInfoService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/telemetry/client/WelinkTelemetryClient.java`

- [ ] **Step 1: 理解接入模式**

每个接入点遵循统一模式：
1. 构造注入 `ApiCallMetricsService`
2. 在外部调用前 `MdcHelper.putBusinessDomain(MetricServiceEnum.XXX.getId())`
3. 记录开始时间 `long start = System.currentTimeMillis()`
4. 执行原有调用，捕获 success/failure
5. 在 finally 块中 `metricsService.recordApiCall(enum, urlTemplate, success, durationMs)` + `MdcHelper.clearAll()`（或只清 businessDomain）

先读取每个目标文件确认现有构造注入模式和外部调用位置，然后逐个修改。

- [ ] **Step 2: 接入 ImOutboundService (2 个枚举)**

读取 `ImOutboundService.java`，在 `sendTextToIm` 方法中根据 targetType 区分群聊/单聊：

```java
// 构造注入
private final ApiCallMetricsService apiCallMetricsService;

// sendTextToIm 方法内，调用 restTemplate.postForEntity 前：
MetricServiceEnum metricEnum = isGroupChat
    ? MetricServiceEnum.IM_GROUP_CHAT
    : MetricServiceEnum.IM_DIRECT_CHAT;
MdcHelper.putBusinessDomain(metricEnum.getId());
long start = System.currentTimeMillis();
boolean success = false;
try {
    // 原有 restTemplate.postForEntity 调用
    success = true;
} finally {
    apiCallMetricsService.recordApiCall(metricEnum,
        isGroupChat ? "/v1/welinkim/im-service/chat/app-group-chat"
                    : "/v1/welinkim/im-service/chat/app-user-chat",
        success, System.currentTimeMillis() - start);
    MdcHelper.putBusinessDomain(null); // 清除，避免泄漏
}
```

- [ ] **Step 3: 接入 ImMessageService (IM_MESSAGE_SEND)**

同样模式，枚举 `IM_MESSAGE_SEND`，URL 模板 `/messages/send`。

- [ ] **Step 4: 接入 GatewayApiClient (3 个 REST 枚举)**

3 个方法各接入：
- `getOnlineAgentsByUserId` → `GATEWAY_AGENTS_LIST`，URL `/api/gateway/agents`
- `getAgentByAk` → `GATEWAY_AGENTS_BY_AK`，URL `/api/gateway/agents`
- `getAvailability` → `GATEWAY_AGENT_AVAILABILITY`，URL `/api/gateway/internal/agent/availability`

- [ ] **Step 5: 接入 GatewayRelayService (3 个 WS 枚举)**

3 个方法各接入：
- `sendInvokeToGateway` → `GATEWAY_WS_INVOKE`，URL `ws://gateway/ws/skill`
- `sendRouteConfirm` → `GATEWAY_WS_ROUTE_CONFIRM`，URL `ws://gateway/ws/skill`
- `sendRouteReject` → `GATEWAY_WS_ROUTE_REJECT`，URL `ws://gateway/ws/skill`

- [ ] **Step 6: 接入 AssistantInfoService (BUSINESS_CENTER_ASSISTANT_INFO)**

`fetchFromUpstream` → `BUSINESS_CENTER_ASSISTANT_INFO`，URL `/appstore/wecodeapi/open/ak/info`。

- [ ] **Step 7: 接入 AssistantInstanceInfoService (BUSINESS_CENTER_INSTANCE_QUERY)**

`lookup` → `BUSINESS_CENTER_INSTANCE_QUERY`，URL `/assistant-api/integration/v4-1/we-crew/instance/query`。

- [ ] **Step 8: 接入 AssistantIdResolverService (BUSINESS_CENTER_PERSONA_QUERY)**

`fetchFromPersonaApi` → `BUSINESS_CENTER_PERSONA_QUERY`，URL `/welink-persona-settings/persona-new`。

- [ ] **Step 9: 接入 WelinkTelemetryClient (TELEMETRY_WELINK_UPLOAD)**

`send` → `TELEMETRY_WELINK_UPLOAD`，URL `{telemetry.welink.url}`。

- [ ] **Step 10: 验证编译通过**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/ImOutboundService.java skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInstanceInfoService.java skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java skill-server/src/main/java/com/opencode/cui/skill/telemetry/client/WelinkTelemetryClient.java
git commit -m "feat(metrics): instrument 13 third-party API call points with recordApiCall + businessDomain MDC"
```

---

## Task 10: 流式指标接入点 — MessageTurnLifecycle 注入 (#5/#6/#8)

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageFlowService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayMessageRouter.java` (或对应 router 类名)

- [ ] **Step 1: 理解接入点**

4 个生命周期阶段注入到现有代码：
- `onTurnStart`: `SkillMessageFlowService.sendMessage()` 用户消息入库后
- `onFirstToken`: `GatewayMessageRouter.handleToolEvent()` 收到首个 text.delta
- `onToken`: `GatewayMessageRouter.handleToolEvent()` 每收到 text.delta
- `onTurnEnd`: `GatewayMessageRouter.handleToolDone()` 收到完成事件

先读取这两个文件，确认现有方法签名、messageId 获取方式、brainTag 来源、assistantAccount 来源。

- [ ] **Step 2: SkillMessageFlowService 注入 onTurnStart**

构造注入 `MessageTurnLifecycle`。在 `sendMessage()` 方法中，`saveUserMessage()` 生成 messageId 后、发往 gateway 前调用：

```java
// 构造注入
private final MessageTurnLifecycle messageTurnLifecycle;

// sendMessage() 方法内，saveUserMessage() 之后：
messageTurnLifecycle.onTurnStart(
    message.getMessageId(),
    brainTag,           // 从 AssistantInfo.businessTag 或 scopeInfo 获取
    session.getId().toString(),
    effectiveUserId,
    brainTag
);
```

- [ ] **Step 3: GatewayMessageRouter 注入 onFirstToken/onToken/onTurnEnd**

构造注入 `MessageTurnLifecycle`。在流式事件处理中：

```java
// 构造注入
private final MessageTurnLifecycle messageTurnLifecycle;

// handleToolEvent() 中，收到 text.delta 时：
if (isFirstTokenForMessage(messageId)) {
    messageTurnLifecycle.onFirstToken(messageId, brainTag, sessionId, assistantAccount);
}
messageTurnLifecycle.onToken(messageId, brainTag);

// handleToolDone() 中：
messageTurnLifecycle.onTurnEnd(messageId, brainTag, sessionId, assistantAccount);
```

> 注意：`isFirstTokenForMessage` 的判断逻辑需根据现有代码确认——可能是检查该 messageId 是否已收到过 text.delta，或通过事件类型判断。`brainTag` / `assistantAccount` / `sessionId` 的来源需从现有消息上下文中提取。

- [ ] **Step 4: 验证编译通过**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: 运行全部测试确认无回归**

Run: `mvn -pl skill-server test -q`
Expected: BUILD SUCCESS (现有测试 + 新增 metrics 测试全通过)

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageFlowService.java skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayMessageRouter.java
git commit -m "feat(metrics): wire MessageTurnLifecycle into SkillMessageFlowService and GatewayMessageRouter"
```

---

## Task 11: skill-server GatewayWSClient 连接数指标

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`

- [ ] **Step 1: 读取 GatewayWSClient.java 确认现有结构**

读取文件，确认连接池初始化、`onOpen`/`onClose` 回调位置、现有构造注入模式。

- [ ] **Step 2: 注入 MeterRegistry + 连接数指标**

```java
// 构造注入
private final MeterRegistry meterRegistry;
private final AtomicInteger currentConnections = new AtomicInteger(0);
private final Counter totalConnections;

// 构造函数中：
this.meterRegistry = meterRegistry;
this.totalConnections = meterRegistry.counter("gateway_ws_total_connections");
// Gauge 绑定 currentConnections
meterRegistry.gauge("gateway_ws_current_connections", currentConnections);

// onOpen 回调中：
currentConnections.incrementAndGet();
totalConnections.increment();

// onClose 回调中：
currentConnections.decrementAndGet();
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java
git commit -m "feat(metrics): add GatewayWSClient connection count metrics (current + total)"
```

---

## Task 12: ai-gateway 依赖 + 配置 + SkillRelayService WS 连接数指标

**Files:**
- Modify: `ai-gateway/pom.xml`
- Modify: `ai-gateway/src/main/resources/application.yml`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`

- [ ] **Step 1: ai-gateway pom.xml 新增依赖**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: ai-gateway application.yml 新增配置**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
      base-path: /actuator
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ai-gateway
```

- [ ] **Step 3: SkillRelayService 注入连接数指标**

读取 `SkillRelayService.java`，确认 `registerSourceSession()` / `removeSourceSession()` 位置：

```java
// 构造注入
private final MeterRegistry meterRegistry;
private final AtomicInteger currentSkillConnections = new AtomicInteger(0);
private final Counter totalSkillConnections;

// 构造函数中：
this.meterRegistry = meterRegistry;
this.totalSkillConnections = meterRegistry.counter("gateway_ws_skill_total_connections");
meterRegistry.gauge("gateway_ws_skill_current_connections", currentSkillConnections);

// registerSourceSession() 中：
currentSkillConnections.incrementAndGet();
totalSkillConnections.increment();

// removeSourceSession() 中：
currentSkillConnections.decrementAndGet();
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn -pl ai-gateway compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: 运行 ai-gateway 测试确认无回归**

Run: `mvn -pl ai-gateway test -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/pom.xml ai-gateway/src/main/resources/application.yml ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git commit -m "feat(metrics): add ai-gateway actuator+prometheus and SkillRelayService connection metrics"
```

---

## Self-Review Checklist

完成所有 Task 后验证：

- [ ] `mvn -pl skill-server,ai-gateway compile -q` — 编译通过
- [ ] `mvn -pl skill-server test -q` — skill-server 全部测试通过
- [ ] `mvn -pl ai-gateway test -q` — ai-gateway 全部测试通过
- [ ] 启动 skill-server，访问 `http://localhost:8080/actuator/prometheus` — 确认指标暴露
- [ ] 启动 ai-gateway，访问 `http://localhost:8081/actuator/prometheus` — 确认指标暴露
- [ ] 检查日志输出确认 `[%X{businessDomain}]` 占位符渲染正确（空值时为 `[]`）

## Spec Coverage 确认

| 需求 | Task |
|------|------|
| #1 第三方接口调用埋码 | Task 2 (枚举) + Task 3 (recordApiCall) + Task 9 (13 接入点) |
| #2 枚举第三方调用 | Task 2 (13 个接口级枚举) |
| #3 慧眼告警 | Task 4 (MDC businessDomain) + Task 9 (13 入口设置值) + 慧眼语法清单文档 |
| #4 URL 路径参数替换 | Task 3 (去 query + 模板保留) |
| #5 TTFT 上报 Welink | Task 7 (ChatFirstTokenTelemetryEvent) + Task 8 (MessageTurnLifecycle) + Task 10 (接入) |
| #6/#8 消息生命周期 | Task 6 (ChatStreamMetricsService) + Task 8 (MessageTurnLifecycle) + Task 10 (接入) |
| #7 非侵入式 | 放弃，保持侵入式 (Task 9 直接注入) |
| skill-server 对外 API 拦截器 | Task 5 (ApiMetricsInterceptor) |
| skill-server WS Client 连接数 | Task 11 (GatewayWSClient) |
| ai-gateway WS 被连接数 | Task 12 (SkillRelayService) |

---

## 实现复盘与差异分析

> 分析日期：2026-06-19
> 分析范围：feature-ops-metrics 分支全部代码变更

### 一、整体完成度评估

| 维度 | 完成度 | 说明 |
|------|--------|------|
| 核心功能 | **~95%** | 13 个第三方接口埋码、对外 API 拦截器、流式效率指标、WS 连接数、慧眼告警 MDC、消息生命周期抽象、TTFT 双上报全部实现 |
| 代码质量 | **高** | 委托模式、防泄漏设计（MDC finally 清理 + Caffeine TTL）、finally 清理等最佳实践都到位 |
| 设计一致性 | **大部分一致** | 有 1 个重要配置不一致问题，2 个次要差异 |

### 二、需求 vs 实现对比

#### ✅ 已正确实现的部分

| 需求点 | 实现状态 | 关键文件 |
|--------|----------|----------|
| MetricServiceEnum 13 个接口级枚举 | ✅ 完全一致 | [MetricServiceEnum.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MetricServiceEnum.java) |
| recordApiCall (3 Counter + 1 Timer + URL 去 query) | ✅ 完全一致 | [ApiCallMetricsService.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiCallMetricsService.java) |
| 失败时写 `[EXT_CALL]` ERROR 日志 | ✅ 完全一致 | 同上，第 45-47 行 |
| 13 个第三方调用接入点 | ✅ 完全一致 | 9 个服务类，覆盖 IM(3) + Gateway(6) + 业务中心(3) + 埋码上报(1) |
| MDC businessDomain + log4j2 pattern | ✅ 完全一致 | [MdcConstants.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java) + [log4j2-spring.xml](file:///d:/code/opencode-CUI/skill-server/src/main/resources/log4j2-spring.xml) |
| 13 个入口 MDC 清理（finally 模式） | ✅ 完全一致 | 所有接入点均在 finally 中 `putBusinessDomain(null)` |
| ApiMetricsInterceptor 对外 API 拦截 | ✅ 完全一致 | [ApiMetricsInterceptor.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ApiMetricsInterceptor.java) |
| ChatStreamMetricsService (TTFT/Latency/TPS) | ✅ 已实现 | [ChatStreamMetricsService.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsService.java) |
| Caffeine Cache 防内存泄漏 | ✅ 已实现 | 同上，maximumSize + expireAfterWrite 双重保障 |
| brain_tag + UNKNOWN 兜底 | ✅ 完全一致 | 同上，第 90-92 行 `resolveBrainTag` |
| MessageTurnLifecycle 编排器（委托模式） | ✅ 完全一致 | [MessageTurnLifecycle.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/MessageTurnLifecycle.java) |
| TTFT 双上报 Prometheus + Welink | ✅ 完全一致 | 同上，第 35-39 行 |
| ChatFirstTokenTelemetryEvent | ✅ 完全一致 | [ChatFirstTokenTelemetryEvent.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/chat/ChatFirstTokenTelemetryEvent.java) |
| SkillMessageFlowService onTurnStart 接入 | ✅ 已实现 | [SkillMessageFlowService.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageFlowService.java) 第 116-122 行 |
| GatewayMessageRouter onFirstToken/onToken/onTurnEnd 接入 | ✅ 已实现 | [GatewayMessageRouter.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java) 第 847/849/1051 行 |
| GatewayWSClient 连接数（Gauge + Counter） | ✅ 完全一致 | [GatewayWSClient.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java) |
| SkillRelayService 连接数（Gauge + Counter） | ✅ 完全一致 | [SkillRelayService.java](file:///d:/code/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java) |
| Actuator + Prometheus 依赖与配置 | ✅ 完全一致 | pom.xml + application.yml |

#### ⚠️ 存在差异 / 问题

| # | 问题 | 严重程度 | 详情 | 建议 |
|---|------|----------|------|------|
| 1 | **ChatStreamMetricsService 配置路径不一致** | 🔴 高 | 代码 `@Value` 读 `telemetry.chatstream.max-sessions` / `telemetry.chatstream.session-ttl-minutes`，但 `application.yml` 配置的是 `skill.metrics.stream.max-sessions` / `skill.metrics.stream.session-ttl`。配置不生效，一直用默认值。 | 统一为 `skill.metrics.stream.*` 前缀 |
| 2 | TPOT 指标未实现 | 🟡 中 | 需求中提到了 TPOT（每 token 输出延迟），但设计文档 §八.1 也将其标记为"待确认"，属于有意识地未实现 | 确认是否需要后补充实现 |
| 3 | Welink 开关读取方式 | 🟢 低 | 设计建议复用 `WelinkTelemetryReporter.isEffectiveEnabled()`，代码直接读 `telemetry.welink.enabled`。影响不大，因为 reporter 内部也有自己的 enabled 判断 | 可保持现状，或统一为注入 reporter 的 enabled 状态 |

### 三、问题 1 详细说明：配置路径不一致

**代码中的配置读取**（[ChatStreamMetricsService.java](file:///d:/code/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/telemetry/metrics/ChatStreamMetricsService.java#L31-L33) 第 31-33 行）：

```java
public ChatStreamMetricsService(MeterRegistry meterRegistry,
                               @Value("${telemetry.chatstream.max-sessions:10000}") long maxSessions,
                               @Value("${telemetry.chatstream.session-ttl-minutes:60}") Duration sessionTtl) {
```

**application.yml 中的配置**（第 141-144 行）：

```yaml
skill:
  metrics:
    stream:
      max-sessions: ${SKILL_METRICS_STREAM_MAX_SESSIONS:10000}
      session-ttl: ${SKILL_METRICS_STREAM_SESSION_TTL:30m}
```

**影响**：
- 配置项 `skill.metrics.stream.max-sessions` 和 `skill.metrics.stream.session-ttl` 完全不生效
- 代码始终使用默认值：max-sessions=10000，session-ttl=60 分钟
- 设计文档、计划文档、yml 配置三者一致，但代码实现不一致

### 四、核心实现逻辑总结

#### 4.1 ApiCallMetricsService 调用链

```
调用方 (9 个服务，13 个入口)
    │
    ├── 1. MdcHelper.putBusinessDomain(serviceId)   ← 设置慧眼告警 MDC
    ├── 2. long start = System.currentTimeMillis()  ← 记录开始时间
    ├── 3. 执行业务调用 (HTTP/WS)
    │
    └── 4. finally {
            recordApiCall(service, urlTemplate, success, duration)
                ├── URL 去 query 参数
                ├── Tags: serviceId + serviceComment + url
                ├── Counter: external_api_call_total++
                ├── Counter: external_api_call_success/failure_total++
                ├── Timer: external_api_call_duration_seconds.record(ms)
                └── 失败时 log.error("[EXT_CALL] {} failed: durationMs={}", ...)
            MdcHelper.putBusinessDomain(null)    ← 清理 MDC，防泄漏
          }
```

#### 4.2 MessageTurnLifecycle 生命周期时序

```
用户发消息 → saveUserMessage() 生成 messageId
              ↓
         onTurnStart(messageId, brainTag, ...)
              ↓
         streamMetrics.onStreamStart() → Caffeine 写入 startTime
              ↓
         发往 Gateway (携带 messageId)
                     │
                     ▼
              Gateway 流式返回
                     │
        ┌────────────┴────────────┐
        ▼                         ▼
  首个 text.delta             后续 text.delta
        │                         │
  onFirstToken(messageId)    onToken(messageId)
        │                         │
  ├─ streamMetrics.onFirstToken → 计算 TTFT + Timer.record
  └─ welinkReporter.report(ChatFirstTokenTelemetryEvent)  ← TTFT 双上报
                                     │
                                     ▼
                              tokenCount++ (Caffeine)
                                     │
                                     ▼
                              tool_done 到达
                                     │
                              onTurnEnd(messageId)
                                     │
                              ├─ streamMetrics.onStreamEnd
                              │   ├─ 计算 Latency + Timer.record
                              │   ├─ 计算 TPS + DistributionSummary.record
                              │   └─ 清理 Caffeine 中该 messageId 的状态
                              └─ (不上报 Welink reply — 由现有 AOP 切面负责)
```

#### 4.3 WS 连接数指标

**skill-server 端**（GatewayWSClient）：
- `gateway_ws_current_connections` (Gauge) → 当前已连接数
- `gateway_ws_total_connections` (Counter) → 累计连接次数
- onOpen 时两者都 ++，onClose 时 current --

**ai-gateway 端**（SkillRelayService）：
- `gateway_ws_skill_current_connections` (Gauge) → 当前被 skill 连接数
- `gateway_ws_skill_total_connections` (Counter) → 累计被连接次数
- registerSourceSession 时两者都 ++，removeSourceSession 时 current --

### 五、慧眼告警工作原理

```
日志格式改造：... [%X{scenario}] [%X{businessDomain}] %-5level ...

MDC 设置模式（13 个入口统一）：
  try {
      MdcHelper.putBusinessDomain("im_group_chat");  // 值 = MetricServiceEnum.id()
      // 业务逻辑
  } finally {
      MdcHelper.putBusinessDomain(null);  // 清理，防泄漏
  }

慧眼告警语法：
  - 第三方接口异常: loglevel:"ERROR" AND businessDomain:"xxx" AND message:"[EXT_CALL]"
  - 业务异常:     loglevel:"ERROR" AND businessDomain:"xxx" AND message:"[ERROR]"

双观测面：
  - Prometheus: external_api_call_failure_total → 看趋势、统计
  - 慧眼日志: [EXT_CALL] ERROR 日志 → 看具体堆栈和上下文
```

### 六、代码亮点

1. **侵入式 vs 非侵入式的明确取舍**：设计阶段探讨后决定用侵入式，理由是更清晰可控，文档有据可查
2. **MDC 泄漏防护**：所有 13 个入口都在 finally 中清理 businessDomain
3. **Caffeine Cache 双重防护**：size-limited + TTL，防止异常 session 导致内存泄漏
4. **生命周期抽象合理**：委托不重写，职责清晰，避免双重上报
5. **双观测面设计**：同一个失败事件同时输出 Prometheus 指标（趋势）和 ERROR 日志（细节）

### 七、修复建议优先级

| 优先级 | 问题 | 预计工作量 |
|--------|------|------------|
| P0 | 修复 ChatStreamMetricsService 配置路径不一致 | 5 分钟（改 2 个 @Value 注解） |
| P1 | 确认 TPOT 是否需要，需要则补充实现 | 1-2 小时 |
| P2 | 统一 Welink 开关读取方式 | 可选，影响不大 |
