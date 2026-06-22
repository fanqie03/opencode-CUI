# PRD: ImMultiDeviceSyncService IM 调用参数对象化

## 目标

将 `ImMultiDeviceSyncService.push()` 内部构造 IM `/v1/app-notify` 请求体时使用的 `Map<String, Object>` 替换为类型化的 record 对象。

## 背景

当前 `ImMultiDeviceSyncService` 用 `LinkedHashMap` 逐字段 put 构造 IM API 请求体：

```java
Map<String, Object> notifyData = new LinkedHashMap<>();
notifyData.put("notify_type", ...);
notifyData.put("notify_content", ...);

Map<String, Object> body = new LinkedHashMap<>();
body.put("client_notify_id", ...);
body.put("notify_scope", ...);
// ...
```

问题：无类型安全、字段名依赖字符串、重构时 IDE 无法追踪。

## 需求

1. 新增 `AppNotifyData` record — 表示 `notify_data` JSON 结构
2. 新增 `AppNotifyRequest` record — 表示 IM `/v1/app-notify` 完整请求体
3. `ImMultiDeviceSyncService.push()` 使用 record 替代 Map
4. 序列化仍用 Jackson `ObjectMapper`

## 字段对照（来自 im-mulit-client-sync-api.md）

### AppNotifyRequest

| 字段 | 类型 | 必填 | 映射 |
|------|------|------|------|
| clientNotifyId | String | Y | UUID |
| notifyScope | int | Y | 配置 |
| notifyTenant | String | Y | 配置 |
| notifyAccounts | List\<String\> | N | targetAccount |
| notifyModule | String | Y | 配置 |
| notifyData | String | Y | JSON 序列化后的 AppNotifyData |

### AppNotifyData

| 字段 | 类型 | 说明 |
|------|------|------|
| notifyType | String | syncType.getType() |
| notifyContent | Map\<String, Object\> | syncContent 透传 |

## 范围外

- `notify_group` 字段（当前未使用）
- syncContent 的类型化（仍是 Map，留给调用方自行协商）
- 其他 service 的 Map 替换

## 验收标准

1. `AppNotifyRequest` + `AppNotifyData` record 编译通过
2. `ImMultiDeviceSyncService.push()` 使用 record 构造请求体
3. 现有 12 个测试全部通过
