# 1 需求价值和概述

Plugin（Agent 客户端）在 WebSocket register 事件中新增上报两个版本字段：`pluginVersion`（插件自身版本号）和 `sdkVersion`（SDK 版本号）。Gateway 需要在 Agent 注册入库时，将这两个字段持久化到 `agent_connection` 表，用于线上问题排查时快速定位 Plugin 版本信息。

当前 `agent_connection` 表仅有 `tool_version` 字段记录工具版本，无法区分插件版本和 SDK 版本。本次改动沿现有注册链路逐层透传新字段，最小侵入完成落库。

# 2 上下文分析

## 2.1 现有架构

- **WebSocket register 协议**：`GatewayMessage` 包含 `deviceName`、`macAddress`、`os`、`toolType`、`toolVersion` 五个注册字段
- **数据模型**：`AgentConnection` 实体对应 `agent_connection` 表，已有 `toolVersion`（`tool_version`）字段
- **注册流程**：`AgentWebSocketHandler.handleRegister()` → `AgentRegistryService.register()` → `AgentConnectionRepository.insert()` / `updateAgentInfo()`
- **Plugin 端已上报** `pluginVersion` 和 `sdkVersion` 字段，Gateway 侧当前忽略这两个字段（JSON 反序列化时丢弃）

## 2.2 数据范围

仅涉及 `agent_connection` 表新增两列，无其他数据变更。

# 3 初始需求分析

## 3.1 初始化需求场景分析

**场景一：线上问题排查，快速定位 Plugin 版本**
运维人员接到用户反馈，需要确认该用户当前使用的 Plugin 版本和 SDK 版本。通过查询 `agent_connection` 表的 `plugin_version` 和 `sdk_version` 字段即可获取。

**场景二：Plugin 升级后重新注册**
用户升级 Plugin 后重新连接，register 消息携带新的版本号。Gateway 在复用已有 `agent_connection` 记录时更新 `plugin_version` 和 `sdk_version`，确保版本信息始终是最新的。

**场景三：旧版 Plugin 未上报新字段**
旧版 Plugin 的 register 消息不含 `pluginVersion` 和 `sdkVersion`。Gateway 将这两个字段存入 NULL，注册流程正常完成，不影响任何现有业务。

## 3.2 结构化IR

| 编号 | 需求项 | 类型 | 优先级 | 输入 | 输出 | 约束 |
|------|--------|------|--------|------|------|------|
| IR-01 | GatewayMessage 新增字段 | 数据 | P0 | register JSON 中的 `pluginVersion`、`sdkVersion` | 反序列化为 Java 字段 | Jackson 自动映射，缺失时为 NULL |
| IR-02 | AgentConnection 实体新增字段 | 数据 | P0 | `pluginVersion`、`sdkVersion` 字符串 | MyBatis 自动映射到 DB 列 | 与 DB 列名 snake_case 自动对应 |
| IR-03 | DB 新增列 | 数据 | P0 | DDL `ALTER TABLE ADD COLUMN` | `plugin_version`、`sdk_version` VARCHAR(64) | 允许 NULL |
| IR-04 | 新 Agent 注册落库 | 功能 | P0 | register() 参数 | INSERT 包含新字段 | 旧 Plugin 传 NULL |
| IR-05 | 已有 Agent 重新注册更新 | 功能 | P0 | register() 参数 | updateAgentInfo 更新新字段 | 覆盖旧值 |

# 4 需求影响分析

## 4.1 特性影响分析

本次改动为纯数据层透传，不改变任何业务流程：

- **Agent 注册**：`AgentRegistryService.register()` 签名新增两个参数，内部透传至实体和 SQL
- **设备绑定、心跳、上下线**：不受影响
- **`agent_online` 事件**：不转发新字段（范围外）
- **skill-server**：无任何影响

## 4.2 涉及模块

| 模块 | 影响类型 | 说明 |
|------|----------|------|
| ai-gateway | 修改 | Model/Handler/Service/Mapper/DB 迁移 |

## 4.3 兼容性分析

- 旧版 Plugin 不传新字段 → `GatewayMessage` 字段为 NULL → DB 列值为 NULL，注册正常完成
- 新版 Plugin 传新字段 → 正常写入
- 已有 Agent 重新注册 → 新字段更新为最新值
- 无 breaking change

# 5 系统用例分析

## 5.1 用例清单

| 编号 | 用例名称 | 说明 |
|------|----------|------|
| UC-01 | Plugin 注册时落库版本信息 | Plugin 发起 register，Gateway 将 pluginVersion、sdkVersion 持久化到 agent_connection 表 |

## 5.2 Plugin 注册入库版本信息 用例分析

### 5.2.1 用例概述

Plugin 通过 WebSocket 向 Gateway 发送 register 消息，Gateway 在完成 Agent 注册（新增或复用已有记录）的同时，将 `pluginVersion` 和 `sdkVersion` 写入 `agent_connection` 表。

