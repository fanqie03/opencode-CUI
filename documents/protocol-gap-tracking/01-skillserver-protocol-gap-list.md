# SkillServer 协议拉齐差异清单

> 日期：2026-03-11
> 范围：`Layer1: Miniapp ↔ Skill Server`、`Layer2: Skill Server ↔ AI-Gateway`
> 目的：沉淀本轮 `skill-server` 与 `documents/protocol/` 的协议差异，作为后续逐条评审和关闭的单一事实源

---

## 1. 标题与背景

本清单用于记录当前 `skill-server` 实际接口行为与 `documents/protocol/` 目录下协议文档之间的不一致项。

本轮只覆盖与 `skill-server` 直接相关的协议面：

- Layer1：Miniapp ↔ Skill Server
- Layer2：Skill Server ↔ AI-Gateway

本清单不直接给出实施方案拆解，也不扩展到 `pc-agent`、`skill-miniapp` 的非直接接口细节。后续评审时，每条差异都应收敛到明确状态，避免长期停留在模糊结论。

---

## 2. 结论摘要

当前发现的差异主要集中在以下几类：

- 认证方式不一致：Layer2 WebSocket 建联认证文档与实现已漂移
- 消息集合不一致：Layer2 上行消息文档缺少 `permission_request`
- DTO 结构不一致：消息历史、发消息返回体、在线 Agent 列表存在字段名、字段集合、大小写差异
- 状态机不一致：`session.status` 的取值与文档不一致
- 流恢复协议不一致：`resume`、`snapshot.seq`、`streaming.parts[]` 语义与文档不一致
- 约束定义不一致：如 `imGroupId`、`toolSessionId` 的必填/可空约束不一致

建议优先按以下顺序评审：

1. 认证、状态机、消息类型集合
2. 前端直接依赖的 DTO：历史消息、流式消息、Agent 列表
3. 必填约束、错误分支、兼容字段

补充说明：

- Layer1 的 `question` 是服务端推给前端的消息类型
- Layer1 当前没有单独的 `question_reply` REST API
- 前端回答 `question` 时，实际调用的是 `POST /api/skill/sessions/{welinkSessionId}/messages`，并在请求体中携带 `toolCallId`
- Layer2 的 `question_reply` 是 SkillServer 发给 Gateway 的 invoke 动作，不是 Miniapp 直接调用的对外接口

---

## 3. 差异总表

