# 测试用例文档

> 版本: 1.0 | 日期: 2026-03-07

## 一、后端单元测试

### 1.1 OpenCodeEventTranslator 测试

| 用例 ID | 场景                           | 输入                                                        | 期望输出                                                        |
| ------- | ------------------------------ | ----------------------------------------------------------- | --------------------------------------------------------------- |
| UT-T01  | text Part 翻译                 | `message.part.updated` + `part.type=text` + state=pending   | `StreamMessage(type=text.delta, content=xxx)`                   |
| UT-T02  | text Part 完成                 | `message.part.updated` + `part.type=text` + state=completed | `StreamMessage(type=text.done, content=xxx)`                    |
| UT-T03  | reasoning Part 翻译            | `part.type=reasoning`                                       | `StreamMessage(type=thinking.delta)`                            |
| UT-T04  | tool-invocation Part pending   | `part.type=tool-invocation` + state=pending                 | `StreamMessage(type=tool.update, status=pending)`               |
| UT-T05  | tool-invocation Part running   | state=running                                               | `StreamMessage(type=tool.update, status=running)`               |
| UT-T06  | tool-invocation Part completed | state=completed + output                                    | `StreamMessage(type=tool.update, status=completed, output=xxx)` |
| UT-T07  | tool-invocation Part error     | state=error                                                 | `StreamMessage(type=tool.update, status=error)`                 |
| UT-T08  | question 事件                  | 有 header + question + options                              | `StreamMessage(type=question, question=xxx, options=[...])`     |
| UT-T09  | permission 事件                | `permission.updated` + status=pending                       | `StreamMessage(type=permission.ask)`                            |
| UT-T10  | session.status 事件            | `session.status` + status=idle                              | `StreamMessage(type=session.status, sessionStatus=idle)`        |
| UT-T11  | 未知 Part type                 | `part.type=unknown_abc`                                     | 返回 `null`（忽略）                                             |
| UT-T12  | 空 event 节点                  | `event = null`                                              | 返回 `null`                                                     |

### 1.2 StreamBufferService 测试

| 用例 ID | 场景                     | 操作                                                   | 期望结果                               |
| ------- | ------------------------ | ------------------------------------------------------ | -------------------------------------- |
| UT-B01  | text.delta 追加          | accumulate 3 次 text.delta                             | Redis 中 content = "abc"               |
| UT-B02  | text.done 清除           | text.delta × N + text.done                             | Redis 对应 part key 被删除             |
| UT-B03  | tool.update 覆盖         | accumulate tool.update(pending) → tool.update(running) | Redis 中 status=running                |
| UT-B04  | session.status=idle 全清 | 有多个 part → session.status=idle                      | 所有 key 被删除                        |
| UT-B05  | getStreamingParts        | 存入 3 个 part                                         | 返回 3 个有序 StreamMessage            |
| UT-B06  | isSessionStreaming       | text.delta 后                                          | 返回 true                              |
| UT-B07  | clearSession             | 手动清除                                               | isSessionStreaming = false, parts = [] |
| UT-B08  | TTL 验证                 | 存入 part                                              | key 有 1h TTL                          |

### 1.3 MessagePersistenceService 测试

| 用例 ID | 场景                         | 操作                                          | 期望结果                            |
| ------- | ---------------------------- | --------------------------------------------- | ----------------------------------- |
| UT-P01  | text.done 持久化             | persistIfFinal(text.done)                     | skill_message_part 表有一条记录     |
| UT-P02  | tool.update completed 持久化 | persistIfFinal(tool.update, status=completed) | 写入 tool_name + tool_output        |
| UT-P03  | text.delta 不持久化          | persistIfFinal(text.delta)                    | 无 DB 写入                          |
| UT-P04  | step.done 更新统计           | persistIfFinal(step.done, tokens, cost)       | skill_message 的 tokens/cost 被更新 |
| UT-P05  | session.status=idle 标记完成 | persistIfFinal(session.status=idle)           | skill_message.finished = true       |

---

## 二、前端单元测试

### 2.1 StreamAssembler 测试

| 用例 ID | 场景                  | 操作                                                         | 期望结果                                      |
| ------- | --------------------- | ------------------------------------------------------------ | --------------------------------------------- |
| FT-A01  | text.delta 累积       | handleMessage × 3 (text.delta, content="a","b","c")          | getText() = "abc"，getParts() 有1个 text part |
| FT-A02  | thinking.delta 累积   | handleMessage (thinking.delta)                               | getParts() 有1个 thinking part                |
| FT-A03  | tool.update 创建/更新 | handleMessage(tool.update, pending) → (tool.update, running) | getParts() 有1个 tool part, status=running    |
| FT-A04  | question 创建         | handleMessage(question, question="xxx")                      | getParts() 有1个 question part                |
| FT-A05  | 多 Part 并行          | text.delta + tool.update + thinking.delta                    | getParts() 有3个不同type的part                |
| FT-A06  | reset 清空            | 有数据后 reset()                                             | getText()="" , getParts()=[]                  |
| FT-A07  | complete 标记         | complete()                                                   | 所有 part 的 isStreaming = false              |

### 2.2 useSkillStream 测试

| 用例 ID | 场景                 | 模拟                         | 期望结果                                            |
| ------- | -------------------- | ---------------------------- | --------------------------------------------------- |
| FT-H01  | text.delta 消息      | 3 条 text.delta              | messages 末尾有1条 assistant 消息，content 拼接正确 |
| FT-H02  | session.status=idle  | text.delta + session.status  | isStreaming = false, message.isStreaming = false    |
| FT-H03  | error 消息           | type=error 消息              | error 不为 null, isStreaming = false                |
| FT-H04  | agent.online/offline | agent.online → agent.offline | agentStatus 变化                                    |
| FT-H05  | streaming (resume)   | type=streaming, parts=[...]  | 暂存为待实现                                        |

