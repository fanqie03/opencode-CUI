<!-- Parent: ../AGENTS.md -->

# Skill Miniapp

## 目的

Skill Miniapp 是运行在 IM 小程序容器中的客户端应用，负责协议适配、富文本渲染、用户交互。作为协议适配层，解析 OpenCode 事件并渲染 UI。

## 关键文件

| 文件 | 描述 |
|------|------|
| `package.json` | 依赖配置：React 18, TypeScript 5, react-markdown, shiki |
| `src/app.tsx` | 小程序入口，管理 Mini Bar 和展开状态 |
| `vite.config.ts` | Vite 构建配置 |
| `tsconfig.json` | TypeScript 编译配置 |

## 子目录

| 目录 | 用途 |
|------|------|
| `src/protocol/` | ★ OpenCode 协议适配层（事件解析、流组装、Tool Use 渲染） |
| `src/pages/` | 页面组件：SkillMiniBar, SkillMain |
| `src/components/` | UI 组件：ConversationView, MessageBubble, CodeBlock, MessageInput 等 |
| `src/hooks/` | React Hooks：useSkillSession, useSkillStream, useSendToIm |
| `src/utils/` | 工具函数：Skill Server REST 客户端 |
| `src/types/` | TypeScript 类型定义 |
| `src/styles/` | CSS 样式文件 |

## 核心模块详解

### Protocol 适配层 (`src/protocol/`)

**职责：** 解析 OpenCode SSE 事件流，转换为 UI 可消费的数据结构。

**关键文件：**

- **`types.ts`** — 类型定义
  - `OpenCodeEvent` — 原始 OpenCode 事件（来自 Skill Server 透明中继）
  - `ParsedEvent` — 规范化事件（UI 消费）
  - `StreamMessage` — Skill Server WebSocket 消息格式
  - `Message` — 对话消息（role: user/assistant/tool/system）
  - `ToolUseInfo` — Tool Use 执行信息

- **`OpenCodeEventParser.ts`** — 事件解析
  - `parse(event)` — 将原始 OpenCode 事件转换为 ParsedEvent
  - 支持事件类型：`message.updated`, `session.completed`, `session.created`, `tool.start`, `tool.result`, `tool.error`
  - 未知事件类型返回 `eventType: 'unknown'`（不抛异常）

- **`StreamAssembler.ts`** — 流消息拼接
  - 累积 `delta` 消息，生成完整文本
  - 支持 `push()`, `getText()`, `complete()`, `reset()` 操作

- **`ToolUseRenderer.ts`** — Tool Use 结果渲染
  - `startTool(parsed)` — 创建 ToolUseInfo（状态：running）
  - `completeTool(parsed, existing)` — 更新为完成状态（result/error）
  - `renderToolUse(info)` — 生成 Markdown 格式的 Tool Use 消息

### 页面组件 (`src/pages/`)

- **`SkillMiniBar.tsx`** — 1 行状态指示器
  - 显示处理状态（processing/completed/error/offline）
  - 展开按钮触发 SkillMain 全屏对话

- **`SkillMain.tsx`** — 完整对话界面
  - 消息列表（ConversationView）
  - 消息输入框（MessageInput）
  - 发送到 IM 按钮（SendToImButton）

### UI 组件 (`src/components/`)

- **`ConversationView.tsx`** — 消息列表容器
  - 渲染 Message 数组
  - 自动滚动到最新消息

- **`MessageBubble.tsx`** — 单条消息气泡
  - 根据 role 显示不同样式（user/assistant/tool）
  - 集成 Markdown 渲染（react-markdown）

- **`CodeBlock.tsx`** — 代码块渲染
  - 使用 shiki 进行语法高亮
  - 支持 15+ 编程语言（JavaScript, TypeScript, Python, Java, Go, Rust, SQL, YAML 等）
  - 单例 Highlighter 模式（仅初始化一次）
  - 动态加载未知语言
  - 复制按钮

- **`MessageInput.tsx`** — 用户输入框
  - 文本输入 + 发送按钮
  - 支持多行输入

- **`SendToImButton.tsx`** — 发送到 IM 按钮
  - 调用 `useSendToIm` Hook
  - 将选中消息发送回 IM 聊天

- **`SessionSidebar.tsx`** — 会话列表侧边栏
  - 显示历史会话
  - 切换会话

### React Hooks (`src/hooks/`)

- **`useSkillStream(sessionId)`** — 核心流处理 Hook
  - 管理 WebSocket 连接到 Skill Server (`/ws/skill/stream/{sessionId}`)
  - 处理消息流（delta/done/error/agent_offline/agent_online）
  - 自动重连（指数退避：1s → 2s → 4s → ... → 30s 上限）
  - 返回：`{ messages, isStreaming, agentStatus, sendMessage, error }`

- **`useSkillSession()`** — 会话管理
  - 创建/加载/切换会话
  - 调用 Skill Server REST API

- **`useSendToIm()`** — IM 集成
  - 将消息发送回 IM 聊天
  - 调用 Skill Server 的 IM 消息分发接口

### 工具函数 (`src/utils/`)

- **`api.ts`** — Skill Server REST 客户端
  - `getMessages(sessionId, offset, limit)` — 获取消息历史
  - `sendMessage(sessionId, text)` — 发送用户消息
  - `createSession()` — 创建新会话
  - `getSessions()` — 获取会话列表
  - `sendToIm(sessionId, messageId, chatId)` — 发送到 IM