| 编号 | 层级/接口 | 类型 | 协议定义 | 实际实现 | 差异说明 | 建议处理 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Layer2 WebSocket 建联认证 | 认证方式不一致 | 当前文档在“建联”段展示 `ws://gateway-host/ws/skill?token=<internal-token>`，路径上显式携带 `token` 参数 | 当前实现使用 `Authorization: Bearer <internal-token>` 发起连接，`gateway` 端优先校验 `Authorization`，query `token` 仅作为兼容回退；尚未切换为 `Sec-WebSocket-Protocol` 子协议认证 | 目标口径已明确：Layer2 不应再在 URL 上携带 `token`，并且应切换为子协议认证；当前文档和当前实现都不满足目标协议 | 以协议为准，Layer2 改为子协议认证，移除 URL `token` 方案；代码与文档同步修改，子协议载荷格式后续单独明确 | 需要改代码 |
| 2 | Layer2 Gateway → Skill 消息集合 | 文档缺项 | 文档定义的上行消息包括 `session_created`、`tool_event`、`tool_done`、`tool_error`、`agent_online`、`agent_offline` | `GatewayRelayService.handleGatewayMessage()` 还处理 `permission_request`，并将其转成 Layer1 的 `permission.ask` | 协议漏掉了一个真实存在的接口消息 | 以实现为准，补充 Layer2 文档中的 `permission_request` 定义、字段和示例 | 需要改文档 |
| 3 | Layer1 `POST /api/skill/sessions` 入参 | 必填/可空不一致 | 协议将 `imGroupId` 标记为必填 | `CreateSessionRequest` 包含 `imGroupId`，但 controller 未做必填校验 | 当前实现已允许会话不绑定默认 IM 群，文档比实现更严格 | 以实现为准，将 `imGroupId` 改为可选，并补充“不传表示未绑定默认 IM 群”的语义说明 | 需要改文档 |
| 4 | Layer1 REST 全局认证约定 | 行为语义不一致 | 文档写明“所有接口从 Cookie 中解析 `userId`” | 实际只有创建会话、会话列表、在线 Agent 列表读取 `userId` Cookie；多个接口未读取 Cookie，也未校验会话归属 | 出于安全要求，所有提供给 Miniapp 的接口都必须做身份认证和访问控制；当前实现缺少完整校验链 | 以协议为准，所有 Layer1 接口都必须从 Cookie 解析 `userId` 并做访问控制；带 `welinkSessionId` 的接口需校验会话归属。默认校验顺序为：先本地校验 `welinkSessionId -> session -> session.userId == Cookie.userId`，再基于 `session.ak` 调用 gateway 校验 `ak` 与 `userId` 归属关系；任一步失败都拒绝访问 | 需要改代码 |
| 4a | Layer1 REST 错误返回风格 | 行为语义不一致 | Layer1 文档约定所有 REST API 统一返回 HTTP `200 OK`，业务错误通过 `code` 和 `errormsg` 表达 | 当前多个 controller 仍直接返回 `400/404/409/500` 等 HTTP 状态码 | 当前实现未统一遵守 Layer1 REST 响应总约定 | 以协议为准，所有 Layer1 对 Miniapp 暴露的 REST 接口统一改为 HTTP `200`，业务失败通过 `code/errormsg` 表达 | 需要改代码 |
| 5 | Layer1 `POST /api/skill/sessions/{id}/messages` 出参 | 出参不一致 | 协议示例返回 `welinkSessionId`、`userId`、`role: "user"`、`messageSeq` 等协议化字段 | 实际直接返回 `SkillMessage`，字段为 `sessionId`，无 `userId`，`role` 为 Java enum，序列化值预期为 `USER` | 返回 DTO 未按协议适配；同时已确认不再要求响应中保留 `userId` | 两边都改：代码改为 Layer1 专用响应 DTO；文档从成功响应中移除 `userId`，保留 `welinkSessionId`、`role`、`content`、`messageSeq`、`createdAt` 等协议字段 | 需要改代码 |
| 6 | Layer1 `POST /messages` 用户归属描述 | 行为语义不一致 | 文档描述“从 Cookie 解析 `userId`，持久化 user message 到 MySQL（含 `userId`）” | `SkillMessage` 模型没有 `userId` 字段，`sendMessage()` 也未读取 Cookie | 当前已确认消息记录与响应中都不要求保留 `userId`；访问控制由第 4 条定义的鉴权链负责 | 修改文档，删除“消息持久化含 `userId`”的描述，避免把访问控制与消息存储绑定 | 需要改文档 |
| 7 | Layer1 `POST /messages` 缺少 `toolSessionId` 场景 | 行为语义不一致 | 文档默认先查到 `toolSessionId` 后再发 `invoke.chat/question_reply` | 实现中如果 `session.ak != null` 但 `toolSessionId == null`，会返回 `503 No toolSessionId available` | 当前错误场景真实存在，但返回风格与整体 REST 约定不一致 | 两边都改：保留“`toolSessionId` 未就绪会失败”的语义，但统一改为 HTTP `200`，在响应体中用非 `0` 的 `code` 和 `errormsg` 表达错误，并补充文档说明 | 需要改代码 |
| 8 | Layer1 `POST /api/skill/sessions/{id}/permissions/{permId}` | 行为语义不一致 | 文档只定义正常请求/响应 | 实际实现还校验 `response` 合法值、会话是否关闭、会话是否关联 agent | 协议未覆盖错误场景，且后续还需接入第 4 条定义的访问控制链 | 两边都改：文档补充失败场景；代码统一为 HTTP `200 + code/errormsg`，并接入会话归属校验 | 需要改代码 |
| 9 | Layer1 `GET /api/skill/sessions/{id}/messages` 外层分页 | 出参不一致 | 协议返回 `content/page/size/total` | `PageResult` 通过 `@JsonProperty` 输出 `page/size/total` | 外层分页壳已与协议对齐 | 当前项无实质差异，作为已对齐基线保留 | 已关闭 |
| 10 | Layer1 `GET /api/skill/sessions/{id}/messages` 消息字段 | 出参不一致 | 协议消息项使用 `welinkSessionId`、`userId`、小写 `role`、小写 `contentType` | 实际 `SkillMessageView` 返回 `sessionId`，无 `userId`，`role` 和 `contentType` 为 enum，预期为大写值 | 历史消息 DTO 与协议明显不一致；同时已确认不再要求保留 `userId` | 两边都改：定义 Layer1 历史消息专用 DTO；文档去掉 `userId`，消息字段统一为协议口径 | 需要改代码 |
| 11 | Layer1 `GET /messages` 中 tool part 字段名 | 出参不一致 | 协议示例中的历史消息 part 使用 `toolStatus`、`toolInput`、`toolOutput` | 实际 `SkillMessagePart` / snapshot 结构使用 `status`、`input`、`output` | 实时流、snapshot 与历史消息字段应保持一致，当前协议示例落后于实现 | 以实现为准，协议统一使用 `status`、`input`、`output`，删除 `toolStatus`、`toolInput`、`toolOutput` | 需要改文档 |
| 12 | Layer1 `POST /api/skill/sessions/{id}/send-to-im` | 行为语义不一致 | 文档定义成功返回 `{ success: true }` | 实际成功时一致，但失败时还会返回 `400` 或 `500` | 协议未覆盖失败场景，且错误返回风格需要与整体 REST 约定统一 | 两边都改：补协议错误场景，代码统一为 HTTP `200 + code/errormsg` | 需要改代码 |
| 13 | Layer1 `GET /api/skill/agents` | 出参不一致 | 协议示例强调 `ak`、`akId`、`toolType`、`toolVersion`、`deviceName`、`os` | SkillServer 代理 Gateway 结果并补 `akId`，但 Gateway 实际还返回 `status`、`connectedAt`，且 `toolType` 被转为小写 | 当前真实返回字段比文档更完整 | 以实现为准，将 `status`、`connectedAt` 等当前真实字段补入协议说明，并区分必填/可选 | 需要改文档 |
| 14 | Layer1 `GET /api/skill/agents` `toolType` 值 | 枚举值不一致 | Layer1 文档示例为 `OPENCODE` 大写 | Gateway REST 实际输出中 `toolType` 会被 `toLowerCase()`，示例更接近 `opencode` 或其他小写值 | 文档示例和真实值格式不一致 | 以实现为准，统一 `toolType` 使用小写协议值 | 需要改文档 |
| 15 | Layer1 `ws://host/ws/skill/stream` 方向定义 | 行为语义不一致 | 文档定义为“服务端 → 客户端（单向推送）” | 实际客户端支持发送 `{"action":"resume"}` 触发重放 | 实际协议包含一个客户端上行动作 | 以实现为准，补充 `resume` 客户端动作的语义和触发结果 | 需要改文档 |
| 16 | Layer1 StreamMessage 公共字段 `seq` | 出参不一致 | 文档规定所有消息都应带 `seq` | `pushStreamMessage()` 会补 `seq`，但 `sendSnapshot()` 构造 `snapshot` 时未设置 `seq` | `snapshot` 不满足公共字段约定 | 以协议为准，为 `snapshot` 补齐 `seq`，纳入统一传输序号体系 | 需要改代码 |
| 17 | Layer1 `session.status` 值域 | 枚举值不一致 | 文档定义 `busy / idle / retry` | 实际实现会发 `active`、`idle`、`reconnecting`；Translator 还可能透传 OpenCode 原始状态值 | 状态值集合已发生漂移，前端状态机会被迫兼容多套值域 | 以协议为准，统一对外状态值为 `busy / idle / retry`，移除 `active / reconnecting` 对外暴露 | 需要改代码 |
| 18 | Layer1 `snapshot` 消息结构 | 出参不一致 | 文档要求 `snapshot.messages[]` 为已完成消息快照，字段以协议化 DTO 为准 | 实现整体思路一致，但具体消息字段仍沿用内部模型映射，和历史消息 API 的字段问题相同 | `snapshot` 方向正确，但底层消息 DTO 未统一 | 以协议为准，`snapshot.messages[]` 复用统一后的历史消息 DTO | 需要改代码 |
| 19 | Layer1 `streaming.parts[]` 语义 | 行为语义不一致 | 文档将 `parts[]` 定义为恢复态下的 part 聚合对象，`type` 取值为 `text/thinking/tool/question/permission/file` | 实现中 `parts` 直接放 `List<StreamMessage>`，更接近事件对象集合，`type` 可能是 `text.delta`、`tool.update`、`question` 等 | `streaming` 恢复态应表达“当前累计状态”，不应原样暴露事件列表 | 以协议为准，`streaming.parts[]` 输出聚合后的 part 快照，不再直接返回事件对象列表 | 需要改代码 |
| 20 | Layer1 `streaming.sessionStatus` | 枚举值不一致 | 文档在 `streaming` 中仅列 `busy / idle` | 实现目前由 `bufferService.isSessionStreaming()` 推导出 `busy / idle`，但整体系统又存在 `active / reconnecting` 状态流 | `streaming` 自身口径可继续保持 `busy / idle`，真正问题已由第 17 条覆盖 | 保持现口径，不单独作为整改项推进 | 已关闭 |
| 21 | Layer2 `invoke.permission_reply` payload | 必填/可空不一致 | 协议要求 `toolSessionId`、`permissionId`、`response` 都必填 | 实现构造 payload 时只有 `permissionId`、`response` 必然写入，`toolSessionId` 是“有则带” | 缺少 `toolSessionId` 时不应发送不完整的权限回复 | 以协议为准，发送 `permission_reply` 前必须确保 `toolSessionId` 存在，否则在 SkillServer 本层返回业务错误 | 需要改代码 |
| 22 | Layer2 `invoke.question_reply` payload | 必填/可空不一致 | 协议要求 `toolSessionId`、`toolCallId`、`answer` 都必填 | 实现中 `toolCallId`、`answer` 必带，`toolSessionId` 仍是“有则带” | `question_reply` 必须精确指向某个会话中的 question tool call，缺少 `toolSessionId` 时请求不完整 | 以协议为准，发送 `question_reply` 前必须确保 `toolSessionId` 存在，否则在 SkillServer 本层返回业务错误 | 需要改代码 |
| 23 | Layer2 上行消息会话解析策略 | 实现多余兼容 | 文档主要描述通过 `toolSessionId -> welinkSessionId` 定位会话 | 实现优先读 `welinkSessionId` / `sessionId`，缺失时才 fallback 到 `toolSessionId -> DB` | 已明确内部不应长期并存 `welinkSessionId/sessionId/toolSessionId` 三套命名，只应保留 `welinkSessionId` 和 `toolSessionId` | 以协议为准，清理 `sessionId` 兼容逻辑；Layer2 上行消息只保留 `welinkSessionId` 与 `toolSessionId` 两套标识 | 需要改代码 |
| 24 | Layer2 `session_created` 字段兼容 | 实现多余兼容 | 文档要求使用 `welinkSessionId` | 实现同时兼容 `welinkSessionId` 和旧字段 `sessionId` | 旧字段 `sessionId` 不应继续作为并行协议字段存在 | 以协议为准，`session_created` 只接受/处理 `welinkSessionId`，移除旧字段 `sessionId` 兼容逻辑 | 需要改代码 |

