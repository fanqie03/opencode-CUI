# 风险与关注点

## 高优先级问题

### 配置文件中存在默认敏感值

以下配置里出现了敏感感较强的默认值：
- `ai-gateway/src/main/resources/application.yml` 中的数据库默认密码
- `skill-server/src/main/resources/application.yml` 中的数据库默认密码
- 两个后端配置中的内部通信 token 默认值

即使这些只是本地开发值，也会提高误用与泄漏风险。

### 编码与乱码问题

扫描中能明显看到乱码：
- `skill-miniapp/src/app.tsx` 中的 UI 文案
- `tests/test_e2e.py` 中的注释与 docstring
- `skill-server/pom.xml` 中部分注释

这会直接影响可维护性、用户体验，以及多人协作时的文本一致性。

### 协议耦合度高

协议与消息形状分散在多个模块：
- `plugins/message-bridge/src/contracts/`
- `plugins/message-bridge/src/protocol/`
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`
- `skill-miniapp/src/hooks/useSkillStream.ts`

这意味着一次协议变更通常需要跨模块联动修改和验证。

## 中优先级问题

### 运维复杂度高

项目横跨：
- Java / Maven
- TypeScript / npm
- TypeScript / Bun
- Python / pytest
- MySQL
- Redis
- WebSocket 实时依赖

这增强了模块独立性，但也提高了本地搭建、CI/CD 与故障排查成本。

### 前端状态复杂度高

`skill-miniapp/src/hooks/useSkillStream.ts` 是一个大体量、高状态密度的 hook，承担了重连、resume、归一化、buffer、merge 等职责。
这类文件通常是功能核心，同时也是回归风险最高的热点。

### 前端入口可能重叠

`skill-miniapp/src/app.tsx` 与 `skill-miniapp/src/pages/SkillMain.tsx` 看起来都在装配聊天主流程。
这未必是错误，但值得确认是否存在并行 UI 路径漂移。

### 缺少统一任务编排

当前没有明显看到统一的 monorepo task runner 或根级 CI 配置。
团队成员可能需要额外的隐性知识才能完整构建与验证整个系统。

## 低优先级问题

### 文档分布不均衡

`plugins/message-bridge/docs/` 文档相对完整，而两个 Java 服务内嵌架构文档较少。
知识沉淀在不同子项目之间并不均衡。

### 跨服务 Schema 漂移风险

`ai-gateway/` 与 `skill-server/` 各自维护 migration 是合理的，但也要求部署与兼容升级更谨慎。

## 需要重点盯防的脆弱区域

- WebSocket 握手与重连
- Redis 路由归属与 TTL 逻辑
- event 顺序与 message part sequence
- session resume / snapshot rebuild
- 鉴权时间窗与 nonce replay 防护

## 建议后续优先调查

- 把默认敏感值替换成更安全的 placeholder 或强制环境变量
- 统一 UTF-8 编码并修复已出现的乱码文件
- 补一份跨 gateway / skill-server / plugin / miniapp 的协议归属文档
- 确认前端双入口是否有意设计，如无必要可收敛
