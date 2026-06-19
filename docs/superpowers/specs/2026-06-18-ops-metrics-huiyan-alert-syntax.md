# 运维埋码 — 慧眼告警语法清单

> 配套文档：`001运维埋码-需求分析与设计.md` §4.7
> 日期：2026-06-18
> 状态：设计阶段

---

## 一、背景

慧眼是基于日志关键词搜索触发告警的系统。通过搜索日志中的关键词组合（如 `loglevel:"ERROR" AND message:"im"`），可以触发对应的告警规则，提示某个业务有异常。

本文档列出 skill-server 接入 Prometheus 埋码后，可用的慧眼告警语法清单，覆盖**业务异常**和**第三方接口异常**两类场景。

---

## 二、日志格式说明

### 2.1 当前日志格式（改造前）

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [${SERVICE_NAME}] [${INSTANCE_ID}] [%X{traceId}] [%X{sessionId}] [%X{ak}] [%X{userId}] [%X{scenario}] %-5level %logger{36}.%method - %msg%n
```

MDC 字段：`traceId` / `sessionId` / `ak` / `userId` / `scenario`

### 2.2 改造后日志格式（新增 businessDomain）

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [${SERVICE_NAME}] [${INSTANCE_ID}] [%X{traceId}] [%X{sessionId}] [%X{ak}] [%X{userId}] [%X{scenario}] [%X{businessDomain}] %-5level %logger{36}.%method - %msg%n
```

新增 `[%X{businessDomain}]` 占位符，取值为接口级 serviceId（如 `im_group_chat`、`gateway_ws_invoke`），未设置时渲染为空 `[]`。

### 2.3 日志行示例

```
2026-06-18 10:30:45.123 [http-nio-8080-exec-1] [skill-server] [pod-abc] [trace-789] [sess-456] [ak-123] [user-001] [rest-post] [im_group_chat] ERROR c.o.c.s.service.ImOutboundService.sendTextToIm - [EXT_CALL] im_group_chat failed: durationMs=3000, error=Connection refused
```

---

## 三、业务异常 vs 第三方接口异常告警语法表

### 3.1 IM 业务域

| 异常类型 | 慧眼语法 | 触发场景 | 报错示例 |
|----------|----------|----------|----------|
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"im_group_chat" AND message:"[EXT_CALL]"` | 群聊消息发送 HTTP 调用失败（超时/4xx/5xx） | `[EXT_CALL] im_group_chat failed: durationMs=3000, error=Connection refused` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"im_direct_chat" AND message:"[EXT_CALL]"` | 单聊消息发送 HTTP 调用失败 | `[EXT_CALL] im_direct_chat failed: durationMs=2000, error=timeout` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"im_message_send" AND message:"[EXT_CALL]"` | IM 消息发送 HTTP 调用失败 | `[EXT_CALL] im_message_send failed: durationMs=1500, error=500` |
| 业务异常 | `loglevel:"ERROR" AND businessDomain:"im_group_chat" AND message:"[ERROR]"` | IM 消息构建失败、targetType 缺失等 | `[ERROR] ImOutboundService.sendTextToIm: reason=target_type_missing, sessionId=sess-456` |

### 3.2 Gateway 业务域

| 异常类型 | 慧眼语法 | 触发场景 | 报错示例 |
|----------|----------|----------|----------|
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_agents_list" AND message:"[EXT_CALL]"` | 查询在线 Agent 列表 REST 调用失败 | `[EXT_CALL] gateway_agents_list failed: durationMs=5000, error=timeout` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_agents_by_ak" AND message:"[EXT_CALL]"` | 按 AK 查询 Agent REST 调用失败 | `[EXT_CALL] gateway_agents_by_ak failed: durationMs=3000, error=404` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_agent_availability" AND message:"[EXT_CALL]"` | 查询 Agent 可及性 REST 调用失败 | `[EXT_CALL] gateway_agent_availability failed: durationMs=4000, error=Connection refused` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_ws_invoke" AND message:"[EXT_CALL]"` | Gateway WS invoke 指令发送失败 | `[EXT_CALL] gateway_ws_invoke failed: durationMs=100, error=connection_closed` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_ws_route_confirm" AND message:"[EXT_CALL]"` | Gateway WS 路由确认失败 | `[EXT_CALL] gateway_ws_route_confirm failed: durationMs=50, error=send_failed` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"gateway_ws_route_reject" AND message:"[EXT_CALL]"` | Gateway WS 路由拒绝失败 | `[EXT_CALL] gateway_ws_route_reject failed: durationMs=50, error=send_failed` |
| 业务异常 | `loglevel:"ERROR" AND businessDomain:"gateway_ws_invoke" AND message:"[ERROR]"` | Gateway 消息解析失败、路由异常等 | `[ERROR] GatewayRelayService.handleGatewayMessage: reason=parse_failed, length=1024` |

