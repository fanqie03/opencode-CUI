# PRD: Fix PR #46 Review Issues

> 基于 `.trellis/tasks/05-20 review分析/review.md` 发现的问题进行修复。

## P0

### #1 URL 路径不匹配
- **问题**：`GatewayApiClient.getAvailability()` 请求 `/internal/agent/availability`，但 AgentController 有类级 `@RequestMapping("/api/gateway")`，实际监听 `/api/gateway/internal/agent/availability`
- **修复**：`GatewayApiClient.java:155` 改为 `gatewayBaseUrl + "/api/gateway/internal/agent/availability?ak=" + ak`
- **AC**：skill-server 请求 gateway 返回 200（非 404），差异化文案生效

## P1

### #2 反序列化失败返回 null → NPE
- **问题**：`AssistantAvailabilityService.resolve()` 中 `deserialize()` 失败返回 null 时直接 `return null`，调用方 `r.online()` NPE
- **修复**：deserialize 返回 null 时走 cache miss 路径（继续 queryAndCompute），并 evict 坏 key
- **AC**：Redis 中有非法 JSON 时不抛 NPE，正常走 gateway 查询路径

### #3 Legacy 端点无鉴权
- **问题**：`GET /api/gateway/agents/{id}/status` 和 `POST /api/gateway/agents/{id}/invoke` 无 Authorization 校验
- **修复**：删除两个旧版端点（已被新版替代，且无调用方）
- **AC**：两个端点不再存在

## P2

### #4 心跳新鲜度
- **问题**：`existsOnlineActiveByAkId` SQL 只判 status='ONLINE'，不查 last_seen_at 时间窗
- **修复**：在 repository 方法 javadoc 注明依赖 `markStaleAgentsOffline` 定时任务
- **AC**：隐式依赖显式化

### #5 V14 seed 索引依赖
- **问题**：`ON DUPLICATE KEY UPDATE` 依赖 `uk_type_key` 唯一索引但未声明
- **修复**：在 V14 文件头部加注释说明依赖 V10 的 `uk_type_key`
- **AC**：后续维护者不会误删索引

### #6 blank ak 语义
- **问题**：`resolve(ak)` 在 ak 为 null/blank 时返回 NOT_CONFIGURED，语义错误
- **修复**：改为 `FALLBACK_ERROR` + `log.error("[BUG] ...")`
- **AC**：系统错误不显示误导文案

### #7 PR 标题
- **问题**：当前 PR 标题 `Person p30056214 test` 不符合规范
- **修复**：合前改标题为 `feat(skill-server,gateway): 差异化助理离线文案（未配置 / 已知类型 / 未知类型）`

### #8 夹带文件
- **问题**：PR 包含 `00-join-feison` 和 `workspace/feison/` 等他人物品
- **修复**：`git checkout main -- .trellis/tasks/00-join-feison/ .trellis/workspace/feison/`
