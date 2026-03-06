---
phase: 3
plan: 1
wave: 1
title: "REQ-22/23 权限确认流 + TODO 补全"
---

# Plan 3.1: 权限确认流 + 重连逻辑

## 目标
实现 Skill Server 中缺失的权限确认流（REQ-22/23）和补全 GatewayRelayService 中 2 个 TODO。

## 任务

<task name="REQ-22: Permission Reply REST API">
**文件:** `SkillMessageController.java`

新增 REST endpoint:
```java
POST /api/skill/sessions/{sessionId}/permissions/{permId}
Body: { "approved": true/false }
```

逻辑：
1. 验证 session 存在且未关闭
2. 构建 `permission_reply` invoke 消息
3. 通过 `GatewayRelayService.sendInvokeToGateway()` 下发

<verify>
mvn compile -pl skill-server
</verify>
</task>

<task name="REQ-23: Permission Request 上行处理">
**文件:** `GatewayRelayService.java`

1. 在 `handleGatewayMessage()` 的 switch 中新增 `permission_request` case
2. 实现 `handlePermissionRequest(sessionId, node)` 方法 — 通过 `SkillStreamHandler.pushToSession()` 推送 `permission_request` 类型消息
3. 消息内容包含 `permissionId`, `command`, `workingDirectory` 等字段

<verify>
mvn compile -pl skill-server
</verify>
</task>

<task name="TODO-1: 重连逻辑">
**文件:** `GatewayRelayService.java` (L108)

实现 `reconnect` case: 关闭当前 session 订阅 → 重新订阅 → 重置 SequenceTracker

<verify>
mvn compile -pl skill-server
</verify>
</task>

<task name="TODO-2: 恢复请求逻辑">
**文件:** `GatewayRelayService.java` (L112)

实现 `request_recovery` case: 发送 recovery 请求给 Gateway 要求重发缺失消息

<verify>
mvn compile -pl skill-server
</verify>
</task>
