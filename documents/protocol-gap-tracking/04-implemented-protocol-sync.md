# 已落地实现的协议同步记录

> 日期：2026-03-11  
> 范围：`documents/protocol/01-layer1-miniapp-skill-api.md`、`documents/protocol/02-layer2-skill-gateway-api.md`

## 说明

由于历史协议文档存在编码问题，本次没有大段重写原正文，而是在原文档末尾追加“2026-03-11 实现同步补充”附录。

后续阅读口径如下：

- 正文与附录冲突时，以附录为准
- 本文件用于记录本次已同步的实现结果，避免遗漏

## 已同步到 Layer1 文档的口径

- Layer1 REST 统一 HTTP `200 OK`，业务结果通过 `code` / `errormsg` 表达
- 所有 Miniapp 接口都要求 Cookie `userId` 身份认证和访问控制
- `welinkSessionId` 访问控制链路补充为“本地会话归属校验 + Gateway `ak ↔ userId` 归属校验”
- `POST /api/skill/sessions` 的 `imGroupId` 改为可选
- `POST /messages` 成功响应移除 `userId`
- `GET /messages` 历史消息响应移除 `userId`
- 历史消息与 `snapshot.messages[]` 复用同一套协议 DTO
- 历史消息与恢复态中的 tool part 统一使用 `status` / `input` / `output`
- `GET /api/skill/agents` 补充 `status`、`connectedAt`
- `toolType` 统一为小写协议值
- `resume` 客户端动作已补充
- `resume` 后重放顺序固定为先 `snapshot` 再 `streaming`
- `snapshot` 补充必须携带 `seq`
- `session.status` 对外值域统一为 `busy` / `idle` / `retry`
- `streaming.sessionStatus` 仅使用 `busy` / `idle`
- `streaming.parts[]` 明确为聚合后的恢复态 part 快照
- `question` 与 Layer2 `question_reply` 的层级区分已补充

## 已同步到 Layer2 文档的口径

- Layer2 WebSocket 改为 `Sec-WebSocket-Protocol` 子协议认证
- Layer2 WebSocket URL 不再携带 `token`
- Layer2 WebSocket 不再以 `Authorization` 头作为主路径
- 子协议载荷格式补充为 `auth.<base64url-encoded-json>`
- 载荷 JSON 补充为 `{ "token": "<internal-token>" }`
- Layer2 只保留 `welinkSessionId` 和 `toolSessionId`
- 协议中移除 `sessionId` 作为并行命名的口径
- `invoke.chat` / `invoke.permission_reply` / `invoke.question_reply` 的 `toolSessionId` 明确为必填
- Gateway → Skill Server 的上行消息补充 `permission_request`
- `agent_online.toolType` 统一为小写协议值
- Layer2 REST 继续使用 `Authorization: Bearer <internal-token>`

## 已同步的 ID 类型基线

- `welinkSessionId`
  - `skill-server` Layer1 对外协议 DTO 已收敛为 `Long`
  - Layer1 WebSocket 对外 JSON 已收敛为数字型 `welinkSessionId`
  - `ai-gateway` Layer2 `GatewayMessage.welinkSessionId` 已收敛为 `Long`
  - `skill-miniapp` 前端类型已收敛为 `number`
- `toolSessionId`
  - 后端与前端继续统一为 `String/string`
- `userId`
  - 后端与前端继续统一为 `String/string`
  - 数据库最终字段继续统一为 `VARCHAR(128)`

## 尚未在正文逐段改写的原因

- 原协议正文存在编码异常
- 当前优先目标是先提供可执行、可引用的最新协议口径
- 等后续确认需要整体整理协议文档编码时，再统一做正文重构
