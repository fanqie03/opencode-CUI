# 前端 StreamMessage 协议设计

> 从 OpenCode 事件到前端友好的协议拆解方案

## 一、设计原则

```
OpenCode Event (复杂、嵌套)          StreamMessage (扁平、语义化)
┌─────────────────────────┐        ┌──────────────────────────┐
│ message.part.updated    │        │ type: "text.delta"       │
│   properties:           │   →    │ partId: "prt_xxx"        │
│     part:               │ Skill  │ content: "你好"           │
│       type: "text"      │ Server │                          │
│       text: "你好..."   │        │ (前端收到就知道:          │
│     delta: "你好"       │        │  追加文本到 prt_xxx 块)   │
└─────────────────────────┘        └──────────────────────────┘
```

| 原则            | 说明                                                             |
| --------------- | ---------------------------------------------------------------- |
| **语义化 type** | 前端看 type 就知道怎么渲染，不需要理解 OpenCode 内部概念         |
| **partId 追踪** | 同一个 Part 的多次更新通过 partId 关联，前端按 partId 做增量更新 |
| **扁平化字段**  | 所有关键信息提升到顶层，前端不需要嵌套解析                       |
| **raw 透传**    | `raw` 字段可选携带原始事件，高级场景可用                         |

---

## 二、消息类型一览

### 内容类（渲染到消息气泡内）

| type             | 触发源                       | 关键字段                                                        | 前端行为                 |
| ---------------- | ---------------------------- | --------------------------------------------------------------- | ------------------------ |
| `text.delta`     | TextPart + delta             | `partId, content`                                               | 追加文本到指定块（流式） |
| `text.done`      | TextPart 无 delta            | `partId, content`                                               | 替换/标记块完成          |
| `thinking.delta` | ReasoningPart + delta        | `partId, content`                                               | 追加到思维链块           |
| `thinking.done`  | ReasoningPart 无 delta       | `partId, content`                                               | 标记思维链完成           |
| `tool.update`    | ToolPart (非 question)       | `partId, toolName, toolCallId, status, input?, output?, error?` | 更新工具卡片状态         |
| `question`       | ToolPart (question, running) | `partId, toolCallId, header, question, options`                 | 渲染问答 UI              |
| `file`           | FilePart                     | `partId, mime, filename, url`                                   | 展示文件/图片            |

### 状态类（更新状态指示器）

| type             | 触发源                        | 关键字段               | 前端行为             |
| ---------------- | ----------------------------- | ---------------------- | -------------------- |
| `step.start`     | StepStartPart                 | —                      | 显示 "思考中..."     |
| `step.done`      | StepFinishPart                | `tokens, cost, reason` | 隐藏思考中，显示统计 |
| `session.status` | session.status / session.idle | `status: busy/idle`    | 更新状态指示器       |
| `session.title`  | session.updated               | `title`                | 更新标题栏           |
| `session.error`  | session.error                 | `error`                | 显示错误提示         |

### 交互类（需要用户操作）

| type               | 触发源             | 关键字段                                  | 前端行为 |
| ------------------ | ------------------ | ----------------------------------------- | -------- |
| `permission.ask`   | permission.updated | `permissionId, permType, title, metadata` | 审批弹窗 |
| `permission.reply` | permission.replied | `permissionId, response`                  | 关闭弹窗 |

### 系统类

| type            | 触发源        | 前端行为 |
| --------------- | ------------- | -------- |
| `agent.online`  | agent_online  | 显示在线 |
| `agent.offline` | agent_offline | 显示离线 |
| `error`         | tool_error    | 错误提示 |

---

## 三、TypeScript 类型定义