### 2.3 UI 组件测试

#### ToolCard

| 用例 ID | 场景           | Props                         | 期望                   |
| ------- | -------------- | ----------------------------- | ---------------------- |
| FT-TC01 | Pending 状态   | `part.toolStatus="pending"`   | 显示 "⏳", 标题含工具名 |
| FT-TC02 | Running 状态   | `part.toolStatus="running"`   | 显示 "⚙️", 蓝色左边框   |
| FT-TC03 | Completed 状态 | `part.toolStatus="completed"` | 显示 "✅", 绿色左边框   |
| FT-TC04 | Error 状态     | `part.toolStatus="error"`     | 显示 "❌", 红色左边框   |
| FT-TC05 | 折叠/展开      | 点击 header                   | body 显示/隐藏         |
| FT-TC06 | 有输出显示     | `part.toolOutput="result"`    | 输出区域可见           |

#### ThinkingBlock

| 用例 ID | 场景          | Props                    | 期望                  |
| ------- | ------------- | ------------------------ | --------------------- |
| FT-TB01 | Streaming 中  | `part.isStreaming=true`  | 显示 "思考中..." 动画 |
| FT-TB02 | 完成后        | `part.isStreaming=false` | 无动画标记            |
| FT-TB03 | 折叠/展开     | 点击 header              | 内容区显示/隐藏       |
| FT-TB04 | Markdown 渲染 | content 含 `**bold**`    | 渲染为粗体            |

#### QuestionCard

| 用例 ID | 场景         | Props                    | 期望                          |
| ------- | ------------ | ------------------------ | ----------------------------- |
| FT-QC01 | 显示问题     | question="xxx"           | 问题文本可见                  |
| FT-QC02 | 选项点击     | options=["A","B"], 点击A | onAnswer 回调被触发，参数="A" |
| FT-QC03 | 自由输入提交 | 输入文本 + 点击提交      | onAnswer 回调被触发           |
| FT-QC04 | 已回答状态   | 回答后                   | 按钮禁用，显示 "已回答"       |
| FT-QC05 | 无选项       | options=[] 或 undefined  | 只显示自由输入                |

#### PermissionCard

| 用例 ID | 场景         | Props                | 期望                  |
| ------- | ------------ | -------------------- | --------------------- |
| FT-PC01 | 显示权限申请 | permType="file_edit" | 显示类型标签和描述    |
| FT-PC02 | 允许按钮     | 点击"允许"           | onDecision(id, true)  |
| FT-PC03 | 拒绝按钮     | 点击"拒绝"           | onDecision(id, false) |
| FT-PC04 | 已决策状态   | 已点击后             | 按钮禁用，显示结果    |

---

## 三、集成测试

### 3.1 端到端流式输出

| 用例 ID | 场景       | 步骤                    | 期望结果                                  |
| ------- | ---------- | ----------------------- | ----------------------------------------- |
| IT-01   | 基本文本流 | 发送消息 → 等待 AI 回复 | 前端实时显示打字效果，完成后光标消失      |
| IT-02   | 思维链流   | 发送需深度推理的问题    | ThinkingBlock 实时更新，正文区同步显示    |
| IT-03   | 工具调用   | 触发 bash/edit 命令     | ToolCard 从 pending → running → completed |
| IT-04   | 多工具串行 | 触发多个工具            | 多个 ToolCard 依次出现，各自独立状态      |
| IT-05   | 混合内容   | 文本 + 工具 + 文本      | Parts 按顺序渲染，text→tool→text          |

### 3.2 断线重连

| 用例 ID | 场景                   | 步骤                                       | 期望结果                                  |
| ------- | ---------------------- | ------------------------------------------ | ----------------------------------------- |
| IT-R01  | 流式中切走再切回       | 发消息 → 等 AI 开始输出 → 切走 → 3s 后切回 | 看到所有已生成内容 + 继续实时流           |
| IT-R02  | 流式中切走等完成后切回 | 发消息 → 切走 → 等 AI 完成 → 切回          | Redis 已清空，DB 有完整记录，显示完整消息 |
| IT-R03  | 网络闪断自动重连       | 模拟 WS 断开                               | 自动重连 → 发 resume → 恢复内容           |
| IT-R04  | 超时后切回 (>1h)       | Redis TTL 过期                             | DB 有完整数据，正常显示历史               |

### 3.3 持久化验证

| 用例 ID | 场景            | 步骤                  | 期望结果                                   |
| ------- | --------------- | --------------------- | ------------------------------------------ |
| IT-P01  | 完整对话持久化  | 完成一次完整对话      | skill_message 表有记录，finished=true      |
| IT-P02  | Part 级别持久化 | 含 text + tool 的回复 | skill_message_part 表有对应 text/tool 记录 |
| IT-P03  | Token 统计      | step.done 事件        | skill_message.total_tokens 和 cost 有值    |
| IT-P04  | 历史加载        | 重新打开会话          | 显示完整历史含 Parts 结构                  |

### 3.4 异常 Case

| 用例 ID | 场景              | 步骤                               | 期望结果                           |
| ------- | ----------------- | ---------------------------------- | ---------------------------------- |
| IT-E01  | OpenCode 连接中断 | 模拟 agent 离线                    | 前端显示 agent.offline 状态        |
| IT-E02  | 工具执行失败      | 触发一个会报错的命令               | ToolCard 显示 error 状态，红色边框 |
| IT-E03  | 未知 Part type    | 后端收到新版本 OpenCode 的未知事件 | 静默忽略，不崩溃                   |
| IT-E04  | WS 消息格式错误   | 前端收到非 JSON 消息               | 静默忽略，不崩溃                   |
