# 中文注释补全计划

## 目标

对 `ai-gateway` 和 `skill-server` 两个服务的**全部 Java 文件**（含测试类）补全中文注释：
- 每个类添加类级别 Javadoc（说明职责和核心功能）
- 每个方法添加方法级别 Javadoc（说明用途、参数、返回值）
- 关键节点（分支逻辑、外部调用、异常处理）添加行内注释
- 已有中文注释保留不动，英文注释翻译为中文

## 文件范围

| 服务         | main    | test   | 合计    |
| ------------ | ------- | ------ | ------- |
| ai-gateway   | 33      | 15     | 48      |
| skill-server | 70      | 22     | 92      |
| **总计**     | **103** | **37** | **140** |

## 注释规范

### 类级别 — Javadoc

```java
/**
 * AI Gateway 的 Agent WebSocket 处理器。
 * 管理 Agent 端 WebSocket 连接的生命周期，包括握手鉴权、消息路由和连接关闭。
 *
 * <p>核心职责：
 * 1. 握手阶段校验 AK/SK 凭证
 * 2. 注册 Agent 连接到 Redis
 * 3. 将 Agent 消息路由到 Skill Server
 */
```

### 方法级别 — Javadoc

```java
/**
 * 校验 WebSocket 握手请求的 AK/SK 凭证。
 * 从 URL 参数中提取 ak 和 sign，通过 AkSkAuthService 验证合法性。
 *
 * @param request  HTTP 请求，包含 ak 和 sign 参数
 * @param response HTTP 响应，鉴权失败时设置 401
 * @param wsHandler WebSocket 处理器
 * @param attributes 握手属性，鉴权成功后写入 ak 和 userId
 * @return true=鉴权通过允许握手；false=拒绝连接
 */
```

### 行内注释 — 关键节点

```java
// 从 Redis 查询路由缓存，命中则直接转发
String cachedLinkId = redisMessageBroker.getRouteCache(toolSessionId);

// 外部 API 调用：通过身份服务获取用户 ID
String userId = identityApiClient.getUserIdByMac(macAddress);
```

### 测试类

```java
/**
 * AgentRegistryService 的单元测试。
 * 覆盖 Agent 注册、注销、查询在线状态等核心场景。
 */
class AgentRegistryServiceTest {

    /** 验证正常注册流程：ak 有效时应成功存入 Redis */
    @Test
    void registerShouldStoreConnectionWhenAkIsValid() { ... }
}
```

## 执行顺序

按**依赖层级从底到上**执行，减少对上层理解的依赖。每个层级按文件分批处理。

### 第 1 批：Model / DTO / 常量（约 30 文件）

最简单，全是数据结构和常量定义。

| #   | 服务         | 文件                                                                                                                       | 优先级 |
| --- | ------------ | -------------------------------------------------------------------------------------------------------------------------- | ------ |
| 1   | ai-gateway   | `model/` (6 文件): AgentConnection, AgentStatusResponse, AgentSummaryResponse, AkSkCredential, ApiResponse, GatewayMessage | P1     |
| 2   | ai-gateway   | `logging/` (3 文件): LogTimer, MdcConstants, MdcHelper                                                                     | P1     |
| 3   | skill-server | `model/` (17 文件): 全部 Model 类                                                                                          | P1     |
| 4   | skill-server | `logging/` (4 文件): LogTimer, MdcConstants, MdcHelper, SensitiveDataMasker                                                | P1     |

### 第 2 批：Repository / Config（约 20 文件）

数据访问层和配置类。

| #   | 服务         | 文件                        | 优先级 |
| --- | ------------ | --------------------------- | ------ |
| 5   | ai-gateway   | `repository/` (2 文件)      | P1     |
| 6   | ai-gateway   | `config/` (5 文件)          | P1     |
| 7   | skill-server | `repository/` (5 文件)      | P1     |
| 8   | skill-server | `config/` (8 文件)          | P1     |
| 9   | 两个服务     | Application 启动类 (2 文件) | P2     |

### 第 3 批：Service 层（约 30 文件）

核心业务逻辑，方法最多、内容最复杂。

| #   | 服务         | 文件                                                                                                                                                                                                                                                        | 优先级 |
| --- | ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| 10  | ai-gateway   | `service/` (11 文件): AgentRegistryService, AkSkAuthService, DeviceBindingService, EventRelayService, GatewayInstanceRegistry, IdentityApiClient, LegacySkillRelayStrategy, RedisMessageBroker, SkillRelayService, SkillRelayStrategy, SnowflakeIdGenerator | P0     |
| 11  | skill-server | `service/` (20+ 文件): GatewayRelayService, GatewayMessageRouter, ImSessionManager, SkillSessionService, SkillMessageService, MessagePersistenceService, OpenCodeEventTranslator 等                                                                         | P0     |

### 第 4 批：Controller / WebSocket 处理器（约 8 文件）

对外接口层。

| #   | 服务         | 文件                                    | 优先级 |
| --- | ------------ | --------------------------------------- | ------ |
| 12  | ai-gateway   | `controller/` (1 文件) + `ws/` (2 文件) | P0     |
| 13  | skill-server | `controller/` (4 文件) + `ws/` (2 文件) | P0     |

### 第 5 批：测试类（约 37 文件）

| #   | 服务         | 文件              | 优先级 |
| --- | ------------ | ----------------- | ------ |
| 14  | ai-gateway   | `test/` (15 文件) | P1     |
| 15  | skill-server | `test/` (22 文件) | P1     |

## 工作量估算

| 文件类型          | 文件数   | 平均耗时/文件          | 总计         |
| ----------------- | -------- | ---------------------- | ------------ |
| Model/DTO/常量    | ~30      | 低（字段描述）         | ~30 min      |
| Repository/Config | ~20      | 低                     | ~20 min      |
| Service（核心）   | ~30      | 高（方法多、逻辑复杂） | ~120 min     |
| Controller/WS     | ~8       | 中（部分已有注释）     | ~30 min      |
| 测试类            | ~37      | 中（每个测试方法描述） | ~60 min      |
| **总计**          | **~140** | —                      | **~260 min** |

## 验证方式

1. 编译通过 — `mvn compile` 两个服务均无错误
2. 抽样检查 — 随机查看 5 个文件，确认注释覆盖完整
3. grep 检查 — 搜索无注释的 public 方法确认遗漏数为 0

## 注意事项

> [!IMPORTANT]
> - 仅修改注释，**不改任何业务逻辑代码**
> - 已有中文注释**保留不动**
> - 英文注释直接**翻译为中文**（不是双语并存）
> - record 类的组件用 `@param` 说明每个字段
> - Lombok `@Data` 类只添加类级别说明和关键字段注释
