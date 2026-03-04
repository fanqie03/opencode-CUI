<!-- Parent: ../AGENTS.md -->

# Protocol 协议适配层

## 目的

Protocol 目录是 Skill Miniapp 的核心模块，负责解析 OpenCode 事件格式、拼接流式消息、转换 Tool Use 结果。这是整个系统中唯一解析 OpenCode 协议的地方。

## 关键文件

| 文件 | 描述 |
|------|------|
| `types.ts` | OpenCode 事件类型定义（OpenCodeEvent, ParsedEvent, StreamMessage, ToolUseInfo） |
| `OpenCodeEventParser.ts` | 解析 OpenCode 事件类型（message.updated, session.*, tool.*） |
| `StreamAssembler.ts` | 流式 delta 事件拼接为完整消息 |
| `ToolUseRenderer.ts` | Tool Use 结果转换为可渲染结构 |

## 核心模块详解

### types.ts — 类型定义

**OpenCodeEventType** — 支持的事件类型
- `message.updated` — 消息流式更新（包含 delta 或完整 text）
- `session.completed` — 会话完成（包含 token 使用统计）
- `session.created` — 会话创建
- `tool.start` — Tool 开始执行
- `tool.result` — Tool 执行成功
- `tool.error` — Tool 执行失败
- `unknown` — 未知事件类型

**OpenCodeEvent** — 原始事件对象（来自 Skill Server 透明中继）
```typescript
{
  type: string;               // 事件类型字符串
  sessionId?: string;               // 会话 ID
  content?: { delta?: string; text?: string };     // 消息内容
  usage?: { input_tokens: number; output_tokens: number };  // token 统计
  tool?: string;                        // Tool 名称
  args?: Record<string, unknown>;              // Tool 输入参数
  result?: string;                    // Tool 执行结果
  error?: string;                    // 错误信息
}
```

**ParsedEvent** — 规范化事件（UI 消费）
- 包含所有 OpenCodeEvent 字段的规范化版本
- `eventType` — 类型化的事件类型
- `raw` — 原始事件对象（用于调试）

**StreamMessage** — Skill Server WebSocket 消息格式
- `type` — 消息类型（delta/done/error/agent_offline/agent_online）
- `event` — 嵌入的 OpenCode 事件

**ToolUseInfo** — Tool Use 执行信息
- `toolName` — Tool 名称
- `args` — 输入参数
- `result` — 执行结果
- `error` — 错误信息
- `status` — 执行状态（running/completed/error）

### OpenCodeEventParser.ts — 事件解析

**parse(event: OpenCodeEvent): ParsedEvent**

将原始 OpenCode 事件转换为规范化的 ParsedEvent。

特性：
- 不抛异常，未知事件类型返回 `eventType: 'unknown'`
- 支持所有 6 种已知事件类型
- 提取相关字段到规范化结构

示例：
```typescript
const raw = {
  type: 'message.updated',
  sessionId: 'sess-123',
  content: { delta: 'Hello' }
};
const parsed = parse(raw);
// parsed.eventType === 'message.updated'
// parsed.delta === 'Hello'
```

### StreamAssembler.ts — 流消息拼接

**职责：** 累积 delta 消息片段，生成完整文本。

**API：**
- `push(delta: string)` — 追加 delta 片段
- `getText(): string` — 获取当前累积的完整文本
- `complete()` — 标记流完成（后续 push 调用被忽略）
- `isCompleted(): boolean` — 检查流是否已完成
- `reset()` — 重置状态（用于新消息）

特性：
- 完成后的流不再接收新 delta（幂等性）
- 支持边界条件：空 delta、乱序、重复

示例：
```typescript
const assembler = new StreamAssembler();
assembler.push('Hello ');
assembler.push('World');
console.log(assembler.getText()); // 'Hello World'
assembler.complete();
assembler.push('!');  // 被忽略
console.log(assembler.getText()); // 'Hello World'
```

### ToolUseRenderer.ts — Tool Use 结果渲染

**startTool(event: ParsedEvent): ToolUseInfo**

从 `tool.start` 事件创建 ToolUseInfo（状态：running）。

**completeTool(event: ParsedEvent, existing: ToolUseInfo): ToolUseInfo**

从 `tool.result` 或 `tool.error` 事件更新 ToolUseInfo。
- `tool.result` → 状态变为 completed，包含 result
- `tool.error` → 状态变为 error，包含 error 信息

