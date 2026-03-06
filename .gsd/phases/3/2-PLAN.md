---
phase: 3
plan: 2
wave: 2
title: "全面单元测试覆盖"
---

# Plan 3.2: 全面单元测试

## 目标
为 Skill Server 所有核心组件编写全面单元测试，覆盖 service + controller + handler。

## 任务

<task name="SequenceTrackerTest">
**文件:** `src/test/java/com/yourapp/skill/service/SequenceTrackerTest.java` [NEW]

测试项:
- 正常序列 → continue
- 重复/乱序 → continue
- 小间隙 (1-3) → continue
- 中间隙 (4-10) → request_recovery
- 大间隙 (>10) → reconnect
- resetSession 重置
- null sequenceNumber 处理

<verify>
mvn test -pl skill-server -Dtest=SequenceTrackerTest
</verify>
</task>

<task name="GatewayRelayServiceTest">
**文件:** `src/test/java/com/yourapp/skill/service/GatewayRelayServiceTest.java` [NEW]

测试项 (Mockito):
- handleGatewayMessage 路由各类型 (tool_event, tool_done, tool_error, agent_online, agent_offline, session_created, permission_request)
- sendInvokeToGateway 序列化和 Redis 发布
- handleRedisMessage 序列号验证 → 路由
- unknown type 处理

<verify>
mvn test -pl skill-server -Dtest=GatewayRelayServiceTest
</verify>
</task>

<task name="SkillSessionServiceTest">
**文件:** `src/test/java/com/yourapp/skill/service/SkillSessionServiceTest.java` [NEW]

测试项 (Mockito):
- createSession 正确调用 repository.insert
- listSessions 分页 + 状态过滤
- getSession 存在/不存在
- closeSession 状态更新
- touchSession timestamp 更新
- updateToolSessionId 更新
- findByAgentId 查询
- cleanupIdleSessions 超时标记

<verify>
mvn test -pl skill-server -Dtest=SkillSessionServiceTest
</verify>
</task>

<task name="SkillMessageServiceTest">
**文件:** `src/test/java/com/yourapp/skill/service/SkillMessageServiceTest.java` [NEW]

测试项 (Mockito):
- saveMessage seq 自增
- saveUserMessage / saveAssistantMessage / saveToolMessage / saveSystemMessage
- getMessageHistory 分页
- getMessageCount

<verify>
mvn test -pl skill-server -Dtest=SkillMessageServiceTest
</verify>
</task>

<task name="SkillStreamHandlerTest">
**文件:** `src/test/java/com/yourapp/skill/ws/SkillStreamHandlerTest.java` [NEW]

测试项:
- afterConnectionEstablished 正常/缺失 sessionId
- pushToSession 单客户端/多客户端/无订阅者
- afterConnectionClosed 清理订阅
- seq 计数器递增
- buildMessage JSON 结构

<verify>
mvn test -pl skill-server -Dtest=SkillStreamHandlerTest
</verify>
</task>

<task name="ControllerTest">
**文件:** 
- `src/test/java/com/yourapp/skill/controller/SkillSessionControllerTest.java` [NEW]
- `src/test/java/com/yourapp/skill/controller/SkillMessageControllerTest.java` [NEW]

测试项 (MockMvc):
- Session CRUD endpoints (201/200/404)
- Message send + history endpoints
- send-to-im endpoint
- permission reply endpoint (新增的 REQ-22)
- 参数校验 (400 Bad Request)

<verify>
mvn test -pl skill-server
</verify>
</task>
