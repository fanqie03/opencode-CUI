# Prometheus 指标埋码设计

> **日期**: 2026-06-08  
> **需求来源**: `docs/human-docs/001运维埋码.md`  
> **目标**: 为 skill-server 和 ai-gateway 接入 Prometheus 指标上报，覆盖第三方接口调用、对外 API 调用、流式对话效率、WS 连接数四个维度。

---

## 1. 背景与问题

### 1.1 skill-server 现状

| 维度 | 现状 |
|------|------|
| **Prometheus/Micrometer** | 无依赖、无配置、无端点 |
| **Actuator** | 无 `/actuator/prometheus` 端点 |
| **现有 telemetry** | `telemetry.welink` 体系（WeLink 事件上报） |
| **外部调用** | 仅通过 `LogTimer` 记录耗时日志 |
| **WS 客户端** | `GatewayWSClient`（连接池），无连接数指标 |
| **Spring Boot 版本** | 3.4.6 |

### 1.2 ai-gateway 现状

| 维度 | 现状 |
|------|------|
| **Prometheus/Micrometer** | 无依赖、无配置、无端点 |
| **现有 metrics** | 手动 `AtomicLong` 计数器，60 秒日志输出 |
| **WS Server** | `/ws/agent` + `/ws/skill`，含握手认证 |
| **连接统计** | `EventRelayService.getActiveSessionCount()` 等 |
| **Spring Boot 版本** | 3.4.6 |

---

## 2. 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| **指标采集** | Micrometer | Spring Boot 3.x 原生集成，标准 facade |
| **指标暴露** | Prometheus Registry | 通过 `/actuator/prometheus` 端点暴露 |
| **端点** | Spring Boot Actuator | 标准端点，无需自建 |
| **WS 连接数** | Micrometer Gauge | 实时反映当前值 |
| **API 调用** | Counter + Timer | 标准调用次数 + 耗时分布 |
| **流式效率** | Timer + DistributionSummary | TTFT/TPOT/Latency 用 Timer，token/s 用 DistributionSummary |

---

## 3. skill-server 详细设计

### 3.1 依赖变更

`pom.xml` 新增：

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

### 3.2 配置变更

