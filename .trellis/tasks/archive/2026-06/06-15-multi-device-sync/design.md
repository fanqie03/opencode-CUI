# Design: 多端同步基础设施服务

## 1 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│ 调用方 (SessionDeletedSyncNotifier / 未来其他业务)              │
│   syncService.push(new SyncRequest(mode, type, content, acct)) │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
           CompositeMultiDeviceSyncService
            ┌─── Map<SyncMode, MultiDeviceSyncService> ───┐
            │  IM → ImMultiDeviceSyncService               │
            │  WS → WsMultiDeviceSyncService               │
            └──────────────────────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
   WsMultiDeviceSyncService   ImMultiDeviceSyncService
   RedisMessageBroker          RestTemplate
   .publishToUser()            POST /v1/app-notify
   user-stream:{userId}        IM 平台 → 多端广播
```

## 2 包结构与文件

```
skill-server/src/main/java/com/opencode/cui/skill/
  model/
    SyncMode.java              (enum)
    SyncType.java              (enum)
    SyncRequest.java           (record)
  service/sync/
    MultiDeviceSyncService.java        (interface)
    CompositeMultiDeviceSyncService.java
    WsMultiDeviceSyncService.java
    ImMultiDeviceSyncService.java
  config/
    MultiMultiSyncProperties.java   (@ConfigurationProperties prefix = skill.multi-sync)
```

## 3 模型设计

### 3.1 SyncMode

```java
public enum SyncMode {
    WS,
    IM
}
```

### 3.2 SyncType

```java
public enum SyncType {
    SESSION_UNREAD("session.unread"),
    SESSION_DELETED("session.deleted");

    private final String type;

    SyncType(String type) { this.type = type; }

    public String getType() { return type; }
}
```

### 3.3 SyncRequest

```java
public record SyncRequest(
    SyncMode syncMode,
    SyncType syncType,
    Map<String, Object> syncContent,
    String targetAccount
) {}
```

- `syncMode`：调用方指定通道
- `syncType`：业务类型，`getType()` 序列化到端侧
- `syncContent`：键值对，ws 模式 JSON 序列化后嵌入 ad-hoc `content`；im 模式序列化后嵌入 `notify_content`
- `targetAccount`：ws 模式用于 `publishToUser(account, ...)`；im 模式用于 `notify_accounts: [account]`

## 4 接口与实现

### 4.1 MultiDeviceSyncService

```java
public interface MultiDeviceSyncService {
    SyncMode getSyncMode();
    void push(SyncRequest request);
}
```

### 4.2 CompositeMultiDeviceSyncService

```java
@Primary  // 防止 IM/WS/Composite 同时注入 MultiDeviceSyncService 时 NoUniqueBeanDefinitionException
@Component
public class CompositeMultiDeviceSyncService implements MultiDeviceSyncService {

    private final Map<SyncMode, MultiDeviceSyncService> registry;

    public CompositeMultiDeviceSyncService(List<MultiDeviceSyncService> services) {
        this.registry = services.stream()
            .filter(s -> !(s instanceof CompositeMultiDeviceSyncService))  // 自过滤
            .collect(Collectors.toMap(
                MultiDeviceSyncService::getSyncMode,
                Function.identity()));
    }

    @Override
    public SyncMode getSyncMode() {
        throw new UnsupportedOperationException("Composite does not have a single mode");
    }

    @Override
    public void push(SyncRequest request) {
        MultiDeviceSyncService svc = registry.get(request.syncMode());
        if (svc == null) {
            log.warn("No sync service registered for mode: {}", request.syncMode());
            return;
        }
        svc.push(request);
    }
}
```

`@Primary` 使 Composite 成为调用方注入 `MultiDeviceSyncService` 时的默认入口。`List<MultiDeviceSyncService>` 注入不受影响，所有实现（含 Composite 自身）都会进入列表，需通过 `instanceof` 自过滤。新增叶子实现只需加 `@Component`。

### 4.3 WsMultiDeviceSyncService

```java
@Component
public class WsMultiDeviceSyncService implements MultiDeviceSyncService {

    private final RedisMessageBroker broker;
    private final ObjectMapper objectMapper;

    @Override
    public SyncMode getSyncMode() { return SyncMode.WS; }

    @Override
    public void push(SyncRequest request) {
        // targetAccount 空值守卫（与 MiniappDeliveryStrategy 一致）
        if (request.targetAccount() == null || request.targetAccount().isBlank()) {
            log.warn("WsMultiDeviceSyncService push skipped: targetAccount is null or blank, type={}",
                    request.syncType().getType());
            return;
        }
        // 复用现有 handleUserBroadcast ad-hoc 路径格式
        // { type, sessionId, content }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", request.syncType().getType());

        String sessionId = (String) request.syncContent().get("welinkSessionId");
        if (sessionId != null) {
            envelope.put("sessionId", sessionId);
        }

        envelope.set("content", objectMapper.valueToTree(request.syncContent()));
        broker.publishToUser(request.targetAccount(), envelope.toString());
    }
}
```

### 4.4 ImMultiDeviceSyncService

```java
@Component
public class ImMultiDeviceSyncService implements MultiDeviceSyncService {

    private final RestTemplate restTemplate;
    private final MultiSyncProperties syncProperties;
    private final ObjectMapper objectMapper;
    private final String imToken;

    public ImMultiDeviceSyncService(RestTemplate restTemplate,
            ObjectMapper objectMapper,
            MultiSyncProperties syncProperties,
            @Value("${skill.im.token:}") String imToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.syncProperties = syncProperties;
        this.imToken = imToken;
    }