---

## 4. 高优先级问题

建议先从以下问题开始逐条评审：

### 4.1 Layer2 认证方式

- 这是服务间建联的入口协议
- 文档和实现已明确漂移
- 若不先定口径，后续联调和运维说明会持续混乱

### 4.2 Layer2 消息集合

- `permission_request` 是真实存在的消息
- 该消息直接影响 Layer1 的 `permission.ask`
- 如果文档缺失，后续无法完整覆盖权限审批链路

### 4.3 Layer1 `session.status` 状态机

- 当前至少存在两套值域：`busy/idle/retry` 与 `active/idle/reconnecting`
- 前端状态展示、恢复流程、错误恢复都会依赖它
- 这是典型的高风险差异，必须优先统一

### 4.4 Layer1 消息 DTO

- `POST /messages`、`GET /messages`、`snapshot`、`streaming` 目前不是同一套稳定协议 DTO
- 这会导致前端对历史消息和实时消息写出不同的兼容逻辑

### 4.5 Layer1 在线 Agent 列表

- 该接口是前端的会话入口依赖之一
- 目前字段集合和字段值格式都存在漂移
- 如果不尽早定口径，后续文档和前端类型会继续分叉

### 4.6 `question` 与 `question_reply` 的层级区分

- `question` 是 Layer1 WebSocket 消息类型，用于把 AI 提问推给前端
- Miniapp 回答 `question` 时，当前入口是 `POST /messages`，通过 `toolCallId` 进入问答分支
- `question_reply` 是 Layer2 invoke 动作，用于 SkillServer 向 Gateway 转发用户回答
- 后续评审时需要避免把“消息类型”和“下行动作”混为同一层协议对象

