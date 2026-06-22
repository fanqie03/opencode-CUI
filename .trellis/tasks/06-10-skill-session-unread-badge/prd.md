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
| 4 | 已读/未读存储 | 纯 Redis Hash `ss:unread:{userId}`（sessionId→maxSeq，TTL 7d），Lua 自愈无需 DB 兜底 |
| 5 | 多端同步 | `MultiDeviceSyncService` 接口 + 复合实现，按 `unread.sync-mode`（ws/im）路由 |
| 6 | 同步实现（cloud） | Redis pub/sub `user-stream:{userId}` → WS 广播 `session.unread` |
| 7 | 同步实现（inner） | IM API `/v1/app-notify` 广播 |
| 8 | 活跃会话追踪 | **不做**，服务端一律推送，前端自行判断是否显示角标 |
| 9 | 免打扰判断 | 前端判断：若正在看某会话则不显示角标 |
| 10 | UI 形式 | 数字角标（超过 99 显示 `99+`） |
| 11 | 推送时机 | 消息落库后推送（tool_done 处理链中） |
| 12 | 流式渲染期间的已读 | 消息未完全渲染（流式进行中）不更新 `readMessageSeq`；单个消息渲染完成后才更新 |
| 13 | 未读查询 | 单一 `POST /unread`（sessionIds 可选）：不传→总数，传入→详情列表 |

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

### 未读查询（两步拉取，离线后进入应用时用）

```
1. 前端进入应用 / 回到前台 / WS 重连
   → POST /api/skill/sessions/unread（不传 sessionIds）
   → 服务端 HLEN 返回有未读的会话总数
   → 返回 { unreadSessionCount: N }
   → 前端决定是否显示全局红点

2. 侧边栏渲染可见会话列表
   → POST /api/skill/sessions/unread { sessionIds: [...] }
   → 服务端 HLEN + HMGET 返回总数+详情
   → 返回 { unreadSessionCount, unreadSessionList }
   → 前端更新各会话角标
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

1. 非活跃会话收到新消息后，会话列表出现数字角标
2. 前端渲染完成消息后上报已读，角标消失
3. 流式输出进行中切换会话，不推进 `readMessageSeq`
4. 设备 A 上报已读，设备 B 上角标同步消失
5. im 模式：IM API `/v1/app-notify` 正确调用
6. ws 模式：WS `session.unread` 正常广播
7. `POST /unread`（sessionIds 可选/传入）返回未读信息与实际一致
8. Hash `ss:unread:{userId}` TTL 7d，Lua 自愈无 DB 依赖
9. 离线后打开应用，未读角标正确显示

## 范围外

- IM 场景的未读管理（由 IM 平台负责）
- 消息级已读/未读标记（仅支持会话级游标）
- 离线推送（APNs/FCM）
- 未读消息预览摘要
- 活跃会话服务端追踪
