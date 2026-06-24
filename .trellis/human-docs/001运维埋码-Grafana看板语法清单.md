# 运维埋码 — Grafana 看板语法清单

> 配套文档：`001运维埋码-需求分析与设计.md`
> Grafana 版本：7.x
> 数据源：Prometheus
> 日期：2026-06-21

---

## 一、第三方 API 调用（external_api_call）

### 1.1 指标定义

| 指标名 | 类型 | Tags | 说明 |
|--------|------|------|------|
| `external_api_call_total` | Counter | serviceId, serviceComment, url | 第三方接口总调用次数 |
| `external_api_call_success_total` | Counter | serviceId, serviceComment, url | 成功调用次数 |
| `external_api_call_failure_total` | Counter | serviceId, serviceComment, url | 失败调用次数 |
| `external_api_call_duration_seconds` | Timer(histogram) | serviceId, serviceComment, url | 调用耗时分布 |

### 1.2 看板 Panel 语法

#### Panel 1：调用总量按业务接口分组（Stat / Graph）

```
# 总调用速率（QPS），按 serviceId 分组
sum(rate(external_api_call_total[5m])) by (serviceId)
```

#### Panel 2：成功率（Gauge / Stat）

```
# 成功率 = success / total
sum(rate(external_api_call_success_total[5m])) by (serviceId)
/
sum(rate(external_api_call_total[5m])) by (serviceId)
```

Gauge 可视化：阈值 95% 绿色 / 80% 黄色 / <80% 红色。

#### Panel 3：失败速率（Graph，按 serviceId 分组）

```
sum(rate(external_api_call_failure_total[5m])) by (serviceId)
```

#### Panel 4：调用耗时 P50 / P95 / P99（Graph）

```
# P50
histogram_quantile(0.50, sum(rate(external_api_call_duration_seconds_bucket[5m])) by (le, serviceId))

# P95
histogram_quantile(0.95, sum(rate(external_api_call_duration_seconds_bucket[5m])) by (le, serviceId))

# P99
histogram_quantile(0.99, sum(rate(external_api_call_duration_seconds_bucket[5m])) by (le, serviceId))
```

每条 PromQL 作为一个 series，legend 设为 `{{serviceId}} P50/P95/P99`。

#### Panel 5：按 URL 路由模板分组耗时 P95（Table）

```
histogram_quantile(0.95, sum(rate(external_api_call_duration_seconds_bucket[5m])) by (le, serviceId, url))
```

Table 格式化：Transform → Organize fields，只显示 serviceId、url、Value。

#### Panel 6：单接口下钻（变量联动）

变量 `$serviceId`（Query 类型，查询 `label_values(external_api_call_total, serviceId)`）：

```
# 选中接口的 QPS
sum(rate(external_api_call_total{serviceId="$serviceId"}[5m]))

# 选中接口的 P95 耗时
histogram_quantile(0.95, sum(rate(external_api_call_duration_seconds_bucket{serviceId="$serviceId"}[5m])) by (le))

# 选中接口的错误数
sum(rate(external_api_call_failure_total{serviceId="$serviceId"}[5m]))
```

---

## 二、对外 API 接口调用（common_interface）

### 2.1 指标定义

| 指标名 | 类型 | Tags | 说明 |
|--------|------|------|------|
| `common_interface_duration_seconds` | Timer(histogram) | common_interface_url | 对外暴露 API 的请求耗时 |

### 2.2 看板 Panel 语法

#### Panel 7：API 请求 P95 耗时（Graph，按 URL 分组）

```
histogram_quantile(0.95, sum(rate(common_interface_duration_seconds_bucket[5m])) by (le, common_interface_url))
```

#### Panel 8：API 请求 QPS（Graph）

```
sum(rate(common_interface_duration_seconds_count[5m])) by (common_interface_url)
```

---

## 三、流式对话效率（chat_stream）

### 3.1 指标定义

| 指标名 | 类型 | Tags | 说明 |
|--------|------|------|------|
| `chat_stream_ttft_seconds` | Timer(histogram) | brain_tag | 首 token 延迟（TTFT） |
| `chat_stream_latency_seconds` | Timer(histogram) | brain_tag | 端到端延迟 |
| `chat_stream_tokens_per_second` | Summary | brain_tag | 每秒输出 token 数 |

