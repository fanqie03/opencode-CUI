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

---

## 9. 测试建议（供测试人员参考）

> 以下测试用例面向手工测试 / 接口测试 / 运维验收人员，建议结合 `curl`、`Prometheus UI`（`localhost:9090`）、`actuator` 端点执行验证。

### 9.1 端点与基础可用性测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| BASE-001 | Prometheus 端点暴露 | 服务启动，配置已加载 | GET `/actuator/prometheus` | HTTP 200，返回 Prometheus 文本格式指标，Content-Type 为 `text/plain;version=0.0.4` |
| BASE-002 | Health 端点不受影响 | 服务启动 | GET `/actuator/health` | HTTP 200，原有 health 检查正常 |
| BASE-003 | 端点安全限制（如有） | 配置 Spring Security / 网络隔离 | 从外网访问 `/actuator/prometheus` | 返回 401/403 或网络不可达，确保指标不外泄 |
| BASE-004 | skill-server 标签正确 | 服务启动 | 检查 `/actuator/prometheus` 输出 | 所有指标包含 `application="skill-server"` 标签 |
| BASE-005 | ai-gateway 标签正确 | 服务启动 | 检查 `/actuator/prometheus` 输出 | 所有指标包含 `application="ai-gateway"` 标签 |

### 9.2 第三方接口调用指标验证（skill-server）

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| API-001 | Gateway 调用成功计数 | 服务启动 | 触发一次成功的 Gateway 调用（如发送消息到 Gateway） | `external_api_call_total{serviceId="gateway_invoke"}` 增加 1；`external_api_call_success_total` 增加 1 |
| API-002 | Gateway 调用失败计数 | 服务启动 | 模拟 Gateway 超时或返回 5xx | `external_api_call_total` 增加 1；`external_api_call_failure_total` 增加 1 |
| API-003 | 调用耗时记录 | 服务启动 | 触发多次不同耗时的调用 | `external_api_call_duration_seconds_bucket` 各 bucket 有值，`_sum` 和 `_count` 非零 |
| API-004 | 标签完整性 | 服务启动 | 触发 IM 发送消息 | 指标包含 `serviceId="im_send_message"`、`serviceComment="IM 发送消息"`、`url="..."` |
| API-005 | 多服务枚举覆盖 | 服务启动 | 依次触发 `assistant_info`、`assistant_instance`、`im_upload_file` | 每个服务对应独立的 `serviceId` 标签，Counter 分别累加 |

### 9.3 对外 API 效率指标验证（skill-server）

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| INT-001 | 接口耗时记录 | 服务启动 | 调用任意 `/api/**` 接口 | `common_interface_duration_seconds_count` 增加 1，`_sum` 记录实际耗时（秒） |
| INT-002 | URL 标签正确 | 服务启动 | 调用 `/api/skill/sessions/123/abort` | 指标标签 `common_interface_url="/api/skill/sessions/123/abort"` |
| INT-003 | 拦截器范围正确 | 服务启动 | 调用非 `/api/**` 路径（如静态资源） | `common_interface_duration_seconds` 不增加 |

### 9.4 流式对话效率指标验证（skill-server）

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| STR-001 | TTFT 记录 | 服务启动，发起流式对话 | 发送消息后，收到首个 token | `chat_stream_ttft_seconds_count` 增加 1，值约等于首 token 到达耗时 |
| STR-002 | Latency 记录 | 服务启动，发起流式对话 | 完成一整轮流式对话 | `chat_stream_latency_seconds_count` 增加 1，值约等于端到端总耗时 |
| STR-003 | tokens/s 记录 | 服务启动，发起流式对话 | 完成一整轮流式对话 | `chat_stream_tokens_per_second_count` 增加 1，`_sum` / `_count` 为平均 tokens/s |
| STR-004 | brain_tag 标签 | 助手配置 `businessTag=TEST_TAG` | 发起流式对话 | 指标包含 `brain_tag="TEST_TAG"`；若未配置则为 `brain_tag="UNKNOWN"` |
| STR-005 | 无 messageId 时跳过 | 服务启动 | 模拟 messageId 为 null 的流式事件 | 该轮次不记录任何流式指标，服务端无 NPE |
| STR-006 | Caffeine Cache 淘汰 | 服务启动 | 大量流式对话后等待 30 分钟 | 内存无泄漏，过期条目被自动清理 |

### 9.5 WS 连接数指标验证

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| WS-001 | skill-server Gateway WS 连接数 | 服务启动 | 连接 Gateway → 断开 → 再连接 | `gateway_ws_current_connections` 依次为 1 → 0 → 1；`gateway_ws_total_connections` 累加不减少 |
| WS-002 | ai-gateway Skill WS 被连接数 | 服务启动 | 多个 skill-server 连接到 ai-gateway `/ws/skill` | `gateway_ws_skill_current_connections` 等于当前连接数；`gateway_ws_skill_total_connections` 累加 |
| WS-003 | 异常断开恢复 | 服务启动 | 强制 kill skill-server 进程 | ai-gateway 侧 `gateway_ws_skill_current_connections` 正确降为 0 |

### 9.6 PromQL 查询验证

| 用例编号 | 用例名称 | 查询语句 | 预期结果 |
|----------|----------|----------|----------|
| PQL-001 | TTFT P99 | `histogram_quantile(0.99, rate(chat_stream_ttft_seconds_bucket[5m]))` | 返回 0~10s 之间的有效数值，无 `NaN`（需保证有足够样本） |
| PQL-002 | 按 brain_tag 分组 | `rate(chat_stream_ttft_seconds_bucket[5m]) by (brain_tag)` | 返回多组时间序列，每组对应一个 `brain_tag` |
| PQL-003 | API 成功率 | `rate(external_api_call_success_total[5m]) / rate(external_api_call_total[5m])` | 返回 0~1 之间的成功率 |
| PQL-004 | 接口 QPS | `rate(common_interface_duration_seconds_count[1m])` | 返回每秒请求数，压测期间应明显上升 |

### 9.7 风险场景验证

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| RISK-001 | 与 WeLink telemetry 共存 | 服务启动 | 触发 WeLink 事件上报和 Prometheus 指标采集 | 两者互不干扰，WeLink 事件正常上报，Prometheus 指标正常暴露 |
| RISK-002 | 高并发指标性能 | 服务启动 | 压测 1000 QPS 持续 5 分钟 | CPU 增幅 < 5%，无内存泄漏，指标端点响应正常 |
| RISK-003 | Micrometer 端点大数据量 | 服务运行长时间 | GET `/actuator/prometheus` | 响应时间 < 500ms，返回内容可正常被 Prometheus Server 抓取 |

### 9.8 回归测试 checklist

- [ ] `mvn test` 全量通过，新增单元测试覆盖 `ApiCallMetricsService`、`ChatStreamMetricsService`。
- [ ] 存量 `LogTimer` 日志未被删除，仅新增 Micrometer 指标，确保可回滚。
- [ ] `/actuator/health` 行为未变。
- [ ] Prometheus 依赖未与现有依赖冲突（`mvn dependency:tree` 检查）。
- [ ] 未引入 `actuator` 以外的额外端口暴露。