**renderToolUse(info: ToolUseInfo): { title, content, language? }**

生成可渲染的 Tool Use 表示。

特性：
- 标题包含 Tool 名称和状态标签（运行中.../错误/完成）
- 结果尝试 JSON 格式化，失败则返回原始字符串
- 运行中时显示输入参数
- 错误时显示错误信息

示例：
```typescript
const event = { eventType: 'tool.start', toolName: 'bash', toolArgs: { cmd: 'ls' } };
const info = startTool(event);
const rendered = renderToolUse(info);
// rendered.title === 'Tool: bash [运行中...]'
// rendered.content === '{"cmd":"ls"}'
// rendered.language === 'json'
```

## AI Agent 工作指南

### 在此目录工作时

1. **事件解析**
   - 所有 OpenCode 事件解析在 `OpenCodeEventParser.ts`
   - 新增事件类型需更新 `KNOWN_TYPES` 常量和 `types.ts` 中的 `OpenCodeEventType`
   - 解析函数不抛异常，未知类型返回 `eventType: 'unknown'`

2. **流式消息处理**
   - 使用 `StreamAssembler` 累积 delta 消息
   - 调用 `complete()` 标记流结束
   - 支持重置以处理新消息

3. **Tool Use 转换**
   - `startTool()` 处理 `tool.start` 事件
   - `completeTool()` 处理 `tool.result` 和 `tool.error` 事件
   - `renderToolUse()` 生成 UI 可消费的格式

### 版本兼容策略

- 当 OpenCode SDK 更新事件格式时，只需修改此目录
- 服务端（AI-Gateway、Skill Server）和 PC Agent 无需改动
- 保持向后兼容：新字段可选，旧字段保留
- 未知事件类型不会导致解析失败

### 测试要求

- **单元测试**
  - 所有 6 种事件类型的解析（message.updated, session.completed, session.created, tool.start, tool.result, tool.error）
  - 未知事件类型处理
  - 流式消息拼接的边界条件（空 delta、乱序、重复）
  - Tool Use 状态转换（running → completed/error）
  - JSON 格式化失败的降级处理

- **集成测试**
  - 完整事件流：session.created → message.updated (多个 delta) → session.completed
  - Tool 执行流：tool.start → tool.result/tool.error
  - 错误恢复：无效事件不中断处理

### 常见模式

**流式消息拼接**
```typescript
const assembler = new StreamAssembler();
// 接收多个 delta 事件
for (const event of deltaEvents) {
  assembler.push(event.delta);
}
assembler.complete();
const fullText = assembler.getText();
```

**Tool Use 结果转换**
```typescript
let toolInfo = startTool(startEvent);
// ... 等待 tool.result 或 tool.error 事件
toolInfo = completeTool(resultEvent, toolInfo);
const rendered = renderToolUse(toolInfo);
// 在 UI 中显示 rendered.title 和 rendered.content
```

**事件处理流程**
```typescript
const parsed = parse(rawEvent);
switch (parsed.eventType) {
  case 'message.updated':
    assembler.push(parsed.delta);
    break;
  case 'tool.start':
    toolInfo = startTool(parsed);
    break;
  case 'tool.result':
  case 'tool.error':
    toolInfo = completeTool(parsed, toolInfo);
    break;
}
```

## 依赖关系

### 内部依赖

- 被 `src/hooks/useSkillStream.ts` 调用处理 WebSocket 消息
- 类型定义被整个 Skill Miniapp 使用
- 被 `src/components/MessageBubble.tsx` 用于渲染 Tool Use 消息

### 外部依赖

- 无外部依赖，仅使用 TypeScript 标准库
- 直接解析 JSON，不依赖任何协议库

### 数据流
```
Skill Server WebSocket
    ↓
StreamMessage (包含 OpenCodeEvent)
    ↓
OpenCodeEventParser.parse()
    ↓
ParsedEvent
    ↓
StreamAssembler / ToolUseRenderer
    ↓
UI 组件 (MessageBubble, ConversationView)
```

## 参考

- **父文档：** `../AGENTS.md`
- **类型定义：** `types.ts`
- **事件解析：** `OpenCodeEventParser.ts`
- **流组装：** `StreamAssembler.ts`
- **Tool 渲染：** `ToolUseRenderer.ts`