### 5.2.2 用例流程

**正常流程**：

```
Plugin                   Gateway                      MySQL
  │                         │                           │
  │  register {             │                           │
  │    ...原有字段...        │                           │
  │    pluginVersion,       │                           │
  │    sdkVersion           │                           │
  │  }                      │                           │
  │ ──────────────────────▶ │                           │
  │                         │  GatewayMessage            │
  │                         │  .getPluginVersion()       │
  │                         │  .getSdkVersion()          │
  │                         │                           │
  │                         │  AgentRegistryService      │
  │                         │  .register(...,            │
  │                         │    pluginVersion,          │
  │                         │    sdkVersion)             │
  │                         │                           │
  │                         │  ──────────────────────▶  │
  │                         │  INSERT / UPDATE           │
  │                         │  plugin_version,           │
  │                         │  sdk_version               │
  │                         │                           │
  │  register_ok            │                           │
  │ ◀────────────────────── │                           │
```

**异常流程**：

| 异常 | 处理 |
|------|------|
| 旧版 Plugin 未上报新字段 | `GatewayMessage` 字段为 NULL，DB 写入 NULL，注册正常完成 |

### 5.2.3 影响的功能列表和需求分析

| 影响功能 | 说明 |
|----------|------|
| Agent 注册 | register() 签名扩展，新增/更新时写入新字段 |
| 问题排查 | 可通过 SQL 查询 plugin_version、sdk_version 定位版本 |

# 6 功能设计

## 6.1 业界实现方案分析

本次改动为简单的字段透传落库，无复杂方案选型。遵循现有 `toolVersion` 字段的设计模式：VARCHAR(64)、允许 NULL、注册时透传。

## 6.2 功能实现整体设计方案

核心设计原则：**最小侵入，沿现有链路逐层透传。**

```
Plugin (WebSocket JSON)
  │  register { pluginVersion, sdkVersion }
  ▼
GatewayMessage (Jackson 反序列化)
  │  .getPluginVersion()  .getSdkVersion()
  ▼
AgentWebSocketHandler.handleRegister()
  │  提取字段 → 传入 register()
  ▼
AgentRegistryService.register(...)
  │  AgentConnection.setPluginVersion() / .setSdkVersion()
  ▼
AgentConnectionMapper.xml
  │  INSERT / UPDATE  SQL 包含 plugin_version, sdk_version
  ▼
agent_connection 表
```

## 6.3 Plugin 版本字段落库 功能实现

### 6.3.1 实现思路

1. **消息层**：`GatewayMessage` 新增 `pluginVersion`、`sdkVersion` 字段，Jackson 自动从 register JSON 反序列化
2. **实体层**：`AgentConnection` 新增对应字段，Lombok + MyBatis 自动映射
3. **持久层**：`agent_connection` 表新增两列，INSERT 和 UPDATE SQL 同步更新
4. **服务层**：`AgentRegistryService.register()` 签名扩展，透传新字段
5. **兼容旧版**：字段允许 NULL，旧 Plugin 不上报时不影响注册

### 6.3.2 实现设计

**主流程**：Plugin 发起 register → GatewayMessage 反序列化 → handleRegister() 提取字段 → register() 写入实体 → MyBatis 持久化。

无事件发布、无异步任务。完全同步透传。

### 6.3.3 功能可靠性分析

| 风险 | 缓解措施 |
|------|----------|
| 旧版 Plugin 不上报新字段 | 字段为 NULL，注册正常完成 |
| DB 迁移失败 | Flyway 自动执行，`ALTER TABLE ADD COLUMN` 为轻量操作 |
| 字段为 NULL 时序列化 | GatewayMessage 不对外输出（register 为入站消息） |

### 6.3.4 功能安全分析

| 安全点 | 措施 |
|--------|------|
| 注入攻击 | 字段仅用于落库，不参与查询拼接；MyBatis `#{}` 参数化 |
| 敏感数据 | 版本号为非敏感元数据 |

### 6.3.5 架构元素影响列表

| 架构元素 | 影响类型 | 说明 |
|----------|----------|------|
| ai-gateway | 修改 | Model/Handler/Service/Mapper/DB 迁移 |

**ai-gateway 新增文件**：

| 文件 | 说明 |
|------|------|
| `db/migration/V7__agent_plugin_version.sql` | DDL 加列 |

**ai-gateway 修改文件**：

| 文件 | 改动 |
|------|------|
| `model/GatewayMessage.java` | 新增 `pluginVersion`、`sdkVersion` 字段 |
| `model/AgentConnection.java` | 新增 `pluginVersion`、`sdkVersion` 字段 |
| `ws/AgentWebSocketHandler.java` | `handleRegister()` 提取新字段并传入 `register()` |
| `service/AgentRegistryService.java` | `register()` 签名扩展，新增和更新时写入 |
| `mapper/AgentConnectionMapper.xml` | INSERT + `updateAgentInfo` SQL 新增两列 |

