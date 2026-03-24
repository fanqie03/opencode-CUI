# 协议文档 v1（归档）

> **状态：** 已归档，反映 route-redesign-0321 分支改动前的协议状态。
> **当前版本：** 请查看 [v3](../v3/README.md)

本目录保留了 v1 版本的全部协议文档，作为历史参考。

## 文档结构

| 文档 | 层级 | 内容概要 |
|------|------|---------|
| [01-miniapp-skillserver.md](./01-miniapp-skillserver.md) | Miniapp ↔ Skill Server | REST API 全量端点、WebSocket StreamMessage 19 种消息类型详解 |
| [02-skillserver-gateway.md](./02-skillserver-gateway.md) | Skill Server ↔ AI Gateway | invoke 6 种 Action、上行 7 种消息类型、翻译规则、IM 协议 |
| [03-gateway-plugin.md](./03-gateway-plugin.md) | AI Gateway ↔ Plugin | AK/SK 认证、GatewayMessage 14 种类型、单一 Owner 路由 |
| [04-plugin-opencode.md](./04-plugin-opencode.md) | Plugin ↔ OpenCode SDK | 11 种 SDK 事件、7 种 Action |
| [05-opencode-to-custom-protocol-mapping.md](./05-opencode-to-custom-protocol-mapping.md) | 全链路映射 | 四层转换映射、字段对应表 |
| [06-end-to-end-flows.md](./06-end-to-end-flows.md) | 全链路流程 | 14 个业务场景端到端流程 |
| [07-message-type-lifecycle.md](./07-message-type-lifecycle.md) | 消息生命周期 | 按消息类型分类的全流程详解 |

## v1 → v2 主要变更

详见 [v2/CHANGELOG.md](../v2/CHANGELOG.md)
