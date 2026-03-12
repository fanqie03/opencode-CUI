# Milestones

## v1.1 Connection-Aligned Consumption Fix

**Shipped:** 2026-03-12  
**Phases:** 1  
**Plans:** 3

### Accomplishments

1. 在 `gateway / skill-server` 链路补齐了可信 `userId` 上下文，并在 agent 回流时完成一致性补全。
2. 将 `skill-server` 的实时广播从 `session:{sessionId}` 切换为 `user-stream:{userId}`。
3. 建立了用户级流连接的首订阅 / 末退订生命周期。
4. 清理了旧的 session 级实时广播 API，使其退出主消费链路。
5. 在真实 UAT 中定位并修复了 `SkillStreamHandler` 双重清理导致的误退订问题。
6. 完成了 miniapp 实时回流复测，确认问题已解决。

## v1.2 Gateway Multi-Service Isolation

**Shipped:** 2026-03-12  
**Phases:** 4  
**Plans:** 12

### Accomplishments

1. 将来源服务提升为 `gateway` 的显式路由维度，建立 `source` 识别、握手绑定和消息一致性校验。
2. 将 owner 注册与回流选择升级为按 `source` 分域执行，消除多上游服务场景下的跨服务错投。
3. 在 `skill-server` 与 `ai-gateway` 中统一 Snowflake ID 基础设施，移除对数据库自增主键的依赖。
4. 将 `welinkSessionId` 在 REST、WebSocket、relay payload 与前端消费链路上统一收口为数字型。
5. 为 Phase 7 回填正式 verification / summary 工件，补齐 `ROUT-01`、`ROUT-02`、`SAFE-01` 的审计证据链。
6. 完成 v1.2 milestone rerun audit，确认 6/6 requirements satisfied，仅保留非阻塞技术债。