---

## 5. 处理原则与判定口径

后续逐条评审时，建议遵循以下原则：

### 5.1 先定单一事实源

每条差异先判断“以协议为准”还是“以实现为准”，避免同时调整两边但没有明确基准。

### 5.2 优先收敛前端可见协议

凡是会被 Miniapp 直接消费的字段和状态，优先保证：

- 字段名稳定
- 值域稳定
- 历史消息和实时消息口径一致

### 5.3 兼容逻辑要显式记录

如果某处实现保留了旧字段兼容或回退策略，协议文档需要明确说明：

- 这是长期协议的一部分
- 还是临时兼容窗口

### 5.4 必填项只保留一套定义

像 `imGroupId`、`toolSessionId` 这类字段，不应出现“文档必填、实现可空”长期并存的状态。每条都需要明确：

- 调整代码去匹配协议
- 或调整协议去承认实现

### 5.5 状态字段必须统一值域

所有状态字段都需要明确：

- 允许值列表
- 各值语义
- 是否允许兼容旧值

特别是 `session.status`，必须作为优先项收敛。

---

## 6. 后续评审状态值

本清单初版默认使用 `待确认`。经本轮评审后，条目状态应逐步收敛到以下状态之一：

- `以协议为准`
- `以实现为准`
- `需要改代码`
- `需要改文档`
- `已关闭`
