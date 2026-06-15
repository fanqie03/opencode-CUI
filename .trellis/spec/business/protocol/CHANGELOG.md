# 协议文档变更记录

## 2026-06-12

### Changed

- 按当前代码和本地调试证据重写协议入口、最终消费协议、三套 agent 返回协议、事件映射表和 miniapp 生命周期文档。
- 明确三套返回协议边界：
  - 本地 OpenCode/local plugin：`tool_event.event` 缺少 `protocol`。
  - 插件云端 skill-provider：`tool_event.event.protocol == "cloud"`。
  - assistant_square SSE：Gateway decoder 先转 standard cloud event。
- 补充 `permission.reply`、`question` 完成态、assistant_square unsupported/dropped 事件的消费和降级规则。
- 补充本地调试证据路径：`plugin-uplink-capture.json`、`plugin-local-opencode-capture.json`、`miniapp-ws-capture.jsonl`。
- 新增 `08-all-event-examples.md`，补齐 GatewayMessage、OpenCode/local、cloud skill-provider、CloudEventTranslator standard extension、assistant_square SSE、最终 `StreamMessage` 的全事件 JSON 示例。

### Removed

- 旧 `documents/protocol` 继续退役，不恢复。

### Notes

- 旧文档只作为结构模板参考，不作为协议事实来源。
- 当前事实来源是代码、测试、本地调试抓包和 Trellis business spec。
