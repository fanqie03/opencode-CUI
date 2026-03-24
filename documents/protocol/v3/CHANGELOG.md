# v2 → v3 变更日志

> 分支：`route-redesign-0321`

## 概要

v3 协议文档反映了并发注册保护、认证缓存分级、设备绑定优化、连接注册 (conn:ak) 及日志规范化的所有改动。核心变化集中在 Layer 3（GW↔Plugin）和 Layer 2（SS↔GW），Layer 1（Miniapp↔SS）和 Layer 4（Plugin↔OpenCode）无协议变更。

## 受影响文档

| 文档 | 变更程度 | 说明 |
|------|---------|------|
| 03-gateway-plugin.md | **大幅更新** | 并发注册分布式锁、conn:ak 绑定/刷新/条件删除、AkSk 双模式认证、设备绑定校验 |
| 02-skillserver-gateway.md | **中等更新** | conn:ak 路由发现、Mesh 路由被动学习补充说明 |
| 06-end-to-end-flows.md | **中等更新** | Agent 注册场景增加分布式锁步骤和 conn:ak 绑定 |
| 07-message-type-lifecycle.md | **中等更新** | register/heartbeat 生命周期增加 conn:ak 操作描述 |
| 01-miniapp-skillserver.md | 无变更 | 原样保留 |
| 04-plugin-opencode.md | 无变更 | 原样保留 |
| 05-opencode-to-custom-protocol-mapping.md | 无变更 | 原样保留 |

## 详细变更

### 1. 并发注册分布式锁（新增）

- Agent 注册 (`handleRegister`) 全流程被 Redis 分布式锁保护
- 锁 Key: `gw:register:lock:{ak}`，TTL 10s
- 锁 Value: `{gatewayInstanceId}:{threadId}`（唯一 owner 标识）
- 获取方式: `SET NX` + `EX 10`
- 释放方式: Lua 脚本原子校验 owner 后 DEL（防止超时后误释放其他线程/实例的锁）
- 获取失败 → `registerRejected("concurrent_registration")` + `close(4409)`
- 保护范围: 设备绑定验证 → 重复连接检查 → 数据库注册 → 本地注册 → Redis 绑定 → 通知

### 2. conn:ak 连接注册（新增）

- 注册成功后: `Redis SET conn:ak:{ak} = {gatewayInstanceId} EX 120`
- 心跳时: `Redis EXPIRE conn:ak:{ak} 120`（刷新 TTL）
- 断连时: Lua 脚本条件删除 — 仅当 value == 当前 gatewayInstanceId 时才 DEL
- 用途:
  - 其他 Gateway 实例可通过 `GET conn:ak:{ak}` 确定 Agent 连接在哪个实例
  - 防止 Agent 快速切换实例时旧实例误删新实例的绑定
  - 为未来精确路由（替代广播）预留基础

### 3. AkSk 认证双模式（重构）

- **GATEWAY 模式**（默认）:
  - 本地 HMAC-SHA256 验签
  - 从 `ak_sk_credential` 表查找 SK
  - `stringToSign = ak + ts + nonce` → `HMAC-SHA256(SK, stringToSign)` → Base64 比较
  - 恒定时间比较防时序攻击

- **REMOTE 模式**（外部认证）:
  - L1: Caffeine 本地缓存（TTL 300s，上限 10000 条）→ 命中则信任
  - L2: Redis 缓存 `auth:identity:{ak}`（TTL 3600s）→ 命中则回填 L1
  - L3: 外部身份 API `POST /appstore/wecodeapi/open/identity/check`（Bearer Token 认证）
    - 请求: `{ak, timestamp, nonce, sign}`
    - 响应: `{code, data: {checkResult, userId}}`
    - 成功 → 回填 L1 + L2
    - 失败/超时 → 拒绝认证
  - L4: 隐式拒绝（无本地 DB 降级）

- **共享校验**（两种模式均执行）:
  - 时间戳窗口: `|now - ts| ≤ 300s`
  - Nonce 防重放: `Redis SET NX gw:auth:nonce:{nonce} "1" EX 300`

> **注意：** V2 文档中提到的 `gateway.auth.skip-verification` 调试开关已在代码中移除，V3 不再支持。

### 4. 设备绑定校验优化（重构）

- `DeviceBindingService.validate(ak, macAddress, toolType)`:
  - 功能开关: `gateway.device-binding.enabled`（默认 false）
  - 未启用 → 直接通过
  - 查询 `agent_connection` 最新记录（`findLatestByAkId`）
  - 首次注册（无记录）→ 通过
  - 已有记录 → MAC 地址匹配（忽略大小写）+ toolType 匹配
  - 异常 → Fail-Open（允许通过 + 警告日志）

### 5. MDC 上下文保护（修复）

- `EventRelayService.relayToSkillServer()` 在转发前 `MdcHelper.snapshot()` 保存调用方上下文
- 转发完毕后 `MdcHelper.restore(previousMdc)` 恢复
- 修复了消息转发过程中清除调用方 traceId、ak 等 MDC 字段的问题

### 6. 日志规范化（重构）

- 统一 `[ENTRY]`/`[EXIT]`/`[ERROR]`/`[EXT_CALL]` 前缀格式
- 所有日志通过 MDC 携带 traceId、sessionId (welinkSessionId)、ak
- 外部 API 调用记录 durationMs
- logback-spring.xml 统一格式: `[SERVICE] [traceId] [sessionId] [ak] class.method - message`

### 7. 配置变更

- 新增 `gateway.auth.mode` — 认证模式选择（gateway / remote）
- 新增 `gateway.auth.identity-api.base-url` — 外部认证 API 地址
- 新增 `gateway.auth.identity-api.bearer-token` — 外部 API Bearer Token
- 新增 `gateway.auth.identity-api.timeout-ms` — 外部 API 超时（默认 3000ms）
- 新增 `gateway.auth.identity-cache.l1-ttl-seconds` — Caffeine 缓存 TTL（默认 300s）
- 新增 `gateway.auth.identity-cache.l1-max-size` — Caffeine 缓存上限（默认 10000）
- 新增 `gateway.auth.identity-cache.l2-ttl-seconds` — Redis 缓存 TTL（默认 3600s）
- 新增 `gateway.device-binding.enabled` — 设备绑定开关（默认 false）
- 新增 `gateway.agent.register-timeout-seconds` — 注册超时（默认 10s）

### 8. Redis Key 变更

| Key | 类型 | 用途 | TTL | 状态 |
|-----|------|------|-----|------|
| `gw:register:lock:{ak}` | String | 并发注册分布式锁 | 10s | **v3 新增** |
| `conn:ak:{ak}` | String | Agent 连接实例绑定 | 120s | **v3 新增** |
| `auth:identity:{ak}` | String | REMOTE 模式 L2 认证缓存 | 3600s | **v3 新增** |
| `gw:auth:nonce:{nonce}` | String | Nonce 防重放 | 300s | 保留 |
| `agent:{ak}` | Pub/Sub | Agent 消息投递 | — | 保留 |
| `gw:agent:user:{ak}` | String | AK→userId 映射 | 无 | 保留 |
| `gw:instance:{instanceId}` | String | Gateway 实例自注册 | 30s | 保留(v2) |
| `gw:relay:{instanceId}` | Pub/Sub | 跨实例消息中继 | — | 保留(v2) |
| `gw:source:owner:*` | String | Legacy 策略 owner 心跳 | 30s | 保留(v2) |
| `gw:source:owners:*` | Set | Legacy 策略 owner 注册表 | — | 保留(v2) |
