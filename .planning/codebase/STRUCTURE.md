# 仓库结构地图

## 顶层结构

- `ai-gateway/`：Java 网关服务
- `skill-server/`：Java skill / session 服务
- `skill-miniapp/`：React 前端
- `plugins/message-bridge/`：OpenCode 消息桥插件
- `tests/`：Python 系统级测试
- `.agent/`：本地 agent 相关工作目录

## AI Gateway 结构

主源码根目录：
- `ai-gateway/src/main/java/com/opencode/cui/gateway/`

子目录职责：
- `config/`：Spring 配置与 `ConfigurationProperties`
- `controller/`：REST API
- `model/`：DTO 与持久化模型
- `repository/`：MyBatis mapper 接口
- `service/`：鉴权、注册、中继、Broker、ID 生成等逻辑
- `ws/`：Agent 与 Skill 的 WebSocket 处理器

资源文件：
- `ai-gateway/src/main/resources/application.yml`
- `ai-gateway/src/main/resources/db/migration/`
- `ai-gateway/src/main/resources/mapper/`

测试目录：
- `ai-gateway/src/test/java/com/opencode/cui/gateway/`

## Skill Server 结构

主源码根目录：
- `skill-server/src/main/java/com/opencode/cui/skill/`

子目录职责：
- `config/`：CORS、Redis、RestTemplate、调度、异常处理
- `controller/`：session、message、agent query API
- `model/`：session、message、part、view、command 等模型
- `repository/`：MyBatis mapper 接口
- `service/`：会话逻辑、消息逻辑、中继、翻译、buffer、snapshot
- `ws/`：前端 stream handler 与 gateway client

资源文件：
- `skill-server/src/main/resources/application.yml`
- `skill-server/src/main/resources/db/migration/`
- `skill-server/src/main/resources/mapper/`

测试目录：
- `skill-server/src/test/java/com/opencode/cui/skill/`

## Miniapp 结构

源码根目录：
- `skill-miniapp/src/`

子目录职责：
- `components/`：如 `ConversationView.tsx`、`MessageBubble.tsx` 这类复用 UI 组件
- `hooks/`：如 `useSkillStream.ts`、`useSkillSession.ts` 这类领域 hooks
- `pages/`：如 `SkillMain.tsx`、`SkillMiniBar.tsx`
- `protocol/`：stream 解析、history 归一化、tool 渲染
- `utils/`：API 客户端、dev auth 工具

支撑文件：
- `skill-miniapp/src/app.tsx`
- `skill-miniapp/src/main.tsx`
- `skill-miniapp/src/index.css`
- `skill-miniapp/vite.config.ts`
- `skill-miniapp/tsconfig.json`

## 插件结构

源码根目录：
- `plugins/message-bridge/src/`

主要子目录：
- `action/`
- `config/`
- `connection/`
- `contracts/`
- `error/`
- `event/`
- `protocol/downstream/`
- `protocol/upstream/`
- `runtime/`
- `types/`
- `utils/`

配套目录：
- `plugins/message-bridge/tests/`
- `plugins/message-bridge/scripts/`
- `plugins/message-bridge/docs/`

## 测试结构

顶层 Python 测试：
- `tests/test_gateway_auth.py`
- `tests/test_gateway_agent.py`
- `tests/test_gateway_ws_skill.py`
- `tests/test_skill_session.py`
- `tests/test_skill_message.py`
- `tests/test_skill_stream.py`
- `tests/test_integration.py`
- `tests/test_e2e.py`
- `tests/test_security.py`
- `tests/test_performance.py`
- `tests/test_ha.py`

共享辅助：
- `tests/conftest.py`
- `tests/utils/api_client.py`
- `tests/utils/ws_client.py`
- `tests/utils/auth.py`

## 结构观察

- 命名大多以领域语义组织，整体一致性较好。
- 两个 Java 服务的目录分层非常接近，便于并行理解。
- `plugins/message-bridge/` 是当前文档化程度最高的子模块。
- 前端同时存在 `skill-miniapp/src/app.tsx` 与 `skill-miniapp/src/pages/SkillMain.tsx` 两套聊天装配入口，后续值得确认是否有职责重叠。
