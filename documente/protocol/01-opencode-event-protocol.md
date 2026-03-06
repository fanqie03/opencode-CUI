# OpenCode 事件协议梳理与前端展示方案

> 数据来源：`anomalyco/opencode` (dev branch) `packages/sdk/js/src/gen/types.gen.ts`

## 一、Event 联合类型（31 种）

### 被 PC Agent 中继到 Gateway 的事件（17 种）

| 事件类型               | properties 结构                             | 用途                               |
| ---------------------- | ------------------------------------------- | ---------------------------------- |
| `message.updated`      | `{ info: Message }`                         | 消息元数据（角色、token、finish）  |
| `message.removed`      | `{ sessionID, messageID }`                  | 消息删除                           |
| `message.part.updated` | `{ part: Part, delta?: string }`            | **核心事件：含 12 种 Part 子类型** |
| `message.part.removed` | `{ sessionID, messageID, partID }`          | Part 删除                          |
| `permission.updated`   | `Permission`                                | 权限请求（bash/file 等操作前审批） |
| `permission.replied`   | `{ sessionID, permissionID, response }`     | 权限已回复                         |
| `session.created`      | `{ info: Session }`                         | 新会话创建                         |
| `session.updated`      | `{ info: Session }`                         | 会话标题/summary 更新              |
| `session.status`       | `{ sessionID, status: idle/busy/retry }`    | 会话状态                           |
| `session.idle`         | `{ sessionID }`                             | 会话空闲                           |
| `session.diff`         | `{ sessionID, diff: FileDiff[] }`           | 文件变更 diff                      |
| `session.error`        | `{ sessionID?, error? }`                    | 会话错误                           |
| `session.deleted`      | `{ info: Session }`                         | 会话删除                           |
| `session.compacted`    | `{ sessionID }`                             | 会话压缩                           |
| `file.edited`          | `{ file: string }`                          | 文件编辑                           |
| `todo.updated`         | `{ sessionID, todos: Todo[] }`              | TODO 列表                          |
| `command.executed`     | `{ name, sessionID, arguments, messageID }` | 命令执行                           |

### 不中继的本地事件（14 种）

`server.*`、`installation.*`、`lsp.*`、`file.watcher.*`、`vcs.*`、`tui.*`、`pty.*`

### Plugin 专属 Hook 事件（不在 SDK Event 联合类型中）

| 事件                  | 说明                                                   |
| --------------------- | ------------------------------------------------------ |
| `tool.execute.before` | 工具执行前 hook                                        |
| `tool.execute.after`  | 工具执行后 hook                                        |
| `shell.env`           | Shell 环境变量                                         |
| `permission.asked`    | Plugin 文档中的命名（= SDK 中的 `permission.updated`） |

---

## 二、Part 联合类型（12 种）—— `message.part.updated` 的核心

```typescript
Part = TextPart        // "text"        — AI 回复正文 ⭐
     | ReasoningPart   // "reasoning"   — 思维链 ⭐
     | ToolPart        // "tool"        — 工具调用+结果 ⭐（含 question）
     | StepStartPart   // "step-start"  — 推理步骤开始
     | StepFinishPart  // "step-finish" — 推理步骤结束（含 token 统计）
     | FilePart        // "file"        — 文件附件（图片等）
     | SubtaskPart     // "subtask"     — 子任务委派
     | AgentPart       // "agent"       — 子代理调用
     | SnapshotPart    // "snapshot"    — 状态快照
     | PatchPart       // "patch"       — 补丁文件
     | RetryPart       // "retry"       — API 重试
     | CompactionPart  // "compaction"  — 上下文压缩
```

### 2.1 TextPart — AI 文本回复

```typescript
{ type: "text", id, sessionID, messageID,
  text: string,           // 完整文本内容
  synthetic?: boolean, ignored?: boolean,
  time?: { start, end? } }
```

> `message.part.updated` 还可能携带 `delta?: string` 字段（增量文本）

### 2.2 ReasoningPart — 思维链

```typescript
{ type: "reasoning", id, sessionID, messageID,
  text: string,           // 思考过程
  time: { start, end? } }
```

### 2.3 ToolPart — 工具调用（含 question）⭐

**这是最重要的 Part 类型，统一了工具调用和结果。** 不像之前假设的 `tool-invocation`/`tool-result` 两种类型，实际上只有一种 `"tool"` 类型，通过 `state.status` 区分阶段：

