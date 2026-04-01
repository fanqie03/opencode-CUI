# Subagent Miniapp 展示与交互设计

> 让 miniapp 能够展示 OpenCode subagent 的完整对话内容（text、thinking、tool、file），并支持 subagent 中 permission/question 的交互操作。

---

## 问题背景

OpenCode 的 subagent 功能通过**创建子 Session** 实现。当主 agent 派发 subtask 时，OpenCode 创建一个 `parentID` 指向主 session 的子 session。子 session 中的所有事件（permission、question、text、tool 等）携带的是**子 session 的 ID**。

当前系统只追踪主 session 的 `toolSessionId ↔ welinkSessionId` 映射，导致子 session 的事件无法路由到 miniapp，用户看不到 subagent 的 permission/question，造成流程卡死。

---

## 方案选型

| 方案 | 描述 | 结论 |
|------|------|------|
| A. Plugin 层映射重写 | Plugin 维护 child→parent 映射，重写 toolSessionId 后转发 | **选用** |
| B. Gateway 层会话树 | Gateway 维护 session 父子关系树，按树结构路由 | 改动大，有竞态问题 |
| C. Skill-server 主动订阅 | skill-server 检测到 subtask part 后主动订阅子 session | 时序问题严重，三层都要大改 |

**选用方案 A 的理由**：
1. Plugin 已能收到所有 OpenCode 事件（包括 `session.created`），在源头建立映射最自然
2. 对 Gateway 路由逻辑零侵入，skill-server 只需处理新增字段
3. `session.created` 一定先于子 session 的任何其他事件，无时序问题
4. 回复 permission/question 时，Plugin 已知子 session 真实 ID，可正确路由回 OpenCode

---

## UI 展示设计

### 展示策略：折叠块 + 交互冒泡

- **折叠块**：subagent 的完整对话内容（text、thinking、tool、file）收纳在可折叠块中，默认折叠
- **交互冒泡**：阻塞性交互（permission、question）自动浮到主对话流层级，标注来源 agent 名称

### 折叠状态

```
┌──────────────────────────────────────────────┐
│ agent-icon {agentName}                       │
│ "{prompt}" (截断50字)                         │
│ status-dot {status}  |  {n} tools  |  {time} │
│                                      > 展开   │
└──────────────────────────────────────────────┘
```

### 展开状态

```
┌──────────────────────────────────────────────┐
│ agent-icon {agentName}  status-dot {status}  │
│ Prompt: "{prompt}"                    v 收起  │
├──────────────────────────────────────────────┤
│                                              │
│ thinking-icon Thinking                v 收起  │
│ | 我需要先看一下 auth 模块的结构...           │
│                                              │
│ text-icon Text                               │
│ | 我来分析这个模块的代码结构。                 │
│                                              │
│ tool-icon Tool: Read  src/auth/login.ts  done │
│ | [展开查看输出]                              │
│                                              │
│ text-icon Text                               │
│ | 发现了以下几个问题：                        │
│ | 1. session token 存储方式不安全...          │
│                                              │
│ status-dot {status}  |  {n} tools  |  {time} │
└──────────────────────────────────────────────┘
```

### 多并行 subagent + 冒泡交互

```
主对话流：

[assistant] 好的，我同时启动两个子任务来处理。

┌─ agent-icon code-reviewer  status-dot 运行中 ── > 展开 ─┐
│  "Review auth module..."  |  2 tools  |  00:08           │
└──────────────────────────────────────────────────────────┘

┌─ agent-icon test-writer  status-dot 运行中 ─── > 展开 ─┐
│  "Write tests for auth..."  |  1 tool  |  00:05          │
└──────────────────────────────────────────────────────────┘

warn-icon [code-reviewer] 请求写入 src/auth/login.ts     <-- 冒泡
   [允许一次] [总是允许] [拒绝]

question-icon [test-writer] 使用哪个测试框架？            <-- 冒泡
   [Jest] [Vitest] [自定义输入]
```

### 设计要点

| 元素 | 说明 |
|------|------|
| 折叠块头部 | agent 名称 + prompt 摘要 + 实时状态（运行中/已完成/错误） |
| 统计栏 | tool 调用数 + 运行时间，折叠时也可见 |
| 展开后内容 | 按时间顺序展示所有 part：thinking、text、tool call、file 等 |
| tool call | 二级折叠，默认只显示工具名+状态，可展开看 input/output |
| 冒泡卡片 | 标注 `[agent名称]` 来源，用户知道是哪个 subagent 在请求 |
| 并行 subagent | 多个折叠块并排展示，各自独立折叠/展开 |

