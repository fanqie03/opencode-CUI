# 协议契约入口

当前协议规范已经按旧协议文档模板拆分为多文件套件：

```text
.trellis/spec/business/protocol/
```

请从 [protocol/README.md](./protocol/README.md) 进入。

## 为什么保留本文件

本文件作为历史链接兼容入口，避免已有引用失效。完整协议细节不再写在单篇总纲里，而是按层级拆分：

| 文档 | 内容 |
| --- | --- |
| [01-consumer-stream-protocol.md](./protocol/01-consumer-stream-protocol.md) | miniapp / externalWs 最终消费的 `StreamMessage` 协议 |
| [02-inbound-rest-protocol.md](./protocol/02-inbound-rest-protocol.md) | miniapp REST 与 external REST 入站协议 |
| [03-skillserver-gateway.md](./protocol/03-skillserver-gateway.md) | Skill Server ↔ AI Gateway `GatewayMessage` 协议 |
| [04-agent-return-protocols.md](./protocol/04-agent-return-protocols.md) | OpenCode、cloud default、assistant_square 三套返回协议 |
| [05-event-to-streammessage-mapping.md](./protocol/05-event-to-streammessage-mapping.md) | 原始事件到 `StreamMessage` 的映射表 |
| [06-end-to-end-flows.md](./protocol/06-end-to-end-flows.md) | 典型端到端流程 |
| [07-message-type-lifecycle.md](./protocol/07-message-type-lifecycle.md) | 按消息类型说明生命周期和维护规则 |
| [08-all-event-examples.md](./protocol/08-all-event-examples.md) | 全量事件 JSON 示例 |

## Source of Truth

旧 `documents/protocol` 已退役，不再作为协议事实来源。当前 source of truth：

```text
当前代码
  -> 当前测试
  -> .trellis/spec/business/protocol/*
  -> .trellis/spec/business/*.md
```

## 核心结论

- externalWs 和 miniapp 最终消费同一套 `StreamMessage`。
- 入站 REST 请求协议、agent/cloud 返回协议、最终下行协议是三层概念。
- agent 返回侧有三套来源协议：OpenCode、cloud default、assistant_square。
- 云端请求策略和云端返回 decoder 通过 `cloudProfile` 串联，但字段和职责不同。
