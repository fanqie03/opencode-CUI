# Implement: gateway plugin 注册入库 pluginVersion/sdkVersion

## Step 1: DB 迁移

- [ ] 新建 `ai-gateway/src/main/resources/db/migration/V7__agent_plugin_version.sql`
  ```sql
  ALTER TABLE agent_connection
      ADD COLUMN plugin_version VARCHAR(64) COMMENT '插件版本号',
      ADD COLUMN sdk_version VARCHAR(64) COMMENT 'SDK 版本号';
  ```

## Step 2: 数据模型

- [ ] `AgentConnection.java` 新增字段：`private String pluginVersion;` + `private String sdkVersion;`
- [ ] `GatewayMessage.java` 新增字段：`private String pluginVersion;` + `private String sdkVersion;`

## Step 3: 注册流程

- [ ] `AgentWebSocketHandler.handleRegister()` — 提取 `message.getPluginVersion()`、`message.getSdkVersion()` → 传入 `agentRegistryService.register()`
- [ ] `AgentRegistryService.register()` — 签名新增 `String pluginVersion, String sdkVersion` 参数，写入 AgentConnection

## Step 4: 持久层

- [ ] `AgentConnectionMapper.xml` — INSERT 语句新增 `plugin_version, sdk_version` 列
- [ ] `AgentConnectionMapper.xml` — `updateAgentInfo` 语句新增 `plugin_version = #{pluginVersion}, sdk_version = #{sdkVersion}`

## 验证

```bash
# 编译
cd ai-gateway && mvn compile -q

# 迁移文件存在
ls ai-gateway/src/main/resources/db/migration/V7__agent_plugin_version.sql
```