    @Override
    public SyncMode getSyncMode() { return SyncMode.IM; }

    @Override
    public void push(SyncRequest request) {
        MultiSyncProperties.Im.AppNotify appNotify = syncProperties.getIm().getAppNotify();
        String appNotifyUrl = appNotify.getUrl();
        if (appNotifyUrl == null || appNotifyUrl.isBlank()) {
            log.warn("[SKIP] app_notify_url not configured");
            return;
        }

        // 类型化请求体（@JsonProperty 指定 snake_case 字段名）
        AppNotifyData notifyData = new AppNotifyData(
            request.syncType().getType(),   // notify_type
            request.syncContent()           // notify_content
        );
        String notifyDataJson = objectMapper.writeValueAsString(notifyData);

        AppNotifyRequest body = new AppNotifyRequest(
            UUID.randomUUID().toString(),   // client_notify_id
            appNotify.getScope(),           // notify_scope
            appNotify.getTenant(),          // notify_tenant
            request.targetAccount() != null && !request.targetAccount().isBlank()
                ? List.of(request.targetAccount()) : null,  // notify_accounts
            appNotify.getModule(),          // notify_module
            notifyDataJson                  // notify_data (JSON string)
        );

        // HTTP POST，响应使用类型化 ImAppNotifyResponse
        ResponseEntity<ImAppNotifyResponse> response = restTemplate.postForEntity(
            appNotifyUrl, new HttpEntity<>(body, headers), ImAppNotifyResponse.class);
        // 检查 response.getBody().error() 判断业务错误
    }
}
```

**模型类**（model 包，均使用 `@JsonProperty` 显式指定 wire format）:

```java
// 请求体
public record AppNotifyRequest(
    @JsonProperty("client_notify_id") String clientNotifyId,
    @JsonProperty("notify_scope") int notifyScope,
    @JsonProperty("notify_tenant") String notifyTenant,
    @JsonProperty("notify_accounts") List<String> notifyAccounts,
    @JsonProperty("notify_module") String notifyModule,
    @JsonProperty("notify_data") String notifyData
) {}

// notify_data JSON 对象
public record AppNotifyData(
    @JsonProperty("notify_type") String notifyType,
    @JsonProperty("notify_content") Map<String, Object> notifyContent
) {}

// IM API 响应（类型化 error 检查，替代 JsonNode）
public record ImAppNotifyResponse(
    @JsonProperty("error") ErrorInfo error
) {
    public record ErrorInfo(
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_msg") String errorMsg
    ) {}
}
```

## 5 配置

### 5.1 MultiSyncProperties

```java
@ConfigurationProperties(prefix = "skill.multi-sync")
public class MultiSyncProperties {
    private String mode = "ws";  // ws | im
    private Im im = new Im();

    public static class Im {
        private AppNotify appNotify = new AppNotify();

        public static class AppNotify {
            /** IM app-notify 完整请求 URL，默认通过 skill.im.api-url 拼接 */
            private String url = "";
            private String tenant;
            private String module;
            private int scope = 2;
        }
    }
}
```

### 5.2 application.yml

```yaml
skill:
  multi-sync:
    mode: ${SKILL_MULTI_SYNC_MODE:ws}
    im:
      app-notify:
        url: ${SKILL_MULTI_SYNC_IM_APP_NOTIFY_URL:${skill.im.api-url}/v1/app-notify}
        tenant: ${SKILL_MULTI_SYNC_IM_APP_NOTIFY_TENANT:}
        module: ${SKILL_MULTI_SYNC_IM_APP_NOTIFY_MODULE:}
        scope: ${SKILL_MULTI_SYNC_IM_APP_NOTIFY_SCOPE:2}
```

## 6 WS 消息格式

复用 `SkillStreamHandler.handleUserBroadcast` 现有 ad-hoc 路径（不改 handler）：

```json
{
  "type": "session.unread",
  "sessionId": "123456789",
  "content": {
    "welinkSessionId": "123456789",
    "unreadCount": 1,
    "maxSeq": 15,
    "assistantAccount": "assistant_xxx"
  }
}
```

handler 检测 `type` + `sessionId` + `content` → 构建 ad-hoc `StreamMessage` → `pushStreamMessage()`。

## 7 IM API 调用

```
POST ${skill.multi-sync.im.app-notify.url}
Authorization: Bearer ${skill.im.token}
```

URL 由配置项 `skill.multi-sync.im.app-notify.url` 完整指定（例如 `${skill.im.api-url}/v1/app-notify`），不在代码中硬编码路径。

请求体使用类型化 `AppNotifyRequest` record（`@JsonProperty` 注解），响应使用 `ImAppNotifyResponse` record（`error.error_code` / `error.error_msg`）替代 `JsonNode` 手动遍历。

IM 调用失败：log.error `[EXT_CALL]`，不抛异常，不阻塞 WS 路径。

## 8 与 uread-badge 的整合点

uread-badge 的 `UnreadPushListener` / `ReadReportedListener` 从直接持有具体实现改为：

```java
// 之前（uread-badge design）
multiDeviceSyncService.pushSessionUnread(userId, sessionId, unreadCount, maxSeq, assistant);

// 之后（整合本任务）
syncService.push(new SyncRequest(
    SyncMode.valueOf(syncProperties.getMode().toUpperCase()),
    SyncType.SESSION_UNREAD,
    Map.of("welinkSessionId", sessionId.toString(),
           "unreadCount", unreadCount,
           "maxSeq", maxSeq,
           "assistantAccount", assistant != null ? assistant : ""),
    userId
));
```
