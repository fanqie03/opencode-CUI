# 分支变更总结 (feature--US20260612694037 vs main)

## 工作提交

| Commit | 说明 |
|--------|------|
| (已有) | feat: GatewayMessage/AgentConnection 新增 pluginVersion/sdkVersion 字段 |
| (已有) | feat: V7 迁移脚本 agent_connection 表加两列 |
| (已有) | feat: AgentRegistryService.register() 签名扩展，透传落库 |
| (已有) | feat: AgentConnectionMapper.xml INSERT/UPDATE 加新字段 |
| `0a5c7958` | test: 适配 register() 签名变更 |

## 后端变更 (ai-gateway)

### 新增文件
| 文件 | 说明 |
|------|------|
| `db/migration/V7__agent_plugin_version.sql` | ALTER TABLE agent_connection ADD plugin_version/sdk_version VARCHAR(64) |

### 修改文件
| 文件 | 改动 |
|------|------|
| `model/GatewayMessage.java` | +`pluginVersion`、+`sdkVersion` 字段，Jackson 自动反序列化 |
| `model/AgentConnection.java` | +`pluginVersion`、+`sdkVersion` 字段，Lombok 自动映射 |
| `ws/AgentWebSocketHandler.java` | `handleRegister()` 提取新字段传入 register() |
| `service/AgentRegistryService.java` | `register()` 签名新增 pluginVersion/sdkVersion，新 Agent INSERT / 复用 Agent UPDATE 均写入 |
| `mapper/AgentConnectionMapper.xml` | INSERT 加 `plugin_version, sdk_version`；updateAgentInfo 加 `plugin_version = #{pluginVersion}, sdk_version = #{sdkVersion}` |

### 兼容性
- 新字段允许 NULL，旧版 Plugin 不上报时正常注册
- 不影响设备绑定、心跳、上下线、agent_online 事件
- 不新增索引
