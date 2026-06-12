# 后端开发规范索引
> `ai-gateway` 后端开发导航页。开发前先读本页，再按任务类型进入对应专题文档。
---

## 变更说明

- 保留 7 个 seed 文件，无新增/删除文件。
- 已将模板中的通用示例（`SkillSessionService` 等）替换为 `ai-gateway` 真实实现（`AgentRegistryService`、`SkillRelayService`、`AkSkAuthService` 等）；凡是跨服务协议字段或中继对端的描述，仍通过 `skill-server` 字样保留必要的交叉引用，不视为残留。
- 日志配置以 `ai-gateway/src/main/resources/log4j2-spring.xml` 为准，服务标签为 `[ai-gateway]`。
- 所有代码片段均引自 `ai-gateway/src/main/java/com/opencode/cui/gateway/` 与 `src/test/java/com/opencode/cui/gateway/`，行号为截稿时锚点，若源码漂移请以类名/方法名为准。
- 2026-06-12：新增 `design-and-refactoring.md`，沉淀 Java 软件设计、重构阈值、业务一致性八荣八耻、兜底边界、Wrong vs Correct 与测试要求。来源：任务 06-12-java。

## 技术栈概览

| 项目 | 技术 |
|------|------|
| 框架 | Spring Boot 3.4.6 + Java 21 |
| WebSocket | Spring WebSocket + `TextWebSocketHandler` |
| 数据库 | MySQL + MyBatis XML Mapper |
| 缓存/中继 | Redis（KV、TTL、List、Hash、pub/sub） |
| 序列化 | Jackson + Lombok + Java `record` |
| 日志 | SLF4J + Log4j2 + MDC |
| 定时任务 | `@EnableScheduling` + `@Scheduled` |
| 构建 | Maven |

## 模块职责

| 子域 | 责任 | 主要实现 |
|------|------|----------|
| Agent 接入 | PCAgent 握手鉴权、注册、心跳、断线清理 | `ws/AgentWebSocketHandler.java`, `service/AkSkAuthService.java`, `service/AgentRegistryService.java` |
| Source 接入 | Skill Server 内部 WebSocket 接入、来源实例注册、路由确认 | `ws/SkillWebSocketHandler.java`, `service/SkillRelayService.java`, `service/RedisMessageBroker.java` |
| 消息路由 | Agent ↔ Gateway ↔ Skill Server 双向中继、本地优先投递、远端转发、离线缓冲 | `service/EventRelayService.java`, `service/SkillRelayService.java`, `model/GatewayMessage.java`, `model/RelayMessage.java` |
| 持久化 | Agent 连接档案、AK/SK 凭证、迁移脚本 | `repository/*.java`, `resources/mapper/*.xml`, `resources/db/migration/*.sql` |
| 内部 HTTP API | 在线 Agent 查询、按 AK 调用 Agent、云端 IM 推送入口 | `controller/AgentController.java`, `controller/CloudPushController.java` |
| 可观测性 | REST/WS MDC、统一日志格式、外部调用计时、敏感数据脱敏 | `logging/*.java`, `config/MdcRequestInterceptor.java`, `resources/log4j2-spring.xml` |

## 规范文件

### 开发前必读清单

| 任务类型 | 必读文件 |
|---------|----------|
| 所有后端任务 | `directory-structure.md`, `conventions.md`, `design-and-refactoring.md` |
| REST / WebSocket 行为与错误语义 | `error-handling.md` |
| MyBatis / MySQL / Redis / 路由缓存 | `database-guidelines.md` |
| 日志 / MDC / 观测性 | `logging-guidelines.md` |
| DTO / 模型 / Jackson / `record` | `type-safety.md` |

### 文件列表

| 文件 | 内容 |
|------|------|
| [directory-structure.md](directory-structure.md) | `ai-gateway` 的真实包结构、资源目录、命名约束与新增文件落点 |
| [conventions.md](conventions.md) | 构造器注入、配置注入、WebSocket 注册、服务编排、调度与接口约定 |
| [design-and-refactoring.md](design-and-refactoring.md) | Java 软件设计、重构阈值、业务一致性八荣八耻、兜底边界、Review Checklist |
| [error-handling.md](error-handling.md) | `ai-gateway` 的真实错误处理方式：Controller 直接返回、握手拒绝、服务级异常与降级 |
| [database-guidelines.md](database-guidelines.md) | MyBatis Mapper、迁移脚本、事务边界、Redis key/channel 设计与 TTL 规则 |
| [logging-guidelines.md](logging-guidelines.md) | `[ai-gateway]` 日志格式、MDC key、REST/WS 日志模式、计时与脱敏 |
| [type-safety.md](type-safety.md) | `GatewayMessage`/`RelayMessage` 等核心模型的类型设计与序列化约束 |