### 3.3 业务中心域

| 异常类型 | 慧眼语法 | 触发场景 | 报错示例 |
|----------|----------|----------|----------|
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"business_center_assistant_info" AND message:"[EXT_CALL]"` | 助手信息查询 HTTP 调用失败 | `[EXT_CALL] business_center_assistant_info failed: durationMs=2000, error=404` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"business_center_instance_query" AND message:"[EXT_CALL]"` | 助手实例查询 HTTP 调用失败 | `[EXT_CALL] business_center_instance_query failed: durationMs=3000, error=timeout` |
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"business_center_persona_query" AND message:"[EXT_CALL]"` | Persona 查询 HTTP 调用失败 | `[EXT_CALL] business_center_persona_query failed: durationMs=1500, error=500` |
| 业务异常 | `loglevel:"ERROR" AND businessDomain:"business_center_assistant_info" AND message:"[ERROR]"` | 助手信息缺失、AK 无效等 | `[ERROR] AssistantInfoService.fetchFromUpstream: reason=not_found, ak=ak-123` |

### 3.4 埋码上报域

| 异常类型 | 慧眼语法 | 触发场景 | 报错示例 |
|----------|----------|----------|----------|
| 第三方接口异常 | `loglevel:"ERROR" AND businessDomain:"telemetry_welink_upload" AND message:"[EXT_CALL]"` | WeLink 埋码上报 HTTP 调用失败 | `[EXT_CALL] telemetry_welink_upload failed: durationMs=1500, error=401` |

---

## 四、现有可用告警语法（无需 code change）

以下告警语法基于现有 MDC 字段，现在就能用（不依赖本次 businessDomain 改动）：

| 告警 | 慧眼语法 |
|------|----------|
| 全部 skill-server 错误 | `loglevel:"ERROR" AND service:"skill-server"` |
| 特定 session 的错误 | `loglevel:"ERROR" AND sessionId:"sess-xxx"` |
| 特定 AK 的错误 | `loglevel:"ERROR" AND ak:"ak-xxx"` |
| WS 网关相关错误 | `loglevel:"ERROR" AND scenario:"ws-gateway-*"` |
| REST 请求错误 | `loglevel:"ERROR" AND scenario:"rest-post"` |
| 外部调用失败（全部） | `loglevel:"ERROR" AND message:"[EXT_CALL]"` |
| 会话重建失败 | `loglevel:"ERROR" AND message:"rebuild session"` |
| 认证失败 | `loglevel:"ERROR" AND message:"[AUTH_FAIL]"` |

---

## 五、按业务域聚合的告警语法（通配）

如果慧眼支持前缀匹配或通配，可以按业务域聚合查询（本次改动后可用）：

| 业务域 | 慧眼语法（前缀匹配） | 说明 |
|--------|----------------------|------|
| IM 全部 | `loglevel:"ERROR" AND businessDomain:"im_*"` | 匹配 im_group_chat / im_direct_chat / im_message_send |
| Gateway 全部 | `loglevel:"ERROR" AND businessDomain:"gateway_*"` | 匹配 6 个 gateway 接口 |
| 业务中心全部 | `loglevel:"ERROR" AND businessDomain:"business_center_*"` | 匹配 3 个业务中心接口 |
| 埋码上报 | `loglevel:"ERROR" AND businessDomain:"telemetry_*"` | 匹配 telemetry_welink_upload |

> **注**：若慧眼不支持前缀匹配，则需要为每个接口单独配置告警规则，或使用 OR 语法组合。

---

## 六、告警配置建议

### 6.1 告警级别

| 级别 | 触发条件 | 通知方式 |
|------|----------|----------|
| P0（紧急） | `businessDomain:"gateway_ws_invoke" AND message:"[EXT_CALL]"` 持续 1 分钟 | 电话 + IM |
| P1（严重） | 任一 `businessDomain` 的 `[EXT_CALL]` 持续 5 分钟 | IM |
| P2（警告） | `businessDomain:"telemetry_welink_upload"` 的 `[EXT_CALL]` 持续 10 分钟 | IM（埋码上报失败不影响业务） |

### 6.2 告警粒度建议

- **第三方接口异常**（`[EXT_CALL]`）：每个接口单独配告警，因为修复方式不同（IM 超时查 IM 服务，Gateway 超时查 Gateway 服务）
- **业务异常**（`[ERROR]`）：可按业务域聚合，因为业务异常通常是代码逻辑问题，需要开发排查

---

## 七、维护说明

本文档随 `MetricServiceEnum` 枚举值变化同步更新。新增枚举值时：
1. 在 §三 对应业务域表格中追加告警语法行
2. 在 §五 聚合语法中确认前缀匹配是否覆盖
3. 在 §六 告警配置中评估是否需要新增告警规则