---

## 全链路数据流

```
1. OpenCode 创建 subagent
   → session.created { id: "child-123", parentID: "parent-456" }
   → Plugin 缓存映射: child-123 → { parentId: parent-456, agentName: "code-reviewer" }

2. 子 session 产生事件（text/tool/permission/question）
   → Plugin 拦截，重写 toolSessionId 为 parent-456
   → 附加 subagentSessionId: "child-123", subagentName: "code-reviewer"
   → 转发 tool_event 到 Gateway

3. Gateway 正常路由（parent-456 有映射）
   → skill-server 收到事件
   → OpenCodeEventTranslator 识别 subagent 字段
   → 生成带 subagent 元数据的 StreamMessage
   → 持久化（扁平存储，带 subagent 标识）
   → WebSocket 推送给 miniapp

4. miniapp 收到消息
   → 普通 part: 归入对应 subtask block
   → permission/question: 同时归入 subtask block + 冒泡到主对话流

5. 用户交互（回复 permission/question）
   → miniapp POST 到 skill-server（携带 subagentSessionId）
   → skill-server 构建 invoke，附加子 session 信息
   → Gateway → Plugin 用 child-123（真实子 sessionId）回复 OpenCode
```

### 各层职责

| 层 | 新增职责 |
|---|---|
| Plugin (message-bridge) | 维护 child→parent 映射，重写 toolSessionId，附加 subagent 元数据 |
| Gateway (ai-gateway) | 透传 subagent 字段（无路由逻辑改动） |
| skill-server | 翻译 subagent 事件为 StreamMessage，扁平持久化，推送 |
| skill-miniapp | SubtaskBlock 组件，冒泡交互，subtask 状态管理 |

---

## 第一层：Plugin (message-bridge) 改动

### 改动文件

| 文件 | 改动 |
|------|------|
| `BridgeRuntime.ts` | `handleEvent` 中增加子 session 映射查询和 toolSessionId 重写 |
| `contracts/transport-messages.ts` | `ToolEventMessage` 新增 `subagentSessionId`、`subagentName` 字段 |
| `contracts/upstream-events.ts` | 允许列表新增 `session.created` 事件 |
| 新增 `SubagentSessionMapper.ts` | 内存缓存 + OpenCode API 懒查询的映射器 |

### SubagentSessionMapper

内存缓存 + 懒查询兜底策略：

```
Plugin 收到事件，提取 sessionID
  |
内存缓存命中？
  ├─ YES → 直接使用，重写 toolSessionId
  └─ NO  → 调用 OpenCode API: GET /session/{sessionId}
           ├─ session 有 parentID → 缓存映射，重写转发
           └─ session 无 parentID → 标记为主 session，正常转发
```

- **正常流程**：`session.created` 事件先到，主动写入缓存，后续事件全部命中
- **重启后兜底**：首次缓存 miss 时懒查询一次 OpenCode 本地 API（毫秒级），后续命中缓存

```typescript
class SubagentSessionMapper {
  // 缓存：sessionId → mapping | null
  // null 表示已确认是主 session，避免重复查询
  private cache = new Map<string, SubagentMapping | null>();

  // session.created 事件主动写入
  onSessionCreated(event: SessionCreatedEvent): void {
    const sessionId = event.properties.info.id;
    const parentID = event.properties.info.parentID;
    if (parentID) {
      this.cache.set(sessionId, {
        parentSessionId: parentID,
        agentName: event.properties.info.agent ?? 'unknown',
      });
    }
  }

  // 查询映射（缓存 + 懒查询兜底）
  async resolve(sessionId: string): Promise<SubagentMapping | null> {
    if (this.cache.has(sessionId)) {
      return this.cache.get(sessionId)!;
    }

    const session = await this.client.session.get({ path: { id: sessionId } });
    if (session.parentID) {
      const mapping = {
        parentSessionId: session.parentID,
        agentName: session.agent ?? 'unknown',
      };
      this.cache.set(sessionId, mapping);
      return mapping;
    }

    this.cache.set(sessionId, null);
    return null;
  }
}

interface SubagentMapping {
  parentSessionId: string;
  agentName: string;
}
```

### handleEvent 改动

在 `BridgeRuntime.ts` 的 `handleEvent()` 方法中，事件投影（步骤 6）和构建传输包（步骤 7）之间插入映射查询：

