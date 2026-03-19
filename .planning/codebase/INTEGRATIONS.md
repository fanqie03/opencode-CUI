# 外部集成地图

## 概览

系统通过多个内部与外部边界协作，把消息从本地 OpenCode 运行时传到网关、Skill 后端以及用户界面。

## 内部服务边界

### AI Gateway

关键文件：
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`

职责：
- 接收 Agent WebSocket 连接
- 接收 `skill-server` 的 WebSocket 连接
- 使用 AK/SK 对 Agent 鉴权
- 在在线组件之间路由 invoke 与 event 消息

### Skill Server

关键文件：
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

职责：
- 维护到 `ai-gateway` 的出站 WebSocket 连接
- 向前端提供会话与消息 REST API
- 向浏览器流式推送事件
- 持久化与重建会话/消息历史

## 前端集成

Miniapp 同时消费 REST 和 WebSocket API。

关键文件：
- `skill-miniapp/src/utils/api.ts`
- `skill-miniapp/src/hooks/useSkillSession.ts`
- `skill-miniapp/src/hooks/useSkillStream.ts`
- `skill-miniapp/src/hooks/useAgentSelector.ts`

可见 API 能力：
- 查询在线 Agent
- 创建 / 列表 / 获取 / 关闭会话
- 发送聊天消息
- 拉取消息历史
- 回复 permission 请求
- 将选中文本发送到 IM

## OpenCode 插件集成

插件包负责连接本地 OpenCode 与远端网关。

关键文件：
- `plugins/message-bridge/src/index.ts`
- `plugins/message-bridge/src/runtime/BridgeRuntime.ts`
- `plugins/message-bridge/src/connection/GatewayConnection.ts`
- `plugins/message-bridge/src/action/ActionRouter.ts`
- `plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts`
- `plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts`

根据 `plugins/message-bridge/README.md`，当前协议包括：
- downstream message types：`invoke`、`status_query`
- invoke actions：`chat`、`create_session`、`close_session`、`permission_reply`、`abort_session`、`question_reply`
- upstream events：覆盖 session、message、permission、question 等状态更新

## 持久化与共享基础设施

### MySQL

Migration 目录：
- `ai-gateway/src/main/resources/db/migration/`
- `skill-server/src/main/resources/db/migration/`

Mapper 文件：
- `ai-gateway/src/main/resources/mapper/*.xml`
- `skill-server/src/main/resources/mapper/*.xml`

### Redis

配置文件：
- `ai-gateway/src/main/java/com/opencode/cui/gateway/config/RedisConfig.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/RedisConfig.java`

使用场景：
- pub/sub 中继
- 心跳与路由归属状态
- 网关实例与 Skill 路由之间的临时协调

## 认证与安全边界

- AK/SK 签名校验位于 `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java`
- Skill 到 Gateway 的内部 token 通过两个服务的 `application.yml` 配置
- 插件端鉴权负载生成位于 `plugins/message-bridge/src/connection/AkSkAuth.ts`

## IM 集成

Skill Server 支持将内容转发到 IM 后端。

关键文件：
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java`
- `skill-server/src/main/resources/application.yml`
- `skill-miniapp/src/hooks/useSendToIm.ts`

配置项：
- `skill.im.api-url`

## 运维与日志

- 两个后端都通过 `application.yml` 配置文件日志输出
- 插件运行时优先走 `client.app.log()`，失败时退回本地 debug 输出，说明见 `plugins/message-bridge/README.md`
- 插件脚本目录 `plugins/message-bridge/scripts/` 提供本地堆栈启动、日志抓取、E2E 调试等工具

## 集成风险

- 配置文件中存在默认凭据与默认 token
- 插件、网关、Skill Server、前端之间存在多层协议转换
- 任何 transport shape 变化，通常都需要至少三个模块同步修改