```typescript
/** Skill Server → 前端 WebSocket 消息 */
interface StreamMessage {
  // ─── 必填 ───
  type: StreamMessageType
  seq: number              // 序列号（用于排序和丢失检测）

  // ─── 内容类字段（按 type 选填）───
  partId?: string          // Part 唯一 ID（text/thinking/tool/question/file）
  content?: string         // 文本内容（text.delta/text.done/thinking.*）

  // ─── 工具类字段 ───
  toolName?: string        // 工具名：bash/edit/question...
  toolCallId?: string      // 工具调用 ID
  status?: string          // 工具状态：pending/running/completed/error
  input?: object           // 工具输入参数
  output?: string          // 工具输出结果
  title?: string           // 工具/权限标题

  // ─── question 专用 ───
  header?: string          // 问题标题
  question?: string        // 问题文本
  options?: string[]       // 选项列表

  // ─── 权限类字段 ───
  permissionId?: string
  permType?: string        // bash/file 等
  metadata?: object

  // ─── 统计类字段 ───
  tokens?: { input: number, output: number, reasoning: number,
             cache: { read: number, write: number } }
  cost?: number
  reason?: string          // 完成原因

  // ─── 错误 ───
  error?: string

  // ─── 透传 ───
  raw?: object             // 原始 OpenCode 事件（可选）
}

type StreamMessageType =
  | 'text.delta' | 'text.done'
  | 'thinking.delta' | 'thinking.done'
  | 'tool.update' | 'question' | 'file'
  | 'step.start' | 'step.done'
  | 'session.status' | 'session.title' | 'session.error'
  | 'permission.ask' | 'permission.reply'
  | 'agent.online' | 'agent.offline' | 'error'
```

---

## 四、一次完整对话的事件时序

```
用户发送 "帮我创建一个 React 项目"

时间  OpenCode 事件                              → StreamMessage
─────────────────────────────────────────────────────────────────
T1    session.status {busy}                     → { type:"session.status", status:"busy" }
T2    step-start                                → { type:"step.start" }
T3    reasoning part (delta="分析需求...")        → { type:"thinking.delta", partId:"prt_1", content:"分析需求..." }
T4    reasoning part (delta="需要用 create...")   → { type:"thinking.delta", partId:"prt_1", content:"需要用 create..." }
T5    reasoning part (done)                     → { type:"thinking.done", partId:"prt_1", content:"全部思考文本" }
T6    text part (delta="好的，")                 → { type:"text.delta", partId:"prt_2", content:"好的，" }
T7    text part (delta="我来帮你创建")           → { type:"text.delta", partId:"prt_2", content:"我来帮你创建" }

── AI 使用 question 工具提问 ──
T8    tool part (question, pending)             → { type:"tool.update", partId:"prt_3", toolName:"question", status:"pending" }
T9    tool part (question, running)             → { type:"question", partId:"prt_3", toolCallId:"call_1",
                                                     header:"项目配置", question:"选择模板", options:["Vite","CRA","Next.js"] }
      ← 前端渲染问答 UI，等待用户选择 →
T10   用户选择 "Vite"                           → (前端发送答案到 Skill Server)
T11   tool part (question, completed)           → { type:"tool.update", partId:"prt_3", status:"completed", output:"Vite" }

── AI 继续执行 ──
T12   text part (delta="使用 Vite 模板")        → { type:"text.delta", partId:"prt_4", content:"使用 Vite 模板" }

── 权限请求 ──
T13   permission.updated                        → { type:"permission.ask", permissionId:"p_1", permType:"bash",
                                                     title:"Run: npx create-vite", metadata:{command:"npx create-vite"} }
      ← 前端弹出审批弹窗 →
T14   用户批准                                  → (前端发送批准到 Skill Server)
T15   permission.replied                        → { type:"permission.reply", permissionId:"p_1", response:"once" }

── 工具执行 ──
T16   tool part (bash, pending)                 → { type:"tool.update", partId:"prt_5", toolName:"bash", status:"pending" }
T17   tool part (bash, running)                 → { type:"tool.update", partId:"prt_5", status:"running", title:"npx create-vite" }
T18   tool part (bash, completed)               → { type:"tool.update", partId:"prt_5", status:"completed",
                                                     output:"Done. Now run:\n  cd my-app\n  npm install" }
── 完成 ──
T19   text part (完整文本)                       → { type:"text.done", partId:"prt_6", content:"项目创建成功！" }
T20   step-finish                               → { type:"step.done", tokens:{input:5000,...}, cost:0.01, reason:"stop" }
T21   session.status {idle}                     → { type:"session.status", status:"idle" }
T22   session.updated (title)                   → { type:"session.title", title:"Create React Vite project" }
```

---

## 五、Skill Server 拆解伪代码

