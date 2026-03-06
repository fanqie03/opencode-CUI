# 协议设计文档

OpenCode CUI 消息协议设计方案，涵盖从 OpenCode 事件到前端展示的完整链路。

## 文档索引

| 文件                                                             | 内容                                                    | 状态     |
| ---------------------------------------------------------------- | ------------------------------------------------------- | -------- |
| [01-opencode-event-protocol.md](./01-opencode-event-protocol.md) | OpenCode SDK 完整事件协议（31 Event + 12 Part）         | ✅ 已完成 |
| [02-stream-protocol-design.md](./02-stream-protocol-design.md)   | Skill Server → 前端 StreamMessage 协议（17 种消息类型） | ✅ 已完成 |
| [03-persistence-design.md](./03-persistence-design.md)           | Skill Server 消息持久化方案（消息+部件两层模型）        | ✅ 已完成 |
| [04-reconnect-design.md](./04-reconnect-design.md)               | 断线重连与会话恢复方案（Redis 累积 + 三阶段恢复）       | ✅ 已完成 |
| [05-implementation-plan.md](./05-implementation-plan.md)         | 分阶段实施计划（5 Phase，~10h，10 新 + 11 改）          | ✅ 已完成 |
| [06-requirements.md](./06-requirements.md)                       | 需求文档 — 需求背景 / 目标 / 协议总览 / UI 映射         | ✅ 已完成 |
| [07-progress.md](./07-progress.md)                               | 任务进展 — Phase 1~4 完成详情 + 文件清单 + 验证结果     | ✅ 已完成 |
| [08-test-cases.md](./08-test-cases.md)                           | 测试用例 — 后端单元 / 前端单元 / 集成测试 / 异常 Case   | ✅ 已完成 |

## UI 效果参考

| 效果图                                   | 说明                                      |
| ---------------------------------------- | ----------------------------------------- |
| [question-ui.png](./question-ui.png)     | AI 提问交互 — 标题+问题+选项按钮+自由输入 |
| [permission-ui.png](./permission-ui.png) | 权限审批交互 — 操作描述+允许/拒绝按钮     |

## 数据流总览

```
OpenCode → PC Agent → Gateway → Skill Server → Redis → WebSocket → 前端
                                     │              │
                                     │   实时累积缓冲 │
                                     ↓              ↓
                                   MySQL        前端 resume
                                  (最终态)      (三阶段恢复)
```
