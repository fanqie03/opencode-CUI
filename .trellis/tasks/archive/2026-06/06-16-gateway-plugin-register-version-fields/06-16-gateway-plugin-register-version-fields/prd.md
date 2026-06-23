# 1 需求价值和概述

Plugin（Agent 客户端）在 WebSocket register 事件中新增上报两个字段：`pluginVersion`（插件自身版本号）和 `sdkVersion`（SDK 版本号）。Gateway 需要在 Agent 注册入库（新增/更新）时将这两个字段一起持久化到数据库。

当前 `AgentConnection` 实体和 `agent_connection` 表已有 `toolVersion` 字段（记录工具版本），但缺少对插件版本和 SDK 版本的区分存储。

# 2 上下文分析

## 2.1 现有架构

- **WebSocket register 协议**：`GatewayMessage` 包含 `deviceName`、`macAddress`、`os`、`toolType`、`toolVersion` 五个字段
- **数据模型**：`AgentConnection` 对应 `agent_connection` 表，已有 `toolVersion` 字段
- **注册流程**：`AgentWebSocketHandler.handleRegister()` → `AgentRegistryService.register()` → `AgentConnectionRepository.insert()` / `updateAgentInfo()`
- **Plugin 已开始上报** `pluginVersion` 和 `sdkVersion` 字段，Gateway 侧目前忽略这两个字段

## 2.2 影响范围

| 层 | 文件 | 改动 |
|----|------|------|
| Model | `GatewayMessage.java` | 新增 `pluginVersion`、`sdkVersion` 字段 + 工厂方法参数 |
| Model | `AgentConnection.java` | 新增 `pluginVersion`、`sdkVersion` 字段 |
| DB | 新增迁移 `V7__agent_plugin_version.sql` | `agent_connection` 表加两列 |
| Service | `AgentRegistryService.java` | `register()` 方法签名扩展 |
| Handler | `AgentWebSocketHandler.java` | `handleRegister()` 提取新字段并传入 |
| Repository | `AgentConnectionRepository.java` | `insert()`、`updateAgentInfo()` 无需改签名（实体自带新字段） |
| Mapper | `AgentConnectionMapper.xml` | INSERT/UPDATE SQL 新增两个字段 |

# 3 需求分析

## 3.1 核心需求

| # | 需求 | 优先级 |
|---|------|--------|
| R1 | `GatewayMessage` 新增 `pluginVersion`、`sdkVersion` 字段，支持从 register JSON 反序列化 | P0 |
| R2 | `AgentConnection` 实体新增 `pluginVersion`、`sdkVersion` 字段 | P0 |
| R3 | DB 迁移新增 `plugin_version`、`sdk_version` 列（VARCHAR，允许 NULL） | P0 |
| R4 | 新增 Agent 时，`pluginVersion` 和 `sdkVersion` 一并写入 | P0 |
| R5 | 复用已有记录（re-register）时，`pluginVersion` 和 `sdkVersion` 一并更新 | P0 |
| R6 | `pluginVersion` 和 `sdkVersion` 允许为 NULL（兼容未上报的旧版插件） | P0 |

## 3.2 范围外

- 新字段不影响任何现有业务流程（设备绑定、心跳、上下线等）
- 不在 `agent_online` 事件中转发新字段
- 不修改 `agent_connection` 表的索引

# 4 验收标准

- [ ] `GatewayMessage` 可正确反序列化 register 消息中的 `pluginVersion`、`sdkVersion` 字段
- [ ] 新 Agent 注册后，`agent_connection` 表中 `plugin_version`、`sdk_version` 已写入
- [ ] 已有 Agent 重新注册后，`plugin_version`、`sdk_version` 更新为最新值
- [ ] 旧版 Agent 不上报新字段时，列值为 NULL，注册正常完成
- [ ] 现有 register 流程不受影响（回退兼容）