```typescript
{ type: "tool", id, sessionID, messageID,
  callID: string,         // 工具调用 ID
  tool: string,           // 工具名：bash/edit/question/...
  state: ToolState }      // 状态机 ↓

// ToolState 四种状态：
ToolStatePending   = { status: "pending",   input: {...}, raw: string }
ToolStateRunning   = { status: "running",   input: {...}, title?, metadata?, time: {start} }
ToolStateCompleted = { status: "completed", input: {...}, output: string, title, metadata, time: {start,end}, attachments? }
ToolStateError     = { status: "error",     input: {...}, error: string, metadata?, time: {start,end} }
```

**question 工具的生命周期：**
```
pending  → AI 正在构建 question 参数
running  → 等待用户回答（前端需要渲染问答 UI）
            input: { header: "...", question: "...", options: [...] }
completed → 用户已回答
            output: "用户选择的答案"
error    → 超时或异常
```

### 2.4 StepStartPart / StepFinishPart

```typescript
StepStartPart  = { type: "step-start", id, sessionID, messageID, snapshot? }
StepFinishPart = { type: "step-finish", id, sessionID, messageID,
                   reason: string, cost: number,
                   tokens: { input, output, reasoning, cache: {read, write} } }
```

### 2.5 其他 Part 类型

| Part Type    | 字段                           | 前端用途               |
| ------------ | ------------------------------ | ---------------------- |
| `file`       | `mime, filename, url, source?` | 展示文件附件/图片      |
| `subtask`    | `prompt, description, agent`   | 子任务委派说明         |
| `agent`      | `name, source?`                | 子代理激活通知         |
| `snapshot`   | `snapshot`                     | 内部状态（一般不展示） |
| `patch`      | `hash, files[]`                | 补丁文件列表           |
| `retry`      | `attempt, error, time`         | API 重试通知           |
| `compaction` | `auto: boolean`                | 上下文压缩通知         |

---

## 三、前端展示方案

### 3.1 优先级

| 优先级 | 展示类别      | 事件/Part 来源                           | 展示形式                    |
| :----: | ------------- | ---------------------------------------- | --------------------------- |
|   P0   | AI 文本回复   | TextPart + delta                         | 流式 Markdown               |
|   P0   | AI 提问       | ToolPart (tool=question, status=running) | 标题+问题+选项按钮+自由输入 |
|   P0   | 权限请求      | permission.updated                       | 交互式审批弹窗              |
|   P0   | 会话/工具状态 | session.status, ToolPart status          | 状态指示器                  |
|   P1   | 思维链        | ReasoningPart                            | 可折叠区域                  |
|   P1   | 工具调用      | ToolPart (tool!=question)                | 工具名+参数+结果面板        |
|   P2   | 文件变更      | session.diff / file.edited               | diff 展示                   |
|   P2   | Token 统计    | StepFinishPart                           | 统计信息                    |
|   P2   | 会话标题      | session.updated                          | 标题栏                      |
|   P3   | 子代理/子任务 | AgentPart / SubtaskPart                  | 子任务指示                  |
|   P3   | TODO          | todo.updated                             | 任务列表                    |

### 3.2 Skill Server StreamMessage 转换映射

| 来源                                    | StreamMessage `type` | `content`       | `event`  |
| --------------------------------------- | -------------------- | --------------- | -------- |
| TextPart (有 delta)                     | `delta`              | delta 文本      | 原始事件 |
| TextPart (无 delta)                     | `delta`              | part.text       | 原始事件 |
| ReasoningPart                           | `delta`              | part.text       | 原始事件 |
| ToolPart, tool=question, status=running | `question`           | null            | 原始事件 |
| ToolPart, tool!=question                | `tool`               | null            | 原始事件 |
| StepStartPart                           | `status`             | null            | 原始事件 |
| StepFinishPart                          | `done`               | null            | 原始事件 |
| message.updated (有 finish)             | `done`               | null            | 原始事件 |
| session.status                          | `status`             | busy/idle/retry | 原始事件 |
| session.idle                            | `done`               | null            | 原始事件 |
| session.updated                         | `meta`               | null            | 原始事件 |
| permission.updated                      | `permission`         | null            | 原始事件 |
| 其他所有                                | `meta`               | null            | 原始事件 |

### 3.3 StreamMessage 类型定义

```typescript
interface StreamMessage {
  type: 'delta' | 'done' | 'error' | 'status' | 'meta'
      | 'question' | 'tool' | 'permission'
      | 'agent_offline' | 'agent_online';
  seq?: number;
  content?: string;        // 纯文本（delta/tool_result）
  partType?: string;       // Part type 原始值
  partId?: string;         // Part ID（用于增量更新）
  toolName?: string;       // 工具名（question/bash/edit...）
  toolStatus?: string;     // 工具状态（pending/running/completed/error）
  event?: OpenCodeEvent;   // 透传原始事件
  usage?: object;          // token 统计（done）
  message?: string;        // 错误信息（error）
}
```