`application.yml` 新增：

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
```

### 3.3 需求 1：第三方接口调用埋码

#### 3.3.1 枚举定义

新增 `MetricServiceEnum`（`com.opencode.cui.skill.telemetry.metrics`）：

| 枚举值 | id | comment |
|--------|-----|---------|
| `GATEWAY_INVOKE` | `gateway_invoke` | Gateway 调用 |
| `GATEWAY_AVAILABILITY` | `gateway_availability` | Gateway 可用性查询 |
| `IM_SEND_MESSAGE` | `im_send_message` | IM 发送消息 |
| `IM_UPLOAD_FILE` | `im_upload_file` | IM 上传文件 |
| `IM_DOWNLOAD_FILE` | `im_download_file` | IM 下载文件 |
| `ASSISTANT_INFO` | `assistant_info` | 助手信息查询 |
| `ASSISTANT_INSTANCE` | `assistant_instance` | 助手实例查询 |

#### 3.3.2 `ApiCallMetricsService`

```java
public void recordApiCall(MetricServiceEnum service, String url, boolean success, long durationMs)
```

内部注册指标：
- `external_api_call_total`（Counter）— 总调用次数
- `external_api_call_success_total` / `external_api_call_failure_total`（Counter）
- `external_api_call_duration_seconds`（Timer）— 耗时

Tag：`serviceId`、`serviceComment`、`url`

#### 3.3.3 接入点

| 调用方 | 文件 | 接入方式 |
|--------|------|---------|
| `GatewayRelayService.sendInvokeToGateway()` | `GatewayRelayService.java` | 注入 `ApiCallMetricsService`，调用前后记录 |
| `AssistantAvailabilityService` | `AssistantAvailabilityService.java` | 替换 `LogTimer` 为 `recordApiCall` |
| `ImMessageService` | `ImMessageService.java` | 替换 `LogTimer` 为 `recordApiCall` |
| `AssistantInfoService.fetchFromUpstream()` | `AssistantInfoService.java` | 注入并记录 |
| `AssistantInstanceInfoService.lookup()` | `AssistantInstanceInfoService.java` | 注入并记录 |

### 3.4 需求 2：对外 API 调用效率

#### 3.4.1 `ApiMetricsInterceptor`

实现 `HandlerInterceptor`：
- `preHandle`：记录 `request.setAttribute("metrics.startTime", System.currentTimeMillis())`
- `postHandle`：计算 cost，Timer 记录 `common_interface_duration_seconds`，tag 为 `common_interface_url`

在 `WebMvcConfig` 中注册，拦截 `/api/**`。

### 3.5 需求 3：流式对话效率

#### 3.5.1 指标定义

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `chat_stream_ttft_seconds` | Timer | 首 token 延迟（TTFT） |
| `chat_stream_tpot_seconds` | Timer | 每 token 输出延迟（TPOT） |
| `chat_stream_latency_seconds` | Timer | 端到端延迟 |
| `chat_stream_tokens_per_second` | DistributionSummary | 每秒输出 token 数 |

Tag：仅 `brain_tag`（来源 `AssistantInfo.businessTag`，不存在则 `UNKNOWN`）

#### 3.5.2 `ChatStreamMetricsService`

使用 **Caffeine Cache** 按 `messageId` 维护每轮问答状态（`messageId` 缺失时跳过该轮次）：

- `sessionStartTimes`：`Cache<String, Long>` — 轮次开始时间
- `firstTokenTimestamps`：`Cache<String, Long>` — 首 token 到达时间
- `tokenCounts`：`Cache<String, AtomicInteger>` — token 计数

Caffeine 配置：
- `maximumSize`：`skill.metrics.stream.max-sessions`，默认 10000
- `expireAfterWrite`：`skill.metrics.stream.session-ttl`，默认 30 分钟

生命周期方法（`messageId` 为 null 时直接 return）：
- `onStreamStart(messageId, brainTag)`：记录开始时间
- `onFirstToken(messageId, brainTag)`：计算并记录 TTFT
- `onToken(messageId, brainTag)`：递增 token 计数
- `onStreamEnd(messageId, brainTag)`：计算 Latency + tokensPerSecond，清理状态

配置项：

```yaml
skill:
  metrics:
    stream:
      max-sessions: ${SKILL_METRICS_STREAM_MAX_SESSIONS:10000}
      session-ttl: ${SKILL_METRICS_STREAM_SESSION_TTL:30m}
```

#### 3.5.3 接入点

- `onStreamStart`：`SkillMessageFlowService.sendMessage()` 中，用户消息入库后调用
- `onFirstToken`：`GatewayMessageRouter.handleToolEvent()` 中，收到首个 `text.delta` 时调用
- `onToken`：`GatewayMessageRouter.handleToolEvent()` 中，每收到一个 `text.delta` 时调用
- `onStreamEnd`：`GatewayMessageRouter.handleToolDone()` 中，收到完成事件时调用

### 3.6 需求 4：Gateway WS Client 连接数

#### 3.6.1 指标定义

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `gateway_ws_current_connections` | Gauge | 当前已连接的 gateway 数 |
| `gateway_ws_total_connections` | Counter | 总共连接次数（累计） |

#### 3.6.2 实现方式

在 `GatewayWSClient` 中注入 `MeterRegistry`：
- `AtomicInteger currentConnections`：当前连接数
- `Counter totalConnections`：累计连接次数
- `Gauge` 绑定 `currentConnections` 到 `gateway_ws_current_connections`
- `onOpen`：`currentConnections++`、`totalConnections++`
- `onClose`：`currentConnections--`

---

## 4. ai-gateway 详细设计

### 4.1 依赖与配置

同 skill-server，`pom.xml` 新增 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`，`application.yml` 新增 `management` 配置段（`application: ai-gateway`）。

### 4.2 需求：Gateway WS 被连接情况

#### 4.2.1 指标定义

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `gateway_ws_skill_current_connections` | Gauge | 当前被多少个 skill 连接 |
| `gateway_ws_skill_total_connections` | Counter | 总共被 skill 连接次数（累计） |

#### 4.2.2 实现方式

在 `SkillRelayService` 中注入 `MeterRegistry`：
- `AtomicInteger currentSkillConnections`：当前 skill 连接数
- `Counter totalSkillConnections`：累计 skill 连接次数
- `Gauge` 绑定 `currentSkillConnections` 到 `gateway_ws_skill_current_connections`
- `registerSourceSession()`：`currentSkillConnections++`、`totalSkillConnections++`
- `removeSourceSession()`：`currentSkillConnections--`

---

## 5. 文件变更清单

### 5.1 skill-server

| 操作 | 文件 | 说明 |
|------|------|------|
| **修改** | `pom.xml` | 新增 actuator + micrometer-registry-prometheus 依赖 |
| **修改** | `application.yml` | 新增 management 配置段 |
| **新增** | `telemetry/metrics/MetricServiceEnum.java` | 第三方服务枚举 |
| **新增** | `telemetry/metrics/ApiCallMetricsService.java` | recordApiCall 实现 |
| **新增** | `telemetry/metrics/ApiMetricsInterceptor.java` | 对外 API 拦截器 |
| **新增** | `telemetry/metrics/ChatStreamMetricsService.java` | 流式效率指标 |
| **修改** | `config/WebMvcConfig.java` | 注册 ApiMetricsInterceptor |
| **修改** | `ws/GatewayWSClient.java` | 注入 MeterRegistry，WS 连接数指标 |
| **修改** | `service/GatewayRelayService.java` | 注入 ApiCallMetricsService |
| **修改** | `service/ImMessageService.java` | 注入 ApiCallMetricsService |
| **修改** | `service/AssistantInfoService.java` | 注入 ApiCallMetricsService |
| **修改** | `service/AssistantInstanceInfoService.java` | 注入 ApiCallMetricsService |
| **修改** | `service/AssistantAvailabilityService.java` | 注入 ApiCallMetricsService |
| **修改** | `service/SkillMessageFlowService.java` | 注入 ChatStreamMetricsService，流式埋点 |

### 5.2 ai-gateway

| 操作 | 文件 | 说明 |
|------|------|------|
| **修改** | `pom.xml` | 新增 actuator + micrometer-registry-prometheus 依赖 |
| **修改** | `application.yml` | 新增 management 配置段 |
| **修改** | `service/SkillRelayService.java` | 注入 MeterRegistry，WS 连接数指标 |

---

## 6. 风险与注意事项

1. **内存泄漏**：`ChatStreamMetricsService` 使用 Caffeine Cache（size-limited + TTL），自动淘汰过期条目。
2. **性能影响**：Micrometer Counter/Timer 基于 `ConcurrentHashMap` + `AtomicLong`，开销极小（纳秒级），不影响主链路。
3. **端点安全**：`/actuator/prometheus` 应配置内网访问限制或 Spring Security 保护。
4. **与现有 WeLink telemetry 的关系**：本次 Prometheus 指标与现有 WeLink 事件上报**独立共存**，互不影响。

---

## 7. PromQL 示例

```promql
# TTFT P50/P95/P99
histogram_quantile(0.50, rate(chat_stream_ttft_seconds_bucket[5m]))
histogram_quantile(0.95, rate(chat_stream_ttft_seconds_bucket[5m]))
histogram_quantile(0.99, rate(chat_stream_ttft_seconds_bucket[5m]))

# Latency P50/P95/P99
histogram_quantile(0.50, rate(chat_stream_latency_seconds_bucket[5m]))
histogram_quantile(0.95, rate(chat_stream_latency_seconds_bucket[5m]))
histogram_quantile(0.99, rate(chat_stream_latency_seconds_bucket[5m]))

# 按大脑标签分组
histogram_quantile(0.95, rate(chat_stream_ttft_seconds_bucket[5m])) by (brain_tag)

# 每秒 token 数平均值
rate(chat_stream_tokens_per_second_sum[5m]) / rate(chat_stream_tokens_per_second_count[5m])
```

---

## 8. 待确认事项

1. `chat_stream_tpot_seconds`（TPOT）的接入点：当前流式处理路径中是否已有"每个 token 到达"的回调？需确认 `SkillMessageFlowService` 的流式处理细节。
2. Grafana 面板由开发手工添加，是否需要在文档中提供完整的 Dashboard JSON？
