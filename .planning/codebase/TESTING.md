# 测试地图

## 概览

当前测试体系分散在 Java 单元测试、TypeScript/Bun 插件测试，以及 Python 跨服务系统测试中。
覆盖面比较广，尤其重视协议与集成链路。

## Java 服务测试

`ai-gateway/` 已覆盖：
- controller，位于 `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/`
- service，位于 `ai-gateway/src/test/java/com/opencode/cui/gateway/service/`
- WebSocket handler，位于 `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/`
- model，位于 `ai-gateway/src/test/java/com/opencode/cui/gateway/model/`

`skill-server/` 已覆盖：
- controller，位于 `skill-server/src/test/java/com/opencode/cui/skill/controller/`
- service，位于 `skill-server/src/test/java/com/opencode/cui/skill/service/`
- WebSocket 相关类，位于 `skill-server/src/test/java/com/opencode/cui/skill/ws/`

代表文件：
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/AkSkAuthServiceTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/SkillStreamHandlerTest.java`

## 插件测试

插件模块有比较完整的 unit / integration / e2e 分层：
- `plugins/message-bridge/tests/unit/`
- `plugins/message-bridge/tests/integration/`
- `plugins/message-bridge/tests/e2e/`

从文件名看，重点覆盖：
- 配置校验
- 连接生命周期
- action 路由
- 协议归一化
- 上游事件提取
- 插件分发与加载验证

常用命令见 `plugins/message-bridge/package.json`：
- `bun run test`
- `bun run test:unit`
- `bun run test:integration`
- `bun run test:e2e`
- `bun run test:coverage`

## 顶层 Python 测试

根目录 `tests/` 用于验证跨模块行为。

覆盖主题包括：
- gateway 鉴权
- agent 注册与中继
- skill session CRUD
- message CRUD
- stream 行为
- 全链路集成流程
- 端到端聊天流程
- 安全
- 性能
- 高可用

代表文件：
- `tests/test_gateway_auth.py`
- `tests/test_gateway_agent.py`
- `tests/test_gateway_ws_skill.py`
- `tests/test_integration.py`
- `tests/test_e2e.py`
- `tests/test_security.py`
- `tests/test_performance.py`
- `tests/test_ha.py`

## 测试基础设施

- pytest fixtures 与环境准备位于 `tests/conftest.py`
- HTTP / WebSocket 共享工具位于 `tests/utils/`
- 异步场景大量使用 `pytest.mark.asyncio`
- 部分测试带有 `requires_agent`、`security`、`performance`、`ha`、`slow` 标记

## 优势

- 从单元到分布式集成，测试层次比较完整
- 协议密集区域测试较多
- 用例编号命名提高了需求追踪性
- 安全与韧性是被明确当成一等公民来测试的

## 可能的缺口

- 没有明显看到根级统一 CI 编排文件
- `skill-miniapp/` 下没有直接看到前端组件测试
- E2E 测试明显依赖环境，需要服务、数据库、Redis 和 Agent 同时在线
- 全仓验证很可能依赖手工准备多运行时环境
