# 架构地图

## 系统形态

这不是单体应用，而是一个以消息与流式事件为中心的分布式系统。
整体链路主要分为四层：

1. `plugins/message-bridge/`：本地 OpenCode Host 插件
2. `ai-gateway/`：消息网关
3. `skill-server/`：会话与持久化后端
4. `skill-miniapp/`：聊天 UI

## 高层数据流

一条典型路径如下：
- 用户在 `skill-miniapp/src/app.tsx` 中选择 Agent 并发起会话
- 前端通过 `skill-miniapp/src/utils/api.ts` 调用 REST API
- `skill-server` 通过 controller / service / repository 创建或读取会话
- `skill-server` 使用 `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java` 将 invoke 命令转发给 `ai-gateway`
- `ai-gateway` 使用 `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java` 将消息路由给在线 Agent
- 本地 OpenCode 插件通过 `plugins/message-bridge/src/runtime/BridgeRuntime.ts` 执行动作并产生上游事件
- 事件再经过 gateway 与 skill server 返回，并通过 `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java` 推送给前端

## 后端架构模式

两个 Java 服务都采用典型的分层 Spring Boot 架构：
- controller 层：HTTP API
- ws 层：WebSocket 入口或客户端
- service 层：业务逻辑
- repository 层：MyBatis 接口
- model / config：DTO、实体、配置类

对应证据：
- controller 位于 `ai-gateway/.../controller/` 与 `skill-server/.../controller/`
- service 位于各自模块的 `service/`
- repository 位于各自模块的 `repository/`
- XML mapper 位于 `src/main/resources/mapper/`

## Gateway 角色

`ai-gateway/` 是系统消息中枢。

核心职责：
- 对 Agent 做 AK/SK 鉴权
- 跟踪在线 Agent 与设备绑定
- 维护中继状态与归属
- 在 Skill Server 与 Agent 之间路由消息
- 提供 Agent 查询 API

关键文件：
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`

## Skill Server 角色

`skill-server/` 是会话域后端。

核心职责：
- 管理 skill session 与 message
- 持久化 message part，并支持 snapshot / rebuild
- 将 OpenCode event 翻译成前端可消费的 stream message
- 维护浏览器 WebSocket 订阅
- 可选地向 IM 系统发送消息

关键文件：
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SnapshotService.java`

## 前端模式

`skill-miniapp/` 使用以 hooks 为核心的 React 组织方式：
- `src/utils/api.ts`：API 封装
- `src/hooks/`：领域状态与副作用
- `src/protocol/`：流式消息解析、历史归一化、tool 渲染
- `src/components/`：展示组件
- `src/pages/`：页面装配

根组件主要通过 hooks 组合能力，没有引入全局状态库。

## 插件模式

`plugins/message-bridge/` 明确按边界与职责分层：
- `contracts/`：外部契约定义
- `protocol/`：消息归一化与提取
- `connection/`：WebSocket 与鉴权传输
- `action/`：下行动作执行
- `runtime/`：运行时编排
- `error/`、`utils/`：通用支持

这一点也在 `plugins/message-bridge/README.md` 中被直接描述为 layered boundary architecture。

## 调度、缓存与状态

- `ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java` 启用了调度
- `skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java` 启用了调度
- `skill-server` 使用 `TranslatorSessionCache.java`、`ActiveMessageTracker.java` 等组件维护进程内状态
- Redis 进一步承担跨进程状态协调

## 架构优势

- 子系统边界清晰
- 传输层、持久化层、UI 层分工明确
- 协议相关测试覆盖明显较强
- 两个后端服务结构相似，降低认知负担

## 架构脆弱点

- 多模块重复维护协议概念，变更成本高
- 没有统一的 monorepo 编排器来串起全部运行时
- 一次跨服务改动通常需要严格联动验证
