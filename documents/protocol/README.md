# 协议文档目录

OpenCode-CUI 全链路协议报文文档，按版本组织。

## 版本

| 版本 | 状态 | 说明 |
|------|------|------|
| [v2](./v2/README.md) | **当前** | 路由架构重设计：Mesh/Legacy 双策略、Gateway 自注册/发现、Lua 原子操作 |
| [v1](./v1/README.md) | 归档 | 初始协议版本，单 Owner 路由 |

## 快速导航

**→ 当前版本文档：[v2/README.md](./v2/README.md)**

| 层级 | 文档 |
|------|------|
| Miniapp ↔ Skill Server | [v2/01-miniapp-skillserver.md](./v2/01-miniapp-skillserver.md) |
| Skill Server ↔ AI Gateway | [v2/02-skillserver-gateway.md](./v2/02-skillserver-gateway.md) |
| AI Gateway ↔ Plugin | [v2/03-gateway-plugin.md](./v2/03-gateway-plugin.md) |
| Plugin ↔ OpenCode SDK | [v2/04-plugin-opencode.md](./v2/04-plugin-opencode.md) |
| 全链路映射 | [v2/05-opencode-to-custom-protocol-mapping.md](./v2/05-opencode-to-custom-protocol-mapping.md) |
| 全链路流程 | [v2/06-end-to-end-flows.md](./v2/06-end-to-end-flows.md) |
| 消息生命周期 | [v2/07-message-type-lifecycle.md](./v2/07-message-type-lifecycle.md) |
| 变更日志 | [v2/CHANGELOG.md](./v2/CHANGELOG.md) |
| 路由架构设计 | [routing-redesign/](../routing-redesign/) |

## 阅读建议

- **了解 v1→v2 变更** → 先读 [v2/CHANGELOG.md](./v2/CHANGELOG.md)
- **快速了解全貌** → 读 05（映射总表）
- **理解完整业务流程** → 读 06（端到端场景）
- **查某种消息的完整生命周期** → 读 07（按消息类型索引）
- **前端开发** → 重点读 01
- **后端开发** → 重点读 02 + 03
- **Plugin 开发** → 重点读 04 + 03
- **排查问题** → 05 的时序处理章节 + 06 的错误处理场景
- **路由架构决策** → 读 [routing-redesign](../routing-redesign/)