```typescript
// 步骤 6: 事件转换
const transportEvent = this.upstreamTransportProjector.project(normalized);

// 步骤 6.5: 子 session 映射检查（新增）
const subagentMapping = await this.subagentSessionMapper.resolve(
  normalized.common.toolSessionId
);

// 步骤 7: 构建传输包
let transportEnvelope;
if (subagentMapping) {
  transportEnvelope = {
    type: 'tool_event',
    toolSessionId: subagentMapping.parentSessionId,   // 重写为父 session
    subagentSessionId: normalized.common.toolSessionId,
    subagentName: subagentMapping.agentName,
    event: transportEvent,
  };
} else {
  transportEnvelope = {
    type: 'tool_event',
    toolSessionId: normalized.common.toolSessionId,
    event: transportEvent,
  };
}
```

### session.created 事件处理

在 `handleEvent()` 中，事件提取后立即检查是否为 `session.created`：

```typescript
// 步骤 1 之后：主动捕获 session.created 事件
if (normalized.common.eventType === 'session.created') {
  this.subagentSessionMapper.onSessionCreated(normalized.raw);
}
```

### transport-messages.ts 改动

```typescript
interface ToolEventMessage {
  type: 'tool_event';
  toolSessionId: string;
  subagentSessionId?: string;   // 新增
  subagentName?: string;        // 新增
  event: SupportedUpstreamEvent;
}
```

---

## 第二层：Gateway (ai-gateway) 改动

### 改动范围：最小，仅透传

Gateway 不需要理解 subagent 的概念，只需确保 `subagentSessionId` 和 `subagentName` 字段在消息传递过程中不被丢弃。

### GatewayMessageRouter 改动

`route()` 和 `dispatchLocally()` 方法在解析 `tool_event` 消息时，将 subagent 字段传递给下游处理器：

```java
// dispatchLocally() 中
JsonNode subagentSessionId = node.path("subagentSessionId");
JsonNode subagentName = node.path("subagentName");
// 传递给 handleToolEvent()、handlePermissionRequest() 等处理器
```

### Permission/Question 回复路由

当 skill-server 发起 `permission_reply` 或 `question_reply` invoke 时，payload 中的 `toolSessionId` 使用 `subagentSessionId`（子 session 真实 ID）。Gateway 原样转发给 Plugin，Plugin 用该 ID 回复 OpenCode。无需 Gateway 做额外处理。

---

## 第三层：skill-server 改动

### 1. StreamMessage 新增字段

```java
// StreamMessage.java
@Data @Builder
public class StreamMessage {
    // ... 现有字段 ...

    private String subagentSessionId;   // 子 session ID
    private String subagentName;        // 子 agent 名称
    private String subagentPrompt;      // 子 agent 的 prompt（来自 subtask part）
    private String subagentStatus;      // running | completed | error
}
```

### 2. OpenCodeEventTranslator 改动

`translate()` 方法从 Gateway 传入的 JSON node 中提取 subagent 字段，注入到生成的 StreamMessage 中：

```java
String subagentSessionId = node.path("subagentSessionId").asText(null);
String subagentName = node.path("subagentName").asText(null);

StreamMessage msg = StreamMessage.builder()
    // ... 现有翻译逻辑 ...
    .subagentSessionId(subagentSessionId)
    .subagentName(subagentName)
    .build();
```

### 3. Subtask 生命周期消息

当 Plugin 转发带 `parentID` 的 `session.created` 和子 session 的 `session.idle` 事件时，skill-server 生成特殊消息：

- 子 session 创建 → `STEP_START` 消息（附带 subagentSessionId、subagentName、subagentPrompt、subagentStatus=running）
- 子 session 完成 → `STEP_DONE` 消息（附带 subagentSessionId、subagentStatus=completed）

### 4. 持久化：扁平存储

数据库 `message_parts` 表新增字段：

```sql
ALTER TABLE message_parts ADD COLUMN subagent_session_id VARCHAR(64) DEFAULT NULL;
ALTER TABLE message_parts ADD COLUMN subagent_name VARCHAR(128) DEFAULT NULL;
```

现有持久化方法（`persistIfFinal()`、`persistPermissionPart()`、`persistToolPart()`）在写入时同时写入 subagent 字段。

查询时按 `subagent_session_id` 分组，还原为 subtask block 结构。

### 5. Permission/Question 回复路由

