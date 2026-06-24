# 分支变更总结（vs main）

分支：`feature--US202606022503569` ｜ 40+ files

## 工作提交

| Commit | 说明 |
|---|---|
| `fd5cbfc3` feat | 未读消息小红点提醒与已读多端同步（初始实现） |
| `077d36c` refactor | getUnread 返回 List\<UnreadSessionItem\> 替代 Map |
| `7053180e` fix | Composite.push 未匹配时回退到配置默认 mode |
| `65d24e3f` feat | POST /unread sessionIds 增加 maxQuerySessionIds 上限 |
| `43a89509` fix | markRead Lua readSeq>maxSeq 时返回 -1，抛 400 |
| `8d93f142` feat | ToolErrorEvent，tool_error 时也触发未读计数 |
| `82fc4f90` docs | 同步 summary.md 包含后续 refactor/fix/feat 提交 |
| `043ab62e` docs | 同步 design.md——Lua markRead 三态/syncMode 按场景/ToolErrorEvent |
| `a109c222` docs | 同步 design_inner.md |
| `75a6fe99` refactor | GatewayMessageRouter ApplicationEventPublisher 改为 @Autowired 字段注入 |
| `7c048f99` style | GatewayMessageRouter 成员变量排序——final 在前 @Autowired 在后，变量间空行 |
| `a4e508fa` refactor | ToolDoneEvent/ToolErrorEvent 移除 traceId 字段 |
| `3ea1b786` docs | 同步 design.md——ToolDoneEvent 移除 traceId 字段 |

## 后端变更（skill-server）

### Redis Lua 脚本
- `unread_update_max_seq.lua` — 原子 HSET + EXPIRE，field 不存在或 newSeq > current 时 return 1
- `unread_mark_read.lua` — 三态：1=已清除, 0=仍有未读, -1=readSeq>maxSeq（非法参数）

### 服务层
- `UnreadRedisService.java` — 封装 Lua + HGETALL/HMGET/HDEL，getUnread 返回 `List<UnreadSessionItem>`
- `UnreadManageListener.java` — 4 种事件：`@Async onToolDone` / `onToolError`（domain 白名单 + DB maxSeq → Lua → push SESSION_UNREAD）、`onReadReported`（push SESSION_READ）、`onSessionDeleted`（HDEL）
- `SkillSessionService.java` — `getUnreadSessions()` 返回 List\<UnreadSessionItem\>、`reportRead()` 含 readSeq>maxSeq 400 校验
- `GatewayMessageRouter.java` — `handleToolDone` + `handleToolError` 末尾发布事件

### 事件模型
- `ToolDoneEvent` / `ToolErrorEvent` / `ReadReportedEvent` / `SessionDeletedEvent` record
- `UnreadSyncKeys` — syncContent 常量

### 控制器
- `POST /unread`（sessionIds 可选，maxQuerySessionIds=50 上限）+ `POST /{id}/read`

### 配置
- `UnreadProperties` — `skill.unread.*`：syncMode（按场景配置, 默认 ws）、domain whitelist、maxQuerySessionIds、hash TTL、async 线程池
- `UnreadAsyncConfig` / `SyncImRetryConfig`
- `application.yml` — `skill.unread.*` + `skill.sync.im.retry.*`
- `CompositeMultiDeviceSyncService` — push 未匹配时回退到 `MultiSyncProperties.mode` 默认模式

### 模型变更
- `SyncType` — 新增 `SESSION_READ`
- `UnreadRequest/Response/SessionItem` / `ReadReportRequest/Response` DTO

## 前端变更（skill-miniapp）

- `useReadTracking.ts` — readMessageSeq 追踪 + 500ms debounce + 流式保护
- `useUnreadBadge.ts` — POST /unread 拉取 + WS 推送处理 + 单调校验
- `useSkillStream.ts` / `SessionSidebar.tsx` / `SkillMain.tsx` / `protocol/types.ts` / `api.ts` / `index.css`

## 验证

- 后端：52 unit tests, 0 failures
- 前端：TS typecheck + Vite build pass
- detect_changes：89 symbols, 27 processes（全部在预期范围）
