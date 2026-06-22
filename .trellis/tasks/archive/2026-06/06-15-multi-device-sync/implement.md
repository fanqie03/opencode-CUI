# Implement: 多端同步基础设施服务

## 实施清单

### 1. model 层（4 个文件，无外部依赖）

- [ ] `SyncMode.java` — enum `WS`, `IM`
- [ ] `SyncType.java` — enum，`SESSION_UNREAD("session.unread")`，含 `type` 属性
- [ ] `SyncRequest.java` — record `(SyncMode, SyncType, Map<String,Object>, String)`

### 2. service.sync 层（4 个文件）

- [ ] `MultiDeviceSyncService.java` — 接口 `getSyncMode()` + `push(SyncRequest)`
- [ ] `CompositeMultiDeviceSyncService.java` — 收集所有实现，`getSyncMode()` 自注册路由表，按 `request.syncMode()` 分发
- [ ] `WsMultiDeviceSyncService.java` — `RedisMessageBroker.publishToUser()`，构造 `{type, sessionId, content}` JSON
- [ ] `ImMultiDeviceSyncService.java` — 独立 `RestTemplate` 调用，类型化请求 `AppNotifyRequest` + 响应 `ImAppNotifyResponse`，URL 由 `app-notify.url` 配置

### 3. config 层（1 个文件）

- [ ] `SyncProperties.java` — `@ConfigurationProperties("skill.sync")`，含 `mode` + `im.app-notify`

### 4. 测试

- [ ] `CompositeMultiDeviceSyncServiceTest` — 路由正确性，未知 mode 降级
- [ ] `WsMultiDeviceSyncServiceTest` — JSON envelope 格式验证
- [ ] `ImMultiDeviceSyncServiceTest` — AppNotify 请求体格式验证

## 验证命令

```bash
# 编译
cd skill-server && mvn compile

# 类型检查 + 测试
cd skill-server && mvn test -pl . -- also-make

# 特定测试类
cd skill-server && mvn test -Dtest="CompositeMultiDeviceSyncServiceTest,WsMultiDeviceSyncServiceTest,ImMultiDeviceSyncServiceTest"
```

## 风险点

- `ImMultiDeviceSyncService` 依赖 `skill.sync.im.app-notify.url`（完整 URL，不硬编码路径）和 `skill.im.token` 配置
- `SyncProperties` 需在 `@EnableConfigurationProperties` 中注册

## 后续

uread-badge 任务整合时需：
1. 替换 `MultiDeviceSyncService.pushSessionUnread(...)` 为 `SyncRequest` 调用
2. 移除 `@Qualifier("ws")` / `@Qualifier("im")` 注入
3. 更新 `im-mulit-client-sync-api.md` 引用到本任务