```java
void handleToolEvent(String sessionId, JsonNode event) {
    String eventType = event.path("type").asText();
    JsonNode props = event.get("properties");

    switch (eventType) {
        case "message.part.updated" -> {
            JsonNode part = props.get("part");
            String delta = props.path("delta").asText(null);
            String partType = part.path("type").asText();
            String partId = part.path("id").asText();

            switch (partType) {
                case "text" -> {
                    if (delta != null)
                        broadcast(sessionId, "text.delta", Map.of("partId", partId, "content", delta));
                    else
                        broadcast(sessionId, "text.done", Map.of("partId", partId, "content", part.path("text").asText()));
                }
                case "reasoning" -> {
                    if (delta != null)
                        broadcast(sessionId, "thinking.delta", Map.of("partId", partId, "content", delta));
                    else
                        broadcast(sessionId, "thinking.done", Map.of("partId", partId, "content", part.path("text").asText()));
                }
                case "tool" -> {
                    String toolName = part.path("tool").asText();
                    JsonNode state = part.get("state");
                    String status = state.path("status").asText();

                    if ("question".equals(toolName) && "running".equals(status)) {
                        JsonNode input = state.get("input");
                        broadcast(sessionId, "question", Map.of(
                            "partId", partId,
                            "toolCallId", part.path("callID").asText(),
                            "header", input.path("header").asText(""),
                            "question", input.path("question").asText(""),
                            "options", input.get("options")
                        ));
                    } else {
                        broadcast(sessionId, "tool.update", Map.of(
                            "partId", partId,
                            "toolName", toolName,
                            "toolCallId", part.path("callID").asText(),
                            "status", status,
                            "input", state.get("input"),
                            "output", state.path("output").asText(null),
                            "error", state.path("error").asText(null),
                            "title", state.path("title").asText(null)
                        ));
                    }
                }
                case "step-start" -> broadcast(sessionId, "step.start", Map.of());
                case "step-finish" -> broadcast(sessionId, "step.done", Map.of(
                    "tokens", part.get("tokens"), "cost", part.path("cost").asDouble(), "reason", part.path("reason").asText()));
                case "file" -> broadcast(sessionId, "file", Map.of(
                    "partId", partId, "mime", part.path("mime").asText(), "filename", part.path("filename").asText(), "url", part.path("url").asText()));
                default -> {} // snapshot/patch/agent/retry/compaction — 暂不处理
            }
        }
        case "message.updated" -> {
            if (props.path("info").has("finish"))
                broadcast(sessionId, "step.done", Map.of());
        }
        case "session.status" -> broadcast(sessionId, "session.status", Map.of("status", props.path("status").path("type").asText()));
        case "session.idle" -> broadcast(sessionId, "session.status", Map.of("status", "idle"));
        case "session.updated" -> broadcast(sessionId, "session.title", Map.of("title", props.path("info").path("title").asText()));
        case "session.error" -> broadcast(sessionId, "session.error", Map.of("error", props.path("error").toString()));
        case "permission.updated" -> {
            broadcast(sessionId, "permission.ask", Map.of(
                "permissionId", props.path("id").asText(), "permType", props.path("type").asText(),
                "title", props.path("title").asText(), "metadata", props.get("metadata")));
        }
        default -> {} // session.diff, file.edited, todo.updated 等 — 暂不处理
    }
}
```

---

## 六、前端处理逻辑

```typescript
function handleStreamMessage(msg: StreamMessage) {
  switch (msg.type) {
    case 'text.delta':
      // 找到或创建 partId 对应的文本块，追加 content
      appendToBlock(msg.partId, msg.content)
      break

    case 'text.done':
      // 标记文本块完成
      finalizeBlock(msg.partId, msg.content)
      break

    case 'thinking.delta':
      // 追加到折叠的思维链区域
      appendToThinking(msg.partId, msg.content)
      break

    case 'tool.update':
      // 更新工具卡片：显示工具名、状态、输入/输出
      updateToolCard(msg.partId, msg.toolName, msg.status, msg.output)
      break

    case 'question':
      // 渲染问答 UI：标题 + 问题 + 选项按钮 + 自由输入
      showQuestion(msg.partId, msg.toolCallId, msg.header, msg.question, msg.options)
      break

    case 'permission.ask':
      // 弹出权限审批弹窗
      showPermissionDialog(msg.permissionId, msg.title, msg.metadata)
      break

    case 'step.done':
      // 显示 token 统计
      showUsageStats(msg.tokens, msg.cost)
      break

    case 'session.status':
      // 更新状态指示器
      updateStatus(msg.status)  // "busy" → 显示思考动画, "idle" → 隐藏
      break

    case 'session.title':
      updateTitle(msg.title)
      break
  }
}
```
