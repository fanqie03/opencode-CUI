# PRD: skill 会话删除支持多端同步

## 目标

新增硬删除会话功能：用户删除会话后，数据库物理删除所有关联数据，通过 WebSocket 实时同步到所有设备，并通知 Agent 释放资源。

同时将现有 close 接口调整为语义更清晰的路径。

## 背景

现有 `DELETE /api/skill/sessions/{id}` 是"关闭"操作（soft close，status=CLOSED），不能满足用户彻底删除会话的需求。

## 已确认事实

### 现有架构
- 关闭流程：`SkillSessionFlowService.closeSession()` → DB status=CLOSED → [可选] Gateway close_session
- 关闭流程**不推送 WS 事件**给客户端
- 多端推送基础设施已具备：`user-stream:{userId}` Redis pub/sub + `StreamMessageEmitter.emitToClient()`
- StreamMessage 类型命名规范：`session.status`、`session.title`、`session.error`，新增类型应为 `session.deleted`
- 访问控制：`SessionAccessControlService.requireSessionAccess` 校验 cookie userId

### 需删除的数据
- MySQL：skill_session、skill_message、skill_message_part
- Session route（SessionRouteService）
- Redis：`ss:tool-session:{toolSessionId}`、`ss:stream-seq:{sessionId}`、`skill:history:latest:{sessionId}:{size}`、stream buffer

## 需求

### API 端点变更

| 操作 | 旧端点 | 新端点 |
|------|--------|--------|
| 关闭会话 | `DELETE /api/skill/sessions/{id}` | `POST /api/skill/sessions/{id}/close` |
| 删除会话 | —（新增） | `DELETE /api/skill/sessions/{id}` |

### 核心需求
1. **新增删除 API**：`DELETE /api/skill/sessions/{id}` — 硬删除会话及所有关联数据
2. **多端同步**：删除后推送 `session.deleted` WS 事件到用户所有设备（通过 `user-stream:{userId}`）
3. **Gateway 通知**：发送 `close_session` invoke，Agent 释放资源
4. **ACTIVE 会话处理**：先 abort（持久化缓冲 → IDLE），再执行硬删除
5. **close 接口迁移**：原 `DELETE /{id}` → `POST /{id}/close`

### 涉及模块
- **skill-server**：Controller、Service、Repository、StreamMessage、Redis 缓存
- **skill-miniapp**：`api.closeSession()` 路径更新、新增 `api.deleteSession()`、WS `session.deleted` 事件处理

## 验收标准

1. `DELETE /api/skill/sessions/{id}` 能硬删除会话及关联的 message、part、route、Redis 缓存
2. ACTIVE 会话删除时，先 abort 再删除，不丢流式数据
3. 删除后，该用户所有 WebSocket 连接收到 `session.deleted` 事件
4. 删除后，Gateway 收到 `close_session` invoke
5. `POST /api/skill/sessions/{id}/close` 保留原有关闭行为
6. 前端 close 调用正常工作（路径已更新）
7. 前端收到 `session.deleted` 后自动更新 UI

## 范围外
- abort 操作的多端同步
- 批量删除会话
- 会话恢复（回收站）
