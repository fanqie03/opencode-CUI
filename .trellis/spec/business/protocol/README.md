# 协议文档（当前规范）

> 状态：当前规范，替代旧 `documents/protocol`。旧文档只可参考结构，不再作为事实来源。
> 事实来源：当前代码、当前单元测试、本地调试抓包、`.trellis/tasks/06-12-plugin-miniapp/research/local-run/` 证据文件。
> 写作原则：按旧协议文档的阅读方式组织，但每个事件必须能追到“上游报文 -> gateway/SS 归一 -> miniapp/externalWs 下行”。

## 文档结构

| 文档 | 层级 | 内容 |
| --- | --- | --- |
| [01-consumer-stream-protocol.md](./01-consumer-stream-protocol.md) | miniapp / externalWs | 最终消费协议 `StreamMessage`，字段、事件类型、实测下行样例。 |
| [02-inbound-rest-protocol.md](./02-inbound-rest-protocol.md) | miniapp / external REST | 入站 REST API，session/message/permission/external invoke。 |
| [03-skillserver-gateway.md](./03-skillserver-gateway.md) | Skill Server -> AI Gateway | `/ws/skill`、`GatewayMessage`、invoke/action/route 事件。 |
| [04-agent-return-protocols.md](./04-agent-return-protocols.md) | Agent / Cloud -> Gateway | 三套 agent 返回协议：本地 OpenCode、插件云端 skill-provider、assistant_square SSE。 |
| [05-event-to-streammessage-mapping.md](./05-event-to-streammessage-mapping.md) | 全链路映射 | 每个事件从上游到 `StreamMessage` 的一一对应表、字段来源、丢弃规则。 |
| [06-end-to-end-flows.md](./06-end-to-end-flows.md) | 端到端流程 | miniapp、external、question reply、permission reply、cloud、assistant_square、rebuild 流程。 |
| [07-message-type-lifecycle.md](./07-message-type-lifecycle.md) | miniapp 消费状态机 | `StreamMessage.type` 在前端如何合并、完成、更新卡片。 |
| [08-all-event-examples.md](./08-all-event-examples.md) | 全事件示例 | GatewayMessage、OpenCode/local、cloud skill-provider、assistant_square、最终 StreamMessage 的完整 JSON 示例。 |
| [CHANGELOG.md](./CHANGELOG.md) | 变更记录 | 本规范套件变更说明。 |

## 当前协议全貌

```text
miniapp REST / external REST
  -> Skill Server
  -> GatewayMessage invoke/action
  -> AI Gateway
  -> local plugin / cloud skill-provider / assistant_square
  -> GatewayMessage tool_event/tool_done/tool_error
  -> Skill Server translator
  -> StreamMessage
  -> miniapp WebSocket 或 externalWs
```

最终下行只有一套业务协议：`StreamMessage` JSON。miniapp 和 externalWs 的差异只在连接、认证、路由和恢复机制，不在业务事件体。

## 三套 agent 返回协议

| 来源 | provider 判定 | Gateway 准入/解码 | Skill Server 翻译 | 最终输出 |
| --- | --- | --- | --- | --- |
| 本地 OpenCode/local plugin | `tool_event.event` 缺少 `protocol` | `gateway-schema` 按 OpenCode canonical schema 校验 | `OpenCodeEventTranslator` | `StreamMessage` |
| 插件云端 / skill-provider | `tool_event.event.protocol == "cloud"` | `gateway-schema` 按 skill-provider schema 校验；其他显式 protocol fail-closed | `CloudEventTranslator` | `StreamMessage` |
| assistant_square SSE | `cloudProfile=assistant_square` | `AssistantSquareSseEventDecoder` + `StandardProtocolHandler` 先转成 standard cloud event | `CloudEventTranslator` | `StreamMessage` |

不要把“请求协议”和“返回协议”混在一起：`DefaultCloudRequestStrategy` / `AssistantSquareCloudRequestStrategy` 构造的是云端请求 body；`DefaultSseEventDecoder` / `AssistantSquareSseEventDecoder` 处理的是云端返回 SSE。

## 本次校准证据

| 证据 | 路径 |
| --- | --- |
| 本地服务启动与端口验证 | `.trellis/tasks/06-12-plugin-miniapp/research/local-run/` |
| 插件云端协议 uplink 抓包 | `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-uplink-capture.json` |
| 插件本地 OpenCode 协议 validator 抓包 | `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-local-opencode-capture.json` |
| miniapp WebSocket 最终下行抓包 | `.trellis/tasks/06-12-plugin-miniapp/research/local-run/miniapp-ws-capture.jsonl` |

已跑过的协议相关验证：

```text
pnpm --dir plugins/agent-plugin/packages/gateway-schema test
pnpm --dir plugins/agent-plugin/packages/bridge-runtime-sdk exec node --experimental-strip-types --test tests/runtime-sdk.test.ts
mvn -q "-Dtest=CloudEventTranslatorTest,OpenCodeEventTranslatorTest,CloudOpenCodeProtocolParityTest" test
mvn -q "-Dtest=AssistantSquareSseEventDecoderTest" test
```

## Source of Truth

| 主题 | 代码证据 |
| --- | --- |
| 最终下行 DTO | `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java` |
| miniapp 前端类型 | `skill-miniapp/src/protocol/types.ts` |
| miniapp 实时消费 | `skill-miniapp/src/hooks/useSkillStream.ts`, `skill-miniapp/src/protocol/StreamAssembler.ts` |
| miniapp WS | `SkillStreamHandler`, `MiniappDeliveryStrategy`, `RedisMessageBroker` |
| externalWs | `ExternalStreamHandler`, `ExternalWsDeliveryStrategy` |
| SS/GW DTO | `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java` |
| 本地 OpenCode 协议 schema | `plugins/agent-plugin/packages/gateway-schema/src/contract/schemas/tool-event/opencode-provider-event/` |
| 云端 skill-provider 协议 schema | `plugins/agent-plugin/packages/gateway-schema/src/contract/schemas/tool-event/skill-provider-event/` |
| 插件云端事件投影 | `DefaultFactToSkillEventProjector` |
| 本地事件翻译 | `OpenCodeEventTranslator` |
| 云端事件翻译 | `CloudEventTranslator` |
| assistant_square decoder | `AssistantSquareSseEventDecoder`, `StandardProtocolHandler` |
