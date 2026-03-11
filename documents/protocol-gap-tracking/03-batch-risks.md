# 协议拉齐实施风险记录

> 日期：2026-03-11
> 范围：批次 A / 批次 B 实施过程中的已知风险

## 1. Layer1 会话归属校验依赖在线 Agent 列表

- 背景：当前 `skill-server` 在执行 `welinkSessionId -> session -> ak -> gateway` 的归属校验时，gateway 侧可直接复用的数据源是“在线 agent 列表”接口。
- 当前实现：`GatewayApiClient.isAkOwnedByUser()` 通过 `getOnlineAgentsByUserId(userId)` 判断 `ak` 是否属于当前用户。
- 风险：如果某个 session 绑定的 `ak` 当前离线，即使它历史上确实属于该 `userId`，远端归属校验也可能失败，导致访问被拒绝。
- 当前处理：风险先保留，不回退现有安全校验链。
- 后续建议：补一条不依赖在线态的 `ak ↔ userId` 归属查询能力，再替换当前校验数据源。

