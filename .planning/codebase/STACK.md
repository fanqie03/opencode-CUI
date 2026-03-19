# 技术栈地图

## 概览

这个仓库是一个围绕 OpenCode 聊天与工具编排构建的多模块系统。
根目录 `package.json` 主要作为工作区壳层，当前引用本地插件包 `@opencode-cui/message-bridge`。

主要子系统：
- `ai-gateway/`：负责 Agent 注册、AK/SK 鉴权、消息路由与中继的 Spring Boot 网关
- `skill-server/`：负责会话管理、消息持久化、WebSocket 流式推送的 Spring Boot 服务
- `skill-miniapp/`：面向聊天与会话管理的 React + Vite 前端
- `plugins/message-bridge/`：连接本地 OpenCode 与远端 `ai-gateway` 的 TypeScript 插件
- `tests/`：覆盖集成、安全、HA、E2E 的 Python 测试集

## 语言与运行时

- Java 21，见 `ai-gateway/pom.xml` 与 `skill-server/pom.xml`
- TypeScript，见 `skill-miniapp/src/` 与 `plugins/message-bridge/src/`
- React 18，见 `skill-miniapp/package.json`
- Node.js 工具链，见 `skill-miniapp/package.json`
- Bun 作为插件包管理与测试运行时，见 `plugins/message-bridge/package.json`
- Python 测试工具链，见 `tests/requirements.txt`

## 后端框架

`ai-gateway/` 与 `skill-server/` 都使用：
- `spring-boot-starter-web`
- `spring-boot-starter-websocket`
- `mybatis-spring-boot-starter`
- `spring-boot-starter-data-redis`
- MySQL 驱动 `mysql-connector-j`
- Lombok
- `spring-boot-starter-test`

额外依赖：
- `org.java-websocket:Java-WebSocket`，用于 `skill-server` 里的网关客户端
- `com.github.ben-manes.caffeine:caffeine`，用于 `skill-server` 的有界缓存

## 前端与插件栈

`skill-miniapp/` 使用：
- `react`
- `react-dom`
- `react-markdown`
- `remark-gfm`
- `shiki`
- `vite`
- `typescript`

`plugins/message-bridge/` 使用：
- `@opencode-ai/plugin`
- `@opencode-ai/sdk`
- `jsonc-parser`
- 基于 Node 的 TypeScript 构建脚本，位于 `plugins/message-bridge/scripts/`

## 入口点

- `ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java`
- `skill-server/src/main/java/com/opencode/cui/skill/SkillServerApplication.java`
- `skill-miniapp/src/main.tsx`
- `plugins/message-bridge/src/index.ts`

## 运行配置来源

- `ai-gateway/src/main/resources/application.yml`
- `skill-server/src/main/resources/application.yml`
- `plugins/message-bridge/src/config/default-config.ts`
- `plugins/message-bridge/README.md` 中说明的 `.opencode/message-bridge.jsonc` 等配置位置

## 数据与消息基础设施

- MySQL：持久化实体、会话与消息
- Redis：路由、pub/sub、心跳状态与临时协调
- WebSocket：Agent、Gateway、Skill Server、前端之间的双向流
- HTTP REST：会话 CRUD、消息历史、在线 Agent 查询、IM 转发

## 构建与验证工具

- Maven：Java 服务构建
- Vite + TypeScript Compiler：前端构建
- Bun：`plugins/message-bridge/` 的测试与构建
- Pytest + pytest-asyncio：系统级验证

## 当前技术栈备注

- 仓库同时混用了 `npm`、`bun`、`mvn`、`pytest`，本地环境是多运行时组合，不是单一工具链。
- 两个后端服务在 Spring / MyBatis / Redis / MySQL 选型上高度一致，有利于维护与迁移。
- `skill-miniapp/src/app.tsx` 中存在疑似乱码 UI 文本，说明文件编码一致性值得单独关注。