> 注：`brain_tag` 不存在时兜底为 `UNKNOWN`。

### 3.2 看板 Panel 语法

#### Panel 9：TTFT P50 / P95 / P99（Graph，全局）

```
# P50
histogram_quantile(0.50, sum(rate(chat_stream_ttft_seconds_bucket[5m])) by (le))

# P95
histogram_quantile(0.95, sum(rate(chat_stream_ttft_seconds_bucket[5m])) by (le))

# P99
histogram_quantile(0.99, sum(rate(chat_stream_ttft_seconds_bucket[5m])) by (le))
```

#### Panel 10：TTFT P95 按大脑标签分组（Graph）

```
histogram_quantile(0.95, sum(rate(chat_stream_ttft_seconds_bucket[5m])) by (le, brain_tag))
```

Legend: `{{brain_tag}}`

#### Panel 11：端到端 Latency P50 / P95 / P99（Graph）

```
# P50
histogram_quantile(0.50, sum(rate(chat_stream_latency_seconds_bucket[5m])) by (le))

# P95
histogram_quantile(0.95, sum(rate(chat_stream_latency_seconds_bucket[5m])) by (le))

# P99
histogram_quantile(0.99, sum(rate(chat_stream_latency_seconds_bucket[5m])) by (le))
```

#### Panel 12：每秒 token 数平均值（Graph，按大脑标签分组）

```
# TPS 平均值 = sum / count
rate(chat_stream_tokens_per_second_sum[5m]) by (brain_tag)
/
rate(chat_stream_tokens_per_second_count[5m]) by (brain_tag)
```

Legend: `{{brain_tag}} avg TPS`

#### Panel 13：对话轮次速率（Stat）

```
# 每秒完成的对话轮次
rate(chat_stream_latency_seconds_count[5m])
```

#### Panel 14：TTFT 按大脑标签下钻（变量联动）

变量 `$brain_tag`（Query 类型，查询 `label_values(chat_stream_ttft_seconds, brain_tag)`）：

```
# 选中大脑的 TTFT P95
histogram_quantile(0.95, sum(rate(chat_stream_ttft_seconds_bucket{brain_tag="$brain_tag"}[5m])) by (le))

# 选中大脑的 Latency P95
histogram_quantile(0.95, sum(rate(chat_stream_latency_seconds_bucket{brain_tag="$brain_tag"}[5m])) by (le))

# 选中大脑的 TPS 平均值
rate(chat_stream_tokens_per_second_sum{brain_tag="$brain_tag"}[5m])
/
rate(chat_stream_tokens_per_second_count{brain_tag="$brain_tag"}[5m])
```

---

## 四、WebSocket 连接数

### 4.1 指标定义

**skill-server 端**（连接到 gateway 的客户端）：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `gateway_ws_current_connections` | Gauge | 当前已连接的 gateway 数 |
| `gateway_ws_total_connections` | Counter | 总共连接次数（累计） |

**ai-gateway 端**（被 skill 连接的服务端）：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `gateway_ws_skill_current_connections` | Gauge | 当前被多少个 skill 连接 |
| `gateway_ws_skill_total_connections` | Counter | 总共被 skill 连接次数（累计） |

### 4.2 看板 Panel 语法

#### Panel 15：skill-server 当前 gateway 连接数（Gauge）

```
gateway_ws_current_connections
```

Gauge 可视化：阈值 0 红色 / 1-4 绿色 / >8 黄色（连接池上限 8）。

#### Panel 16：skill-server gateway 累计连接次数（Stat）

```
gateway_ws_total_connections
```

#### Panel 17：ai-gateway 当前 skill 连接数（Gauge）

```
gateway_ws_skill_current_connections
```

#### Panel 18：ai-gateway skill 累计连接次数（Stat）

```
increase(gateway_ws_skill_total_connections[1h])
```

> 用 `increase` 看 1 小时内新增连接数，比看累计值更有意义。

#### Panel 19：连接数趋势（Graph，双线对比）

```
# skill-server 端当前连接
gateway_ws_current_connections

# ai-gateway 端当前被连接
gateway_ws_skill_current_connections
```

