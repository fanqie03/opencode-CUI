# ROADMAP.md

> **Current Phase**: Phase 3 ✅
> **Milestone**: v1.0 — Web UI 全功能联调版

## Must-Haves (from SPEC)

- [ ] PC Agent ↔ Gateway AK/SK 认证长连接
- [ ] Web UI 实时流式对话（支持 17 种 StreamMessage）
- [ ] 会话创建、切换、列表
- [ ] 消息持久化 + 历史加载
- [ ] 断线重连 + 状态恢复
- [ ] IM 推送（选择回答发送到群聊）
- [ ] 多实例部署支持

## Phases

### Phase 1: 端到端协议报文定义
**Status**: ✅ Complete
**Objective**: 逐一定义并确认 Miniapp ↔ Skill Server ↔ AI-Gateway ↔ PC Agent ↔ OpenCode 各环节的协议报文，形成最终版协议规范
**Requirements**: 对每个环节的每一个报文格式（REST 请求/响应、WebSocket 消息、SDK 调用）进行独立定义，每个报文单独与用户确认，确保协议在实现前完全一致
**Deliverable**: 完整的协议报文规范文档（每个报文含字段定义、示例 JSON、校验规则），作为后续所有实现的唯一参考

---

### Phase 2: Gateway 架构简化与 AK 路由改造
**Status**: ✅ Complete
**Objective**: 按 Gap Analysis 结论简化 Gateway 路由架构——移除 session 级路由、agent channel 改用 ak 做 key
**Depends on**: Phase 1
**Source**: [Gap Analysis — Redis Pub/Sub 影响分析](file:///C:/Users/15721/.gemini/antigravity/brain/b295cd8f-2d9a-422d-8d16-98a7b01a5e9b/gap_analysis.md)

**Tasks**:
- [ ] 删除 `gw:session:skill-owner:{sessionId}` 相关代码（SkillRelayService: bindSession/resolveSessionOwner/touchSessionOwner）
- [ ] 删除 RedisMessageBroker 中 setSessionOwner/getSessionOwner/clearSessionOwner/sessionOwnerKey
- [ ] 上行消息路由改为"发给任意有 Skill 连接的 Gateway 实例"（SkillRelayService）
- [ ] agent channel 从 `agent:{agentId}` 改为 `agent:{ak}`（EventRelayService + RedisMessageBroker）
- [ ] AgentWebSocketHandler 注册逻辑改为以 `ak` 为 key
- [ ] MessageEnvelope 残留代码清理

**Verification**:
- Gateway 多实例部署下 invoke 消息正确路由到 PC Agent
- 上行 tool_event 消息正确路由到任意 Skill 实例
- 不存在 gw:session:skill-owner:* Redis key

---

### Phase 3: Skill Server 协议对齐
**Status**: ✅ Complete
**Objective**: 将 Skill Server 的 REST API 和 Gateway 通信对齐目标协议，解决 P0/P1 级别的字段缺失和类型不匹配
**Depends on**: Phase 2
**Source**: [Gap Analysis — 层① & 层②](file:///C:/Users/15721/.gemini/antigravity/brain/b295cd8f-2d9a-422d-8d16-98a7b01a5e9b/gap_analysis.md)

**Tasks**:
- [ ] 发送消息 API 增加 `toolCallId` 可选字段 (P0)
- [ ] 消息发送逻辑：有 toolCallId → invoke.question_reply，无 → invoke.chat (P0)
- [ ] 新增 `POST /sessions/{id}/abort` REST API (P1)
- [ ] 权限回复字段从 `approved: Boolean` 改为 `response: String("once"/"always"/"reject")` (P1)
- [ ] 创建会话字段：`agentId` → `ak`，`skillDefinitionId` 移除，`imChatId` → `imGroupId` (P1)
- [ ] invoke 路由字段从 `agentId` 改为 `ak` (P1)
- [ ] 上行消息处理增加 `toolSessionId → welinkSessionId` DB 查找逻辑 (Redis 新方案)
- [ ] 新增 invoke.abort_session 下行构建 (P0)

**Verification**:
- 发送带 toolCallId 的消息后生成 question_reply invoke
- abort API 可调用且生成 abort_session invoke
- 权限回复支持 once/always/reject
- 上行 tool_event 可通过 toolSessionId 正确路由到 session channel

---

### Phase 4: PC Agent 协议对齐
**Status**: ✅ Complete
**Objective**: 补齐 PC Agent 缺失的 invoke handler，修复错误调用
**Depends on**: Phase 2
**Source**: [Gap Analysis — 层④](file:///C:/Users/15721/.gemini/antigravity/brain/b295cd8f-2d9a-422d-8d16-98a7b01a5e9b/gap_analysis.md)

**Tasks**:
- [ ] 新增 `invoke.question_reply` handler → 调用 `session.prompt()` (P0)
- [ ] 新增 `invoke.abort_session` handler → 调用 `session.abort()` (P0)
- [ ] 修复 `invoke.close_session` → 从 `session.abort()` 改为 `session.delete()` (P0)
- [ ] 移除 PC Agent 内存 sessionMap 对 welinkSessionId 的映射依赖（新架构不再需要）

**Verification**:
- question_reply invoke 正确触发 session.prompt
- abort_session invoke 正确触发 session.abort
- close_session invoke 正确触发 session.delete（非 abort）
- PC Agent 重启后上行消息仍正常传递

---

### Phase 5: 协议规范化
**Status**: ✅ Complete
**Objective**: 统一全链路 ID 命名、响应格式、清理技术债
**Depends on**: Phase 3, Phase 4
**Source**: [Gap Analysis — P2](file:///C:/Users/15721/.gemini/antigravity/brain/b295cd8f-2d9a-422d-8d16-98a7b01a5e9b/gap_analysis.md)

**Tasks**:
- [ ] 全链路 ID 命名：代码中 `sessionId` 对外暴露为 `welinkSessionId` (P2)
- [ ] Skill Server REST API 统一响应格式 `{code, errormsg, data}` (P2)
- [ ] 清理 envelope 残留代码（Gateway MessageEnvelope + Skill GatewayRelayService）(P2)
- [ ] Gateway 实现 `status_query` 主动查询 (P2)

**Verification**:
- 所有 REST API 响应统一为 `{code, errormsg, data}` 格式
- 前端收到的 StreamMessage 中 session ID 字段名为 welinkSessionId
- 代码中无 envelope 相关引用
- Gateway 可主动查询 Agent 的 OpenCode 运行状态

---

### Phase 6: 端到端集成验证
**Status**: ⬜ Not Started
**Objective**: 验证 Phase 2-5 改造后的端到端能力，确认全链路基本可用
**Requirements**: 确认协议对齐改造与协议设计一致，修复集成中发现的问题
**Deliverable**: 一个可运行的端到端 demo，用户在 Web UI 上发消息 → OpenCode 回答流式渲染

### Phase 7: 会话管理完善
**Status**: ⬜ Not Started
**Objective**: 完善会话生命周期管理，支持多会话创建/切换/列表/隔离
**Requirements**: 会话 CRUD API、Web UI 会话选择器、按群聊维度隔离、同一时间单会话生效
**Deliverable**: Web UI 中可创建新会话、切换会话、查看会话列表

### Phase 8: Gateway 多实例路由验证
**Status**: ⬜ Not Started
**Objective**: 验证 Phase 2 改造后的多实例路由在双 Gateway 部署下正常工作
**Requirements**: 跨实例 invoke 路由、跨实例上行消息转发、Skill 连接断重连
**Deliverable**: 双 Gateway 实例部署下功能正常

### Phase 9: IM 推送与 Web UI 打磨
**Status**: ⬜ Not Started
**Objective**: 实现 IM 消息推送能力，并打磨 Web UI 达到小程序等效体验
**Requirements**: 选择回答发送到 IM、Web UI 交互优化、错误处理、边界场景覆盖
**Deliverable**: Web UI 可完全替代小程序进行联调，IM 推送可用
