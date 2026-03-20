# 协议文档目录

OpenCode-CUI 全链路协议报文文档，按层级组织。

## 文档结构

| 文档 | 层级 | 内容概要 |
|------|------|---------|
| [01-miniapp-skillserver.md](./01-miniapp-skillserver.md) | Miniapp ↔ Skill Server | REST API 全量端点、WebSocket StreamMessage 19 种消息类型详解、StreamAssembler 重组逻辑、MessagePart 结构 |
| [02-skillserver-gateway.md](./02-skillserver-gateway.md) | Skill Server ↔ AI Gateway | invoke 6 种 Action 详解、Gateway 上行 7 种消息类型、OpenCodeEventTranslator 翻译规则、IM 入站/出站协议、Session 重建机制、消息广播与持久化 |
| [03-gateway-plugin.md](./03-gateway-plugin.md) | AI Gateway ↔ Message Bridge Plugin | AK/SK 签名认证、GatewayMessage 14 种类型完整字段表、Agent 注册生命周期、多实例 Rendezvous Hash 路由、Redis 频道与 Key 设计 |
| [04-plugin-opencode.md](./04-plugin-opencode.md) | Plugin ↔ OpenCode SDK | OpenCode 11 种 SDK 事件原始结构、UpstreamEventExtractor 提取规则、7 种 Action 的 SDK API 调用、ToolDoneCompat 状态机、配置体系 |
| [05-opencode-to-custom-protocol-mapping.md](./05-opencode-to-custom-protocol-mapping.md) | 全链路映射 | OpenCode 事件→自定义协议的四层转换映射、字段名称对应表、特殊时序处理（用户消息、tool_done、completionCache）、协议封装/解封装 |

## 阅读建议

- **快速了解全貌** → 先读 05（映射总表）
- **前端开发** → 重点读 01
- **后端开发** → 重点读 02 + 03
- **Plugin 开发** → 重点读 04 + 03
- **排查问题** → 05 的时序处理章节
