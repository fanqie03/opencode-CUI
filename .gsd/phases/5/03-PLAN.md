---
phase: 5
plan: 3
wave: 2
---

# Plan 5.3: Gateway status_query + 全链路 ID 规范化

## Objective
1. Gateway 实现主动向 PC Agent 发送 `status_query` 消息（用于获取 OpenCode 运行状态）
2. 全链路 ID 命名规范化：前端/WS 对外暴露的 sessionId 字段统一为 `welinkSessionId`

## Context
- [AgentWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java) — 已处理 `status_response` 上行
- [EventRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java) — Agent 连接管理
- [StreamMessage.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java) — WS 推送模型

## Tasks

<task type="auto">
  <name>Gateway 实现 status_query 主动发送</name>
  <files>
    ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
    ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java
  </files>
  <action>
    在 `EventRelayService` 中新增 `sendStatusQuery(String ak)` 方法：
    1. 通过 ak 找到 agent connection（WS session）
    2. 发送 `{"type": "status_query"}` JSON 消息
    3. 支持被 Skill Server 或定时任务调用
    
    可选：注册为 @Scheduled 定时任务（每 30s 对所有在线 agent 发送），或暴露为内部 REST 端点供 Skill 按需调用。
    
    注意：PC Agent 端已实现 `handleStatusQuery()`（Phase 2），handleTextMessage 已接受 `status_response` 上行。
  </action>
  <verify>方法存在 + 可编译</verify>
  <done>sendStatusQuery 方法可向指定 ak 的 agent 发送 status_query</done>
</task>

<task type="auto">
  <name>StreamMessage sessionId 字段对外改名为 welinkSessionId</name>
  <files>skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java</files>
  <action>
    1. 审查 `StreamMessage` 中 `sessionId` 字段的使用范围
    2. 如果是 WebSocket 推送字段，用 `@JsonProperty("welinkSessionId")` 注解在序列化时输出为 `welinkSessionId`
    3. Java 代码中字段名保持 `sessionId`（内部一致性），仅 JSON 输出改名
    4. 同时检查 WS 推送的其他模型（如 StreamMessage 的 builder/构造），确保不遗漏
    
    注意：这是 P2 级 — 不影响功能，但让协议文档和代码对齐
  </action>
  <verify>grep "welinkSessionId" StreamMessage.java → 有 @JsonProperty 注解</verify>
  <done>StreamMessage 的 JSON 序列化输出 sessionId 字段名改为 welinkSessionId</done>
</task>

## Success Criteria
- [ ] Gateway 可向 PC Agent 发送 status_query
- [ ] StreamMessage JSON 输出中使用 `welinkSessionId` 字段名
- [ ] Gateway 编译通过 + Skill Server 测试通过
