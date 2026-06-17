# 业务规范索引

> 本目录记录跨 `skill-server`、`ai-gateway`、`skill-miniapp` 的业务流程和场景边界。它不是替代各技术层 spec，而是作为业务语义入口，帮助先对齐“这个场景是什么”，再进入具体代码层。

## 当前业务场景

| 场景 | 文档 | 状态 |
| --- | --- | --- |
| external / IM 单聊 | [external-im-direct-chat.md](external-im-direct-chat.md) | 已记录 |
| external / IM 群聊 | [external-im-group-chat.md](external-im-group-chat.md) | 已记录 |
| miniapp 对话 | [miniapp-chat.md](miniapp-chat.md) | 已记录 |

## 横切业务场景

| 场景 | 文档 | 状态 |
| --- | --- | --- |
| 协议文档 | [protocol/README.md](protocol/README.md) | 已记录 |
| 助手类型矩阵 | [assistant-type-matrix.md](assistant-type-matrix.md) | 已记录 |
| toolSessionId 生命周期 | [tool-session-lifecycle.md](tool-session-lifecycle.md) | 已记录 |

## 使用方式

涉及聊天业务流程时，先读对应业务场景文档，再按改动范围进入技术层 spec：

- `skill-server` 后端：`.trellis/spec/skill-server/backend/index.md`
- `ai-gateway` 后端：`.trellis/spec/ai-gateway/backend/index.md`
- `skill-miniapp` 前端：`.trellis/spec/skill-miniapp/frontend/index.md`

## 待补场景

- 下行投递场景
