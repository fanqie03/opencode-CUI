# Design: gateway plugin 注册入库 pluginVersion/sdkVersion

## 架构：透传落库，最小侵入

Plugin 在 WebSocket register 消息中新增 `pluginVersion` 和 `sdkVersion` 字段。Gateway 沿现有注册链路逐层透传，最终在 `agent_connection` 表新增/更新时持久化。新字段仅用于排查问题，不参与任何业务逻辑。

```
Plugin (WebSocket)                Gateway                           MySQL
─────────────────────────────────────────────────────────────────────────
register {
  ...原有字段...
  pluginVersion: "1.2.0"   →   GatewayMessage          →   agent_connection
  sdkVersion:   "3.1.0"         .pluginVersion               .plugin_version
}                               .sdkVersion                  .sdk_version
```

## 数据流

```
AgentWebSocketHandler.handleRegister()
  │  从 GatewayMessage 提取 pluginVersion、sdkVersion
  │
  ▼
AgentRegistryService.register(..., pluginVersion, sdkVersion)
  │
  ├─ 新 Agent → AgentConnection.builder()
  │              .pluginVersion(pluginVersion)
  │              .sdkVersion(sdkVersion)
  │              → repository.insert()
  │
  └─ 复用 Agent → existing.setPluginVersion(pluginVersion)
                   existing.setSdkVersion(sdkVersion)
                   → repository.updateAgentInfo()
```

## 数据模型

### DDL（V7__agent_plugin_version.sql）

```sql
ALTER TABLE agent_connection
    ADD COLUMN plugin_version VARCHAR(64) COMMENT '插件版本号',
    ADD COLUMN sdk_version VARCHAR(64) COMMENT 'SDK 版本号';
```

- 类型 `VARCHAR(64)`，与现有 `tool_version` 一致
- 允许 NULL，兼容旧版 Plugin 不上报的场景
- 不新增索引（仅用于问题排查，非查询条件）

### 实体字段（AgentConnection.java）

```java
private String pluginVersion;  // 插件版本号（Agent 注册时上报）
private String sdkVersion;     // SDK 版本号（Agent 注册时上报）
```

Lombok `@Data` 自动生成 getter/setter，MyBatis 通过字段名自动映射到 `plugin_version` / `sdk_version`。

### 消息协议（GatewayMessage.java）

```java
private String pluginVersion;
private String sdkVersion;
```

Jackson 自动从 register JSON 反序列化，无需额外配置。字段缺失时值为 `null`。

## 持久层

### INSERT（AgentConnectionMapper.xml）

```sql
INSERT INTO agent_connection
    (id, user_id, ak_id, device_name, mac_address, os, tool_type, tool_version,
     plugin_version, sdk_version, status, last_seen_at, created_at)
VALUES
    (#{id}, #{userId}, #{akId}, #{deviceName}, #{macAddress}, #{os}, #{toolType}, #{toolVersion},
     #{pluginVersion}, #{sdkVersion}, #{status}, #{lastSeenAt}, #{createdAt})
```

### UPDATE（updateAgentInfo）

```sql
UPDATE agent_connection
SET status = #{status},
    device_name = #{deviceName},
    mac_address = #{macAddress},
    os = #{os},
    tool_version = #{toolVersion},
    plugin_version = #{pluginVersion},
    sdk_version = #{sdkVersion},
    last_seen_at = #{lastSeenAt}
WHERE id = #{id}
```

## 接口变更

### AgentRegistryService.register()

```diff
  public AgentConnection register(String userId, String akId, String deviceName,
-         String macAddress, String os, String toolType, String toolVersion)
+         String macAddress, String os, String toolType, String toolVersion,
+         String pluginVersion, String sdkVersion)
```

### AgentWebSocketHandler.handleRegister()

```diff
  String toolVersion = message.getToolVersion();
+ String pluginVersion = message.getPluginVersion();
+ String sdkVersion = message.getSdkVersion();

  AgentConnection agent = agentRegistryService.register(
-         userId, akId, deviceName, macAddress, os, toolType, toolVersion);
+         userId, akId, deviceName, macAddress, os, toolType, toolVersion,
+         pluginVersion, sdkVersion);
```

## 兼容性

| 场景 | 行为 |
|------|------|
| 新版 Plugin 上报新字段 | `plugin_version`、`sdk_version` 写入实际值 |
| 旧版 Plugin 不上报 | 字段为 NULL，注册正常完成 |
| 已有 Agent 重新注册 | `plugin_version`、`sdk_version` 更新为最新值 |
| 新字段不影响 | 设备绑定、心跳、上下线、`agent_online` 事件 |

## 各层改动汇总

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `db/migration/V7__agent_plugin_version.sql` | 新增 | DDL 加列 |
| `model/GatewayMessage.java` | 修改 | +2 字段 |
| `model/AgentConnection.java` | 修改 | +2 字段 |
| `ws/AgentWebSocketHandler.java` | 修改 | 提取 + 传入新字段 |
| `service/AgentRegistryService.java` | 修改 | 签名扩展，写入实体 |
| `mapper/AgentConnectionMapper.xml` | 修改 | INSERT + UPDATE SQL |