### 6.3.6 ai-gateway 实现设计

#### 6.3.6.1 接口设计

**AgentRegistryService.register() 签名变更**：

```diff
  public AgentConnection register(String userId, String akId, String deviceName,
-         String macAddress, String os, String toolType, String toolVersion)
+         String macAddress, String os, String toolType, String toolVersion,
+         String pluginVersion, String sdkVersion)
```

无新增 API 端点。`GatewayMessage` 的 JSON 反序列化自动处理新字段，无需修改 register 消息协议。

#### 6.3.6.2 数据模型设计

##### 6.3.6.2.1 关系型数据库设计

**迁移 V7__agent_plugin_version.sql**：

```sql
ALTER TABLE agent_connection
    ADD COLUMN plugin_version VARCHAR(64) COMMENT '插件版本号',
    ADD COLUMN sdk_version VARCHAR(64) COMMENT 'SDK 版本号';
```

- 类型 `VARCHAR(64)`，与 `tool_version` 保持一致
- 允许 NULL，兼容旧版 Plugin
- 不新增索引（仅用于排查，非查询条件）

**SQL 变更**：

| 操作 | 变更 |
|------|------|
| INSERT | 新增 `plugin_version, sdk_version` 列和对应值 |
| updateAgentInfo | 新增 `plugin_version = #{pluginVersion}, sdk_version = #{sdkVersion}` |

##### 6.3.6.2.2 Redis 缓存设计

无。新字段仅落 MySQL，不涉及 Redis。

##### 6.3.6.2.3 配置项设计

无新增配置项。

# 7 系统级非功能性设计

## 7.1 系统级的FMEA影响分析

| 失效模式 | 影响 | S | 原因 | O | 当前控制 | D | RPN | 改进措施 |
|----------|------|---|------|---|----------|---|-----|----------|
| DB 迁移执行失败 | 应用启动失败 | 8 | Flyway 校验失败 | 1 | Flyway 自动迁移 | 2 | 16 | 无需额外措施 |
| 旧版 Plugin 不传新字段 | 列值为 NULL | 1 | JSON 字段缺失 | 10 | 字段允许 NULL | 1 | 10 | 无需额外措施 |

> S=严重度(1-10), O=发生频率(1-10), D=可检测度(1-10), RPN=S×O×D。

## 7.2 系统级安全影响分析

| 安全维度 | 风险 | 缓解措施 |
|----------|------|----------|
| 注入攻击 | 版本号字符串拼接 SQL | MyBatis `#{}` 参数化查询 |
| 敏感数据泄露 | 版本号暴露系统信息 | 版本号为非敏感元数据，可公开 |

## 7.3 兼容性

### 7.3.1 后向兼容性确认

| 变更项 | 旧行为 | 新行为 | 兼容性 |
|--------|--------|--------|--------|
| `pluginVersion` 不传 | — | DB 写入 NULL | 兼容 |
| `sdkVersion` 不传 | — | DB 写入 NULL | 兼容 |
| `register()` 方法签名 | 7 参数 | 9 参数 | 调用方（handleRegister）同步修改 |

### 7.3.2 前向兼容性确认

| 预留项 | 说明 |
|--------|------|
| 字段类型 VARCHAR(64) | 如未来版本号变长，可通过 `ALTER TABLE MODIFY COLUMN` 扩展 |

## 7.4 可运维

### 7.4.1 日志规范

无新增日志。Plugin 版本信息可通过查询 `agent_connection` 表获取。

### 7.4.2 部署要求

- DB 迁移 V2 在应用启动前由 Flyway 自动执行
- 无需协调其他服务上线

## 7.5 资料

| 资料 | 链接/路径 |
|------|-----------|
| 需求 PRD | `.trellis/tasks/06-16-gateway-plugin-register-version-fields/prd.md` |
| 实现计划 | `.trellis/tasks/06-16-gateway-plugin-register-version-fields/implement.md` |
| AgentConnectionMapper.xml | `ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml` |

# 8 CheckList

## 8.1 设计自检清单要求

| # | 检查项 | 状态 |
|---|--------|------|
| 1 | 需求场景是否完整覆盖？（新版/旧版 Plugin、新增/复用记录） | ✅ |
| 2 | API/接口变更是否清晰？（register() 签名变更，无新增 API） | ✅ |
| 3 | 数据模型是否完整？（DDL、实体字段、SQL 变更） | ✅ |
| 4 | 可靠性是否充分？（NULL 兼容、Flyway 自动迁移） | ✅ |
| 5 | 安全性是否覆盖？（参数化查询、非敏感数据） | ✅ |
| 6 | 兼容性是否评估？（后向/前向） | ✅ |
| 7 | 可运维性是否考虑？（部署顺序） | ✅ |
| 8 | 架构元素影响是否列出？（6 个文件） | ✅ |
