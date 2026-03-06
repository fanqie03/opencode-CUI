---
phase: 3
plan: 3
wave: 3
title: "代码审查与重构"
---

# Plan 3.3: 代码审查与重构

## 目标
审查 Skill Server 所有现有代码，修复潜在问题，优化代码质量。

## 任务

<task name="代码质量审查">
检查项：
1. 异常处理完整性 — 是否所有数据库/网络操作都有 try-catch
2. null safety — @Nullable/@NonNull 合理标注
3. 日志一致性 — 所有 handler 都有 trace + error 日志
4. 资源泄漏 — WebSocket session 清理是否完整
5. 线程安全 — ConcurrentHashMap 使用是否正确
6. Jackson 配置 — 是否有 @JsonIgnoreProperties(ignoreUnknown = true)

修复所有发现的问题。

<verify>
mvn compile -pl skill-server
mvn test -pl skill-server
</verify>
</task>

<task name="WebSocket Config 验证">
确认 `WebSocketConfig` 或同等配置中：
- `/ws/internal/gateway` 端点正确注册
- `/ws/skill/stream/{sessionId}` 端点正确注册
- WebSocket 允许的 origin 配置

<verify>
找到并审查 WebSocket 配置类
</verify>
</task>
