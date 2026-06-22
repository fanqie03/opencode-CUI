# PRD: 多端同步基础设施服务

> 从 `06-10-skill-session-unread-badge` 的 `MultiDeviceSyncService` 抽离，升级为通用的多端同步基础设施。

## 目标

构建独立于业务的多端同步基础设施，支持 IM / WS 两种同步通道，以接口 + 枚举 + 复合路由实现可扩展架构。调用方通过 `SyncRequest` 参数对象指定同步模式和类型。

## 需求

1. **SyncMode 枚举**（model 包）：`IM`、`WS`
2. **SyncType 枚举**（model 包）：枚举项含 `type` 属性（String），序列化时取 `type` 传到端侧。初始已知类型：`SESSION_UNREAD("session.unread")`，后续按需追加
3. **SyncRequest record**（model 包）：`syncMode`、`syncType`、`syncContent`（Map）、`targetAccount`
4. **MultiDeviceSyncService 接口**（service.sync 包）：`getSyncMode()` + `push(SyncRequest)`
5. **CompositeMultiDeviceSyncService**：收集所有实现，通过 `getSyncMode()` 注册路由表，按 `request.syncMode` 分发
6. **WsMultiDeviceSyncService**：`RedisMessageBroker.publishToUser()` 直接发布 JSON，复用现有 ad-hoc 路径（`{type, sessionId, content}`）
7. **ImMultiDeviceSyncService**：独立实现 HTTP 调用 `/v1/app-notify`，不侵入 `ImOutboundService`
8. **SyncProperties**（config 包）：`skill.sync.*` 配置命名空间

## 核心接口

```java
public enum SyncMode { IM, WS }

public enum SyncType {
    SESSION_UNREAD("session.unread");
    private final String type;
    // getter
}

public record SyncRequest(
    SyncMode syncMode,
    SyncType syncType,
    Map<String, Object> syncContent,
    String targetAccount
) {}

public interface MultiDeviceSyncService {
    SyncMode getSyncMode();
    void push(SyncRequest request);
}
```

## 确认的决策

| # | 决策 | 结论 |
|---|------|------|
| 1 | syncType | Enum + `type` 属性传递端侧，初始 `SESSION_UNREAD` |
| 2 | syncMode | Enum `IM` / `WS` |
| 3 | 包结构 | enum + record 放 `model`；接口 + 实现放 `service.sync`；配置放 `config` |
| 4 | IM 实现 HTTP | ImMultiDeviceSyncService 内部独立实现，不复用 ImOutboundService |
| 5 | 配置 | `SyncProperties` 类，`skill.sync.*` 命名空间 |
| 6 | WS 实现 | 直接 `RedisMessageBroker.publishToUser()`，复用现有 handler ad-hoc 路径 |
| 7 | WS envelope | 复用现有格式 `{type, sessionId, content}`，不改 SkillStreamHandler |
| 8 | 复合路由 | `getSyncMode()` 自注册，按 `request.syncMode` 分发 |

## 与 uread-badge 的关系

uread-badge 的 Listener 构造 `SyncRequest` 调用：
```java
syncService.push(new SyncRequest(
    SyncMode.valueOf(syncProperties.getMode().toUpperCase()),
    SyncType.SESSION_UNREAD,
    Map.of("welinkSessionId", sessionId, "unreadCount", count, "maxSeq", maxSeq),
    userId
));
```

## 范围外

- 未读角标业务逻辑
- Lua / Redis Hash / 已读上报
- 前端 readMessageSeq / SessionSidebar
- `POST /unread` / `POST /{id}/read` 端点
- SkillStreamHandler 改动

## 验收标准

1. `MultiDeviceSyncService` 接口 + 三个实现 (Composite/Ws/Im) 可编译
2. `WS` 模式：`RedisMessageBroker.publishToUser()` 被调用，消息格式为 `{type, sessionId, content}`
3. `IM` 模式：`/v1/app-notify` 被调用，请求体符合 IM API 规范
4. `CompositeMultiDeviceSyncService` 按 `request.syncMode` 正确路由
5. 新增 `SyncType` 只需加枚举项，无需改接口和复合类
6. 新增 `SyncMode` 实现只需实现接口并声明 `getSyncMode()`，复合类自动收录