两条线放在同一 Graph，legend 分别设为 `skill→gateway` 和 `gateway←skill`。正常情况两条线应该相等（一连接一被连接）。

---

## 五、Caffeine Cache 监控

### 5.1 监控的 Cache 列表

| Cache 名称 | 所在类 | 内容 | 说明 |
|-----------|--------|------|------|
| `sessionStartTimes` | ChatStreamMetricsService | Cache&lt;String, Long&gt; | 每轮问答的开始时间 |
| `firstTokenTimestamps` | ChatStreamMetricsService | Cache&lt;String, Long&gt; | 每轮问答的首 token 时间 |
| `tokenCounts` | ChatStreamMetricsService | Cache&lt;String, AtomicInteger&gt; | 每轮问答的 token 累计字符数 |
| `processedFirstToken` | MessageTurnLifecycle | Cache&lt;String, Boolean&gt; | 已处理首 token 的 messageId 集合 |

> Micrometer 的 `CaffeineCacheMetrics` 自动暴露以下指标系列，tag `cache` 为上表名称。

### 5.2 自动暴露的指标

| 指标名 | 类型 | Tags | 说明 |
|--------|------|------|------|
| `cache_size` | Gauge | cache | 当前缓存条目数 |
| `cache_get_total` | Counter | cache, result(hit/miss) | 缓存查询次数 |
| `cache_eviction_total` | Counter | cache, cause | 缓存驱逐次数 |
| `cache_put_total` | Counter | cache | 缓存写入次数 |

### 5.3 看板 Panel 语法

#### Panel 20：缓存条目数（Graph，4 条线）

```
cache_size{cache=~"sessionStartTimes|firstTokenTimestamps|tokenCounts|processedFirstToken"}
```

Legend: `{{cache}}`

> 正常情况下 `sessionStartTimes` 和 `processedFirstToken` 的 size 应接近（每个活跃轮次各一条）。如果 `sessionStartTimes` size 持续增长但 `processedFirstToken` 不增长，说明流式响应没回来（gateway 异常）。

#### Panel 21：缓存命中率（Stat / Graph，按 cache 分组）

```
# 命中率 = hit / (hit + miss)
sum(rate(cache_get_total{result="hit"}[5m])) by (cache)
/
(
  sum(rate(cache_get_total{result="hit"}[5m])) by (cache)
  +
  sum(rate(cache_get_total{result="miss"}[5m])) by (cache)
)
```

> `sessionStartTimes` 和 `firstTokenTimestamps` 的命中率应该很高（每个 messageId 查 1-2 次）。如果命中率低，说明大量 messageId 在 `onStreamStart` 后没有走到 `onFirstToken`/`onStreamEnd`（异常断连）。

#### Panel 22：缓存驱逐速率（Graph，按 cache 和 cause 分组）

```
sum(rate(cache_eviction_total[5m])) by (cache, cause)
```

Legend: `{{cache}} - {{cause}}`

> `cause` 通常是 `SIZE`（超过 maximumSize 淘汰）或 `EXPIRED`（TTL 过期）。
> - `EXPIRED` 驱逐多 = 正常（30 分钟 TTL 到期）
> - `SIZE` 驱逐多 = 异常（并发轮次超过 10000 上限，需要调大 max-sessions）

#### Panel 23：缓存写入速率（Graph，按 cache 分组）

```
sum(rate(cache_put_total[5m])) by (cache)
```

> `sessionStartTimes` 的 put 速率 = 对话发起速率（onTurnStart）。`processedFirstToken` 的 put 速率 = 首 token 到达速率。两者速率接近表示大部分对话都有流式响应。

---

## 六、Dashboard 变量定义

### 变量 1：serviceId（第三方接口筛选）

- Type: Query
- Query: `label_values(external_api_call_total, serviceId)`
- 开启 Multi-value + Include All option

### 变量 2：brain_tag（大脑标签筛选）

- Type: Query
- Query: `label_values(chat_stream_ttft_seconds, brain_tag)`
- 开启 Multi-value + Include All option

### 变量 3：url（URL 模板筛选）

- Type: Query
- Query: `label_values(external_api_call_total{serviceId=~"$serviceId"}, url)`
- 开启 Multi-value + Include All option
- 依赖变量：`$serviceId`

