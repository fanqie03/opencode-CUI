# 代码约定地图

## 总体风格

仓库整体采用常见、可预测的命名与分层方式，没有引入很强的自定义框架约束。

当前可见约定：
- Java package 统一采用 `com.opencode.cui.<module>`
- Spring stereotype 使用较一致：`@Service`、`@Component`、`@RestController`、`@Configuration`
- MyBatis XML mapper 统一放在 `src/main/resources/mapper/`
- React 组件文件名使用 PascalCase，hooks / utility 使用 camelCase
- TypeScript 插件更偏接口驱动与显式类型

## Java 后端约定

在 `ai-gateway/` 与 `skill-server/` 中可见：
- 控制器类名以 `Controller` 结尾
- 服务类名以 `Service` 结尾
- 仓储接口以 `Repository` 结尾
- WebSocket 传输相关类集中在 `ws/`
- 配置属性类以 `Properties` 结尾
- `model/` 下同时承载实体、命令对象、视图对象与响应对象

错误处理相关约定：
- `skill-server/src/main/java/com/opencode/cui/skill/config/GlobalExceptionHandler.java` 做全局异常兜底
- 协议相关辅助类独立存在，如 `ProtocolException.java`、`ProtocolUtils.java`
- 插件侧有专门的错误映射层，位于 `plugins/message-bridge/src/error/` 与 `plugins/message-bridge/src/utils/error.ts`

## 前端约定

`skill-miniapp/src/` 的主要模式：
- 自定义 hooks 封装远程数据、socket 状态与业务副作用
- 协议归一化逻辑与渲染组件解耦
- UI 组件主要接收 typed props，不直接承担数据拉取
- API 调用统一收敛在 `skill-miniapp/src/utils/api.ts`

例子：
- `useSkillSession.ts` 管理 session CRUD 与当前会话状态
- `useSkillStream.ts` 管理 socket 生命周期、重连、resume、消息合并
- `ConversationView.tsx`、`MessageBubble.tsx` 主要负责展示 message 结构

## 插件约定

插件模块的设计规则最清晰。

当前可见规范：
- 先定义 contracts，再做 protocol，最后才是 runtime / action
- inbound 与 outbound 的消息处理分为 extractor 与 normalizer
- action 类按支持的 command 命名
- runtime 更倾向 typed adapter，而不是直接信任外部对象形状
- 日志与错误在输出前会做归一化与脱敏整理

可参考文件：
- `plugins/message-bridge/src/action/ChatAction.ts`
- `plugins/message-bridge/src/action/ActionRegistry.ts`
- `plugins/message-bridge/src/runtime/SdkAdapter.ts`
- `plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts`

## 测试命名约定

- Java 测试使用 JUnit `@Test`
- 插件测试通过 `bun test`
- 顶层 Python 测试使用 `pytest` 与 `pytest-asyncio`
- 测试名中大量使用场景编号，如 `test_u45_...`、`test_int06_...`、`test_perf03_...`

这说明项目大概率在按某套需求 / 用例编号体系维护可追踪性。

## 命名与 API 形状

- `Session`、`Message`、`Part` 是跨模块重复出现的稳定核心概念
- transport payload 通常有显式类型或模型承载
- 状态值倾向使用小集合枚举，如 `online`、`offline`、`unknown`

## 约定漂移与异味

- 多个文件出现了乱码或 mojibake，说明编码规范没有被稳定执行
- 前端代码中 `useCallback` 使用频率较高，但仓库里没看到统一的 React 性能约定文档
- 根目录下没有明显统一的 lint / format 配置，子项目之间的约束强度可能不同
