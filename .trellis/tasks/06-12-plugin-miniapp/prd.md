# 补齐 plugin / miniapp / 助手广场事件报文协议映射

## Goal

从真实代码和本地调试结果重新校准项目协议文档：逐事件梳理 plugin 返回报文、AI Gateway / Skill Server 中间事件、最终给 miniapp / externalWs 的 `StreamMessage` 出站报文，并把云端协议、本地协议、助手广场协议分别落到 `.trellis/spec/business/protocol/` 的规范中。

## Scope

- 只改协议 spec 和任务证据记录。
- 不修改业务代码；如果发现代码与协议预期冲突，先记录差异。
- 旧 `documents/protocol` 继续退役，不恢复为事实来源。

## Requirements

- 每个事件族必须有“来源报文 -> 中间事件 -> 出站报文”的对应关系。
- 每一个当前代码枚举到的事件都必须能找到 JSON 示例；不能只覆盖部分代表性事件。
- 区分三套 agent 返回协议：
  - 本地 OpenCode/local plugin：`tool_event.event` 缺少 `protocol`。
  - 插件云端 skill-provider：`tool_event.event.protocol == "cloud"`。
  - assistant_square SSE：Gateway decoder 先转 standard cloud event。
- 明确 `protocolType/eventType/messageType` 到项目内部事件类型的映射。
- 明确 miniapp / externalWs 最终下行是同一套 `StreamMessage`。
- 明确 dropped / unsupported / fallback 规则，尤其是 question、permission、assistant_square unsupported messageType。

## Evidence

本地服务已启动并验证端口：

| 服务 | 地址 |
| --- | --- |
| AI Gateway | `http://localhost:8081` |
| Skill Server | `http://localhost:8082` |
| miniapp | `http://127.0.0.1:3001` |

抓包/调试输出：

| 文件 | 内容 |
| --- | --- |
| `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-uplink-capture.json` | 插件云端 skill-provider uplink 实测序列：`session_created`、`step.start`、`text.*`、`tool.update`、`question`、`permission.*`、`step.done`、`session.title`、`tool_done`。 |
| `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-local-opencode-capture.json` | OpenCode/local fixtures 过 `gateway-schema.validateToolEvent` 后的 raw/canonical 样例。 |
| `.trellis/tasks/06-12-plugin-miniapp/research/local-run/miniapp-ws-capture.jsonl` | Skill Server -> miniapp `/ws/skill/stream` 最终 `StreamMessage` 实测下行。 |

代码证据：

- `DefaultFactToSkillEventProjector`
- `gateway-schema/src/contract/schemas/tool-event/**`
- `CloudEventTranslator`
- `OpenCodeEventTranslator`
- `AssistantSquareSseEventDecoder`
- `StandardProtocolHandler`
- `StreamMessage`
- `SkillStreamHandler`
- `useSkillStream.ts`
- `StreamAssembler.ts`

验证命令：

```text
pnpm --dir plugins/agent-plugin/packages/gateway-schema test
pnpm --dir plugins/agent-plugin/packages/bridge-runtime-sdk exec node --experimental-strip-types --test tests/runtime-sdk.test.ts
mvn -q "-Dtest=CloudEventTranslatorTest,OpenCodeEventTranslatorTest,CloudOpenCodeProtocolParityTest" test
mvn -q "-Dtest=AssistantSquareSseEventDecoderTest" test
```

说明：`plugins/agent-plugin/packages/bridge-runtime-sdk` 的全量 test 曾有 2 个 distribution build 用例失败，协议相关 `runtime-sdk.test.ts` 已单独通过。

## Key Findings

- miniapp 和 externalWs 最终消费同一套 `StreamMessage`，差异在连接、认证、路由和恢复，不在业务 payload。
- 本地 OpenCode 协议由“缺少 `protocol` 字段”判定；显式 `protocol` 只有 `"cloud"` 被 cloud schema 接受，其他值 fail-closed。
- 插件云端 skill-provider gateway-schema 白名单目前覆盖 text/thinking/tool/question/permission/step/session；`CloudEventTranslator` 额外支持 planning/file/search/reference/ask_more，主要来自 assistant_square 或内部 standard event。
- assistant_square `protocolType` 缺失、`standard`、`"5"` 都走 `StandardProtocolHandler`；未知 protocolType 丢弃。
- assistant_square 第一个事件前会补 `session.status busy` 和 `step.start`；flush 时补 open part `.done`、`step.done`、`session.status idle`。
- miniapp 主会话收到 `permission.reply` 不会创建新消息；只更新已有 permission 状态。
- question 完成态如果晚于 `session.status idle` 到达，miniapp 会 patch 旧 question part，而不是重建 assembler。

## Acceptance

- [x] 规范入口能找到本地协议、云端协议、助手广场协议、miniapp/externalWs 出站协议。
- [x] 关键事件族有来源报文、字段来源和出站报文示例。
- [x] 新增 `08-all-event-examples.md`，覆盖 GatewayMessage、OpenCode/local、cloud skill-provider、CloudEventTranslator standard extension、assistant_square SSE、最终 StreamMessage 的全事件 JSON 示例。
- [x] 云端协议与本地协议分开说明，且标注 gateway-schema 与 SS translator 的边界。
- [x] 助手广场协议来自 decoder/handler 代码和测试证据，并标注 unsupported / dropped 事件。
- [x] 本地服务、抓包文件、测试命令记录到任务中。
- [x] `rg --hidden "documents/protocol" .trellis/spec skill-server plugins skill-miniapp ai-gateway AGENTS.md` 只剩明确退役引用。
- [x] `git diff --check` 通过。

## Out of Scope

- 不修复运行时代码 bug。
- 不补真实外部云端账号才能复现的私有 SSE 样例；当前用 decoder/test 和本地构造样例作为证据。
- 不恢复旧 `documents/protocol`。