### 变量 4：cache（缓存筛选）

- Type: Query
- Query: `label_values(cache_size, cache)`
- 开启 Multi-value + Include All option

---

## 七、告警规则建议

### 7.1 第三方接口告警

| 告警名 | PromQL | 触发条件 | 级别 |
|--------|--------|----------|------|
| 第三方接口错误率 >5% | `1 - (success / total) > 0.05` | 5 分钟内某 serviceId 错误率超 5% | P1 |
| 第三方接口 P95 > 2s | `histogram_quantile(0.95, ...) > 2` | 5 分钟内某 serviceId P95 耗时超 2 秒 | P1 |
| 第三方接口无调用 | `rate(external_api_call_total[10m]) == 0` | 10 分钟内某 serviceId 无任何调用 | P2 |

PromQL 示例（错误率告警）：

```promql
(
  1 -
  (
    sum(rate(external_api_call_success_total[5m])) by (serviceId)
    /
    sum(rate(external_api_call_total[5m])) by (serviceId)
  )
) > 0.05
```

### 7.2 流式效率告警

| 告警名 | PromQL | 触发条件 | 级别 |
|--------|--------|----------|------|
| TTFT P95 > 3s | `histogram_quantile(0.95, ...) > 3` | 5 分钟内首 token 延迟 P95 超 3 秒 | P1 |
| Latency P95 > 30s | `histogram_quantile(0.95, ...) > 30` | 5 分钟内端到端延迟 P95 超 30 秒 | P1 |
| TPS < 5 | `avg_tps < 5` | 5 分钟内平均每秒 token 数低于 5 | P2 |

### 7.3 缓存告警

| 告警名 | PromQL | 触发条件 | 级别 |
|--------|--------|----------|------|
| sessionStartTimes 命中率 <90% | `hit_rate < 0.9` | 5 分钟内命中率低于 90% | P2 |
| sessionStartTimes SIZE 驱逐 | `rate(cache_eviction_total{cause="SIZE"}[5m]) > 0` | 出现 SIZE 驱逐（并发超上限） | P2 |
| cache_size 持续增长 | `deriv(cache_size[30m]) > 0` | 30 分钟内缓存条目数持续增长（泄漏） | P2 |

### 7.4 WS 连接告警

| 告警名 | PromQL | 触发条件 | 级别 |
|--------|--------|----------|------|
| skill-gateway 连接数为 0 | `gateway_ws_current_connections == 0` | skill-server 无 gateway 连接 | P0 |
| skill-gateway 连接数 >6 | `gateway_ws_current_connections > 6` | 接近连接池上限 8 | P2 |

---

## 八、Panel 布局建议

| Row | Panel | 类型 | 宽度 |
|-----|-------|------|------|
| **第三方 API** | Panel 1 调用总量 | Stat | 6 |
| | Panel 2 成功率 | Gauge | 6 |
| | Panel 3 失败速率 | Graph | 12 |
| | Panel 4 耗时 P50/P95/P99 | Graph | 12 |
| | Panel 5 URL 模板 P95 | Table | 12 |
| **对外 API** | Panel 7 P95 耗时 | Graph | 6 |
| | Panel 8 QPS | Graph | 6 |
| **流式效率** | Panel 9 TTFT P50/P95/P99 | Graph | 12 |
| | Panel 10 TTFT P95 按大脑 | Graph | 6 |
| | Panel 11 Latency P50/P95/P99 | Graph | 12 |
| | Panel 12 TPS 平均值 | Graph | 6 |
| | Panel 13 对话轮次速率 | Stat | 6 |
| **WS 连接** | Panel 15 skill 当前连接 | Gauge | 3 |
| | Panel 16 skill 累计连接 | Stat | 3 |
| | Panel 17 gateway 当前连接 | Gauge | 3 |
| | Panel 18 gateway 累计连接 | Stat | 3 |
| | Panel 19 连接数趋势 | Graph | 12 |
| **Cache** | Panel 20 缓存条目数 | Graph | 12 |
| | Panel 21 缓存命中率 | Graph | 6 |
| | Panel 22 驱逐速率 | Graph | 6 |
| | Panel 23 写入速率 | Graph | 12 |
