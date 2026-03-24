# 协议文档目录

OpenCode-CUI 全链路协议报文文档，按版本组织。

## 版本

| 版本 | 状态 | 说明 |
|------|------|------|
| [v3](./v3/README.md) | **当前** | 并发注册分布式锁、AkSk 认证双模式、conn:ak 连接注册、设备绑定校验优化 |
| [v2](./v2/README.md) | 归档 | 路由架构重设计：Mesh/Legacy 双策略、Gateway 自注册/发现、Lua 原子操作 |
| [v1](./v1/README.md) | 归档 | 初始协议版本，单 Owner 路由 |

## 快速导航

**→ 当前版本文档：[v3/README.md](./v3/README.md)**

| 层级 | 文档 |
|------|------|
| Miniapp ↔ Skill Server | [v3/01-miniapp-skillserver.md](./v3/01-miniapp-skillserver.md) |
| Skill Server ↔ AI Gateway | [v3/02-skillserver-gateway.md](./v3/02-skillserver-gateway.md) |
| AI Gateway ↔ Plugin | [v3/03-gateway-plugin.md](./v3/03-gateway-plugin.md) |
| Plugin ↔ OpenCode SDK | [v3/04-plugin-opencode.md](./v3/04-plugin-opencode.md) |
| 全链路映射 | [v3/05-opencode-to-custom-protocol-mapping.md](./v3/05-opencode-to-custom-protocol-mapping.md) |
| 全链路流程 | [v3/06-end-to-end-flows.md](./v3/06-end-to-end-flows.md) |
| 消息生命周期 | [v3/07-message-type-lifecycle.md](./v3/07-message-type-lifecycle.md) |
| 变更日志 | [v3/CHANGELOG.md](./v3/CHANGELOG.md) |

## 阅读建议

- **了解 v2→v3 变更** → 先读 [v3/CHANGELOG.md](./v3/CHANGELOG.md)
- **快速了解全貌** → 读 05（映射总表）
- **理解完整业务流程** → 读 06（端到端场景）
- **查某种消息的完整生命周期** → 读 07（按消息类型索引）
- **前端开发** → 重点读 01
- **后端开发** → 重点读 02 + 03
- **Plugin 开发** → 重点读 04 + 03
- **排查问题** → 05 的时序处理章节 + 06 的错误处理场景