## AI Agent 工作指南

### 在此目录工作时

1. **协议解析**
   - 所有 OpenCode 事件解析在 `src/protocol/OpenCodeEventParser.ts`
   - 新增事件类型需更新 `KNOWN_TYPES` 和 `types.ts`
   - 解析函数不抛异常，未知类型返回 `eventType: 'unknown'`

2. **代码高亮**
   - 使用 shiki，配置在 `src/components/CodeBlock.tsx`
   - 支持的语言在 `getOrCreateHighlighter()` 中定义
   - 单例模式避免重复初始化

3. **WebSocket 流处理**
   - 订阅在 `src/hooks/useSkillStream.ts`
   - 连接地址：`ws://localhost:8080/ws/skill/stream/{sessionId}`（可通过 `VITE_SKILL_SERVER_WS` 环境变量配置）
   - 支持自动重连和心跳

4. **Markdown 渲染**
   - 使用 react-markdown + remark-gfm
   - 在 `src/components/MessageBubble.tsx` 中集成

5. **消息流程**
   - 用户输入 → `useSkillStream.sendMessage()` → REST API
   - Skill Server 推送 → WebSocket delta 消息 → `StreamAssembler` 拼接 → UI 更新
   - Tool Use 事件 → `ToolUseRenderer` 转换 → 消息气泡显示

### 测试要求

- **单元测试**
  - 协议解析逻辑（`OpenCodeEventParser.parse()`）
  - 流组装逻辑（`StreamAssembler`）
  - Tool Use 渲染逻辑（`ToolUseRenderer`）

- **组件测试**
  - CodeBlock 代码高亮渲染
  - MessageBubble 消息气泡样式
  - ConversationView 消息列表滚动

- **端到端测试**
  - 完整用户流程：输入 → 发送 → 流式接收 → 渲染
  - WebSocket 连接和重连
  - Tool Use 执行和结果显示

### 常见模式

- **流式消息拼接**
  ```typescript
  const assembler = new StreamAssembler();
  assembler.push(deltaText);
  const fullText = assembler.getText();
  assembler.complete();
  ```

- **Tool Use 结果转换**
  ```typescript
  const toolInfo = ToolUseRenderer.startTool(parsed);
  const updated = ToolUseRenderer.completeTool(parsed, toolInfo);
  const rendered = ToolUseRenderer.renderToolUse(updated);
  ```

- **消息气泡样式**
  - User 消息：右对齐，蓝色背景
  - Assistant 消息：左对齐，灰色背景
  - Tool 消息：左对齐，绿色背景

## 依赖关系

### 内部依赖

- **Skill Server WebSocket** — 端口 8082（默认）
  - 连接：`/ws/skill/stream/{sessionId}`
  - 消息格式：`StreamMessage`

- **Skill Server REST API** — 端口 8082
  - `GET /api/sessions/{sessionId}/messages` — 获取消息历史
  - `POST /api/sessions/{sessionId}/messages` — 发送消息
  - `POST /api/sessions` — 创建会话
  - `GET /api/sessions` — 获取会话列表
  - `POST /api/im/send` — 发送到 IM

### 外部依赖

| 包 | 版本 | 用途 |
|---|------|------|
| `react` | ^18.2.0 | UI 框架 |
| `react-dom` | ^18.2.0 | DOM 渲染 |
| `react-markdown` | ^9.0 | Markdown 渲染 |
| `remark-gfm` | ^4.0 | GitHub Flavored Markdown 支持 |
| `shiki` | ^1.9.0 | 代码语法高亮 |
| `typescript` | ^5.3.0 | 类型检查 |
| `vite` | ^5.0.0 | 构建工具 |

### 运行环境

- **IM 小程序容器** — 宿主环境（DingTalk, Feishu, Slack）
- **Node.js 18+** — 开发和构建

## 构建和开发

### 开发模式

```bash
cd skill-miniapp
npm install
npm run dev
# 启动 Vite 开发服务器，默认 http://localhost:5173
```

### 生产构建

```bash
npm run build
# 输出到 dist/ 目录
```

### 类型检查

```bash
npm run typecheck
# 运行 tsc --noEmit，不生成输出文件
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `VITE_SKILL_SERVER_WS` | `ws://localhost:80` | Skill Server WebSocket 基础 URL |
| `VITE_SKILL_SERVER_API` | `http://localhost:8082` | Skill Server REST API 基础 URL |

## 常见问题

| 问题 | 诊断 | 解决方案 |
|------|------|-----|
| WebSocket 连接失败 | 检查 Skill Server 是否运行 | 确保 Skill Server 在端口 8082 运行 |
| 代码高亮不显示 | shiki 初始化失败 | 检查浏览器控制台错误，确保网络连接 |
| 消息不更新 | WebSocket 消息处理错误 | 检查 `useSkillStream` 的 `handleStreamMessage` 逻辑 |
| 样式错乱 | CSS 加载失败 | 检查 `src/styles/` 文件是否存在 |
| 类型错误 | TypeScript 编译失败 | 运行 `npm run typecheck` 查看详细错误 |

## 参考

- **父文档：** `../AGENTS.md`
- **React 文档：** https://react.dev
- **Vite 文档：** https://vitejs.dev
- **react-markdown：** https://github.com/remarkjs/react-markdown
- **shiki：** https://shiki.matsu.io
- **TypeScript：** https://www.typescriptlang.org