SkillMessageController 中 `replyPermission()` 和 `sendMessage()`（question 回复）在请求中接收 `subagentSessionId`，构建 invoke 时使用子 session 的真实 ID：

```java
String targetSessionId = request.getSubagentSessionId() != null
    ? request.getSubagentSessionId()   // 子 session → 用子 session ID
    : session.getToolSessionId();       // 主 session → 用主 session ID

// invoke payload 中 toolSessionId 使用 targetSessionId
```

### 6. 历史消息加载

加载历史消息时，后端按 `subagent_session_id` 分组后返回，前端直接组装为 SubtaskPart 结构。

---

## 第四层：skill-miniapp 前端改动

### 1. 类型定义扩展

```typescript
// protocol/types.ts

// StreamMessage 新增字段
interface StreamMessage {
  // ... 现有字段 ...
  subagentSessionId?: string;
  subagentName?: string;
  subagentPrompt?: string;
  subagentStatus?: 'running' | 'completed' | 'error';
}

// 新增 SubtaskPart 类型
interface SubtaskPart {
  type: 'subtask';
  partId: string;                    // = subagentSessionId
  subagentName: string;
  subagentPrompt: string;
  status: 'running' | 'completed' | 'error';
  parts: MessagePart[];              // 子 agent 内部的所有 parts
}

// MessagePart union 新增 subtask
type MessagePart = TextPart | ToolPart | ThinkingPart | PermissionPart
                 | QuestionPart | FilePart | SubtaskPart;
```

### 2. handleStreamMessage 分发逻辑

```typescript
// useSkillStream.ts

const handleStreamMessage = useCallback((msg: StreamMessage) => {
  if (msg.subagentSessionId) {
    handleSubagentMessage(msg);
    return;
  }
  // 现有主 session 逻辑不变
  switch (msg.type) { ... }
}, [...]);

const handleSubagentMessage = useCallback((msg: StreamMessage) => {
  const { subagentSessionId, subagentName, type } = msg;

  // 1. 确保 subtask block 存在
  ensureSubtaskPart(msg.messageId, subagentSessionId, subagentName, msg.subagentPrompt);

  // 2. 将消息归入对应 subtask block 的内部 parts
  appendToSubtaskPart(msg.messageId, subagentSessionId, msg);

  // 3. permission/question → 同时冒泡到主对话流
  if (type === 'permission.ask' || type === 'question') {
    applyStreamedMessage({
      ...msg,
      subagentName: subagentName, // UI 用于显示来源标注
    });
  }

  // 4. subtask 状态更新
  if (msg.subagentStatus) {
    updateSubtaskStatus(subagentSessionId, msg.subagentStatus);
  }
}, [...]);
```

### 3. 新增 SubtaskBlock 组件

复用现有组件（TextBlock、ToolCard、ThinkingBlock、PermissionCard、QuestionCard）渲染子 agent 内部 parts。折叠/展开通过 `useState` 控制。

### 4. PermissionCard / QuestionCard 改动

- Props 新增 `subagentName?: string`
- 渲染时有 `subagentName` → 显示 `[{agentName}] 请求...`
- 回复请求中携带 `subagentSessionId`

### 5. 历史消息还原

加载历史消息时，后端返回的 parts 中有 `subagent_session_id` 的按分组组装为 SubtaskPart，前端直接渲染 SubtaskBlock。

### 6. CSS 样式

```css
.subtask-block {
  border-left: 3px solid var(--color-border-subtle);
  margin: 8px 0;
  border-radius: 8px;
  background: var(--color-bg-subtle);
}
.subtask-block--running   { border-left-color: var(--color-accent); }
.subtask-block--completed { border-left-color: var(--color-success); }
.subtask-block--error     { border-left-color: var(--color-error); }

.permission-card__source,
.question-card__source {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}
```

---

## 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| Plugin 重启后缓存丢失 | 懒查询 OpenCode API `GET /session/{id}` 补回映射 |
| 多个 subagent 并行运行 | 每个 subagent 独立的 SubtaskBlock，各自折叠/展开 |
| subagent 嵌套（子 agent 再派子 agent） | 映射器递归查找最顶层父 session（链式 parentID） |
| 用户刷新 miniapp | 历史消息从 MySQL 加载，按 subagent_session_id 分组还原 |
| permission/question 未回复时刷新 | 持久化中记录 status（pending），刷新后重新展示等待回复的卡片 |
| subagent 执行中断/错误 | 收到子 session 的 `session.error` 事件 → 更新 SubtaskBlock 状态为 error |
