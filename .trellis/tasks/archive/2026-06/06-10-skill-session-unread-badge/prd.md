# PRD (V2): 未读消息小红点提醒与已读多端同步

> 方案1 备份在 `prd-v1-backup.md` 和 `design-v1-backup.md`（Git 历史可回溯）。

## 目标

在 skill-miniapp 会话列表侧边栏中展示未读消息数字角标，前端追踪已渲染的最大 message_seq 并上报已读，已读状态通过 IM API 或 WebSocket 实时同步到同一用户的所有设备。

## 背景

当前 skill-miniapp 会话列表不展示未读提示，用户无法感知其他会话是否有新消息。代码库中不存在未读/已读追踪机制（无 DB 列、无模型字段、无 API 字段、无前端角标）。

### 可复用基础设施

- `skill_message.seq`：会话内递增序号 + `uk_skill_message_session_seq` 唯一约束，可靠且严格递增
- `ImOutboundService`：已有 `RestTemplate` 调 IM REST API 的基础设施
- `StreamMessageEmitter`：统一出站入口（ws 模式 WS 推送复用）

## 核心决策

| # | 决策 | 结论 |
|---|------|------|
| 1 | 范围 | 仅 miniapp 场景 |
| 2 | 已读触发 | 前端上报（已渲染的最大 message_seq 变化时上报，含节流） |
| 3 | 已读粒度 | 前端自行追踪 `readMessageSeq`，服务端 Redis Hash 维护 `maxSeq`，Lua 原子比较 |
| 4 | 已读/未读存储 | 纯 Redis Hash `ss:unread:{userId}:{assistantAccount}`（sessionId→maxSeq），按助手隔离，Lua 自愈无需 DB 兜底 |
| 5 | 多端同步 | `MultiDeviceSyncService` 接口 + 复合实现，按 `unread.sync-mode`（ws/im）路由 |
| 6 | 同步实现（cloud） | Redis pub/sub `user-stream:{userId}` → WS 广播 `session.unread` |
| 7 | 同步实现（inner） | IM API `/v1/app-notify` 广播 |
| 8 | 活跃会话追踪 | **不做**，服务端一律推送，前端自行判断是否显示角标 |
| 9 | 免打扰判断 | 前端判断：若正在看某会话则不显示角标 |
| 10 | UI 形式 | 红点（二态：有未读显示红点，无未读不显示） |
| 11 | 推送时机 | 消息落库后推送（tool_done 处理链中） |
| 12 | 流式渲染期间的已读 | 消息未完全渲染（流式进行中）不更新 `readMessageSeq`；单个消息渲染完成后才更新 |
| 13 | 未读查询 | 单一 `POST /unread`（assistantAccount + sessionIds 可选）：不传 sessionIds→全部未读会话详情；传入→指定会话详情 |
| 14 | 存储策略 | 纯 Redis Hash `ss:unread:{userId}:{assistantAccount}`，按助手隔离，无 MySQL 持久化，接受 Redis 故障后自愈重建 |
| 15 | IM 乱序保护 | 推送消息携带 `maxSeq`，前端单调校验（仅当 `maxSeq >= 当前已知maxSeq` 时应用），防止 IM 通道消息乱序导致红点错误闪烁 |
| 16 | 硬删除清理 | `SessionDeletedEvent` 监听 → `HDEL ss:unread:{userId} {sessionId}`，清理已删除会话的残留 Hash field |
| 17 | 前端架构 | `useReadTracking`（readMessageSeq 追踪 + debounce + REST 上报 + 流式保护）+ `useUnreadBadge`（拉取 + 推送处理 + 角标状态）|
| 18 | Lua 简化 | `updateMaxSeq` 仅返回 0/1（1=需同步）；`markRead` 仅返回 0/1（1=需同步+HDEL）；去掉了 return 2 中间态 |
| 19 | Domain 白名单 | `skill.unread.session-domain-whitelist` 配置（默认 `miniapp`），仅白名单 domain 的会话触发未读/已读逻辑 |
| 20 | IM 重试补偿 | Spring Retry 注解驱动，`@Retryable(maxAttempts=5, delay=1s, multiplier=2)`，参数由 `skill.sync.im.retry.*` 配置注入 |

## 需求

### 核心需求

1. **未读角标**：会话列表每个会话项展示未读消息数字角标
2. **前端已读上报**：前端追踪 `readMessageSeq`，变化时 POST 上报，服务端 Lua `markRead` 原子比较并清除
3. **流式渲染保护**：流式输出进行中不更新已读游标，单条消息完整渲染后才推进
4. **多端同步**：设备 A 上报已读后，设备 B 的角标通过同步通道消失
5. **未读信息独立查询**：单一 `POST /unread`（sessionIds 可选）— 不传返总数，传入返详情列表，与会话列表接口解耦
6. **离线后感知**：用户离线再进入应用时，通过未读查询获取最新角标数据

### 涉及模块

- **skill-server**：Controller、Service、StreamMessage、WebSocket、Redis Lua、IM API client（无 DB 变更）
- **skill-miniapp**：api.ts、SessionSidebar、useSkillSession、useSkillStream、Session 类型、readMessageSeq 追踪

## 前后端数据流

### 已读上报（前端 → 后端）

```
前端消息渲染完成 → readMessageSeq 变化 → debounce 500ms → POST /api/skill/sessions/{id}/read
→ 服务端 Lua markRead → 条件发布 ReadReportedEvent → 广播
```

### 未读查询（离线后进入应用时用）

```
前端进入应用 / 回到前台 / WS 重连 / 侧边栏渲染
  → POST /api/skill/sessions/unread { assistantAccount }
  → 服务端 HGETALL 返回所有未读会话
  → 返回 { unreadSessionCount, unreadSessionList }
  → 前端更新各会话红点
```

### 未读推送（事件驱动，零侵入现有逻辑）

```
handleToolDone → Lua updateMaxSeq → return 1 才发布 ToolDoneEvent
→ UnreadPushListener 监听 → Lua updateMaxSeq → return 1 → MultiDeviceSyncService.push(session.unread { ... })
→ cloud: WS 广播 / inner: IM API /v1/app-notify
→ 前端收到 → 更新角标

updateMaxSeq=2（已处未读态）不发布事件，避免重复播报。
```

### 已读上报后的同步

```
SkillSessionService.reportRead → 发布 ReadReportedEvent
→ ReadReportedListener 监听 → 计算剩余未读数
→ MultiDeviceSyncService.push(session.unread { ... }) → 多端清除角标（立即推送，不延迟）
```

## 验收标准

1. 非活跃会话收到新消息后，会话列表出现红点角标
2. 前端渲染完成消息后上报已读，红点消失
3. 流式输出进行中切换会话，不推进 `readMessageSeq`
4. 设备 A 上报已读，设备 B 上红点同步消失（推送携带 maxSeq，前端单调校验防乱序）
5. im 模式：IM API `/v1/app-notify` 正确调用
6. ws 模式：WS `session.unread` 正常广播
7. `POST /unread`（不传 sessionIds 返回全部未读详情，传入返回指定详情）与实际一致
8. Hash `ss:unread:{userId}` TTL 7d，Lua 自愈无 DB 依赖
9. 离线后打开应用，红点正确显示
10. 会话硬删除后，对应 Hash field 被清理

## 范围外

- IM 场景的未读管理（由 IM 平台负责）
- 消息级已读/未读标记（仅支持会话级游标）
- 离线推送（APNs/FCM）
- 未读消息预览摘要
- 活跃会话服务端追踪
