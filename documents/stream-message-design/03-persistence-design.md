# Skill Server 消息持久化方案

## 一、现有问题

```
现在：每个 OpenCode event → 一条 skill_message (ASSISTANT)
问题：
1. 一次对话产生几十条 delta 记录（text.delta × N, tool.update × N...）
2. content 存的是原始 JSON，前端回看时无法渲染
3. 所有 Part 混在一起，丢失了结构（text/reasoning/tool 的先后顺序）
4. role=ASSISTANT 无法区分文本回复 vs 工具调用 vs 思维链
```

## 二、设计方案：消息 + 部件 两层模型

```
skill_message  (一条用户/AI 消息 = 一行)
  └── skill_message_part  (消息内的每个部件)
        ├── TextPart:     AI 文本回复（最终完整版）
        ├── ReasoningPart: 思维链
        ├── ToolPart:     工具调用 + 结果
        ├── FilePart:     文件附件
        └── ...
```

**核心思路：**
- **消息级别**：一个用户发送 + 一个 AI 回复 = 2 条 `skill_message`
- **Part 级别**：AI 回复内的每个 Part（text/reasoning/tool...）= 1 条 `skill_message_part`
- **Delta 不存**：流式 delta 只广播到 WebSocket，不持久化。 只存最终完整状态
- **原始事件选存**：`raw_event` 字段可选存储原始 JSON（调试用）

## 三、新表结构

### 3.1 skill_message（改造）

```sql
-- 保持现有表结构不变，content 字段存"纯文本摘要"用于搜索和预览
ALTER TABLE skill_message ADD COLUMN message_id VARCHAR(128) COMMENT 'OpenCode messageID';
ALTER TABLE skill_message ADD COLUMN finished TINYINT(1) DEFAULT 0 COMMENT '消息是否完成';
ALTER TABLE skill_message ADD COLUMN tokens_input INT COMMENT '输入 token';
ALTER TABLE skill_message ADD COLUMN tokens_output INT COMMENT '输出 token';
ALTER TABLE skill_message ADD COLUMN cost DECIMAL(10,6) COMMENT '费用';
```

### 3.2 skill_message_part（新建）

```sql
CREATE TABLE skill_message_part (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id   BIGINT NOT NULL,              -- FK → skill_message.id
  session_id   BIGINT NOT NULL,              -- 冗余，方便查询
  part_id      VARCHAR(128),                 -- OpenCode Part ID (prt_xxx)
  seq          INT NOT NULL,                 -- Part 在消息内的顺序
  part_type    VARCHAR(30) NOT NULL,         -- text/reasoning/tool/file/step-start/step-finish/...

  -- 文本内容（text/reasoning 存最终完整文本）
  content      MEDIUMTEXT,

  -- 工具相关（tool Part 专用）
  tool_name    VARCHAR(50),                  -- bash/edit/question/...
  tool_call_id VARCHAR(128),                 -- OpenCode callID
  tool_status  VARCHAR(20),                  -- pending/running/completed/error
  tool_input   JSON,                         -- 工具输入参数
  tool_output  MEDIUMTEXT,                   -- 工具输出
  tool_title   VARCHAR(200),                 -- 工具执行标题

  -- 文件相关（file Part 专用）
  file_mime    VARCHAR(100),
  file_name    VARCHAR(200),
  file_url     VARCHAR(500),

  -- Token 统计（step-finish 专用）
  tokens       JSON,                         -- {input, output, reasoning, cache}
  cost         DECIMAL(10,6),

  -- 通用
  raw_event    JSON,                         -- 可选存原始 OpenCode 事件
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,

  INDEX idx_message    (message_id),
  INDEX idx_session    (session_id),
  INDEX idx_part_id    (part_id)
);
```

## 四、存储策略：什么存、什么不存

| StreamMessage type        |  持久化？  | 存入位置                        | 说明                     |
| ------------------------- | :--------: | ------------------------------- | ------------------------ |
| `text.delta`              | **❌ 不存** | —                               | 流式增量，只走 WebSocket |
| `text.done`               |   **✅**    | part (type=text)                | 存最终完整文本           |
| `thinking.delta`          |   **❌**    | —                               | 流式增量                 |
| `thinking.done`           |   **✅**    | part (type=reasoning)           | 存完整思考文本           |
| `tool.update` (pending)   |   **❌**    | —                               | 中间状态                 |
| `tool.update` (running)   |   **❌**    | —                               | 中间状态                 |
| `tool.update` (completed) |   **✅**    | part (type=tool)                | 存最终输入+输出          |
| `tool.update` (error)     |   **✅**    | part (type=tool)                | 存错误信息               |
| `question`                |   **✅**    | part (type=tool, tool=question) | 存问题和选项             |
| `question` 用户回答       |   **✅**    | part (tool_output=回答)         | 更新 tool_output         |
| `file`                    |   **✅**    | part (type=file)                | 存文件引用               |
| `step.start`              |   **❌**    | —                               | 临时状态                 |
| `step.done`               |   **✅**    | message (tokens/cost)           | 更新消息级 token 统计    |
| `session.status`          |   **❌**    | —                               | 临时状态                 |
| `session.title`           |   **✅**    | skill_session.title             | 更新会话标题             |
| `permission.ask`          |   **✅**    | part (type=permission)          | 存权限记录               |
| `permission.reply`        |   **✅**    | 更新 part.tool_status           | 更新审批结果             |

**简单记忆：存"最终态"，不存"流式中间态"**

## 五、持久化时机（Skill Server 伪代码）

```java
void handleToolEvent(String sessionId, JsonNode event) {
    String eventType = event.path("type").asText();
    JsonNode props = event.get("properties");

    // ══════ 先广播（实时推送） ══════
    StreamMessage streamMsg = translateToStreamMessage(event);  // 拆解为前端协议
    broadcastToSession(sessionId, streamMsg);                   // 推 WebSocket

    // ══════ 再持久化（只存最终态）══════
    switch (eventType) {
        case "message.part.updated" -> {
            JsonNode part = props.get("part");
            String partType = part.path("type").asText();
            String partId = part.path("id").asText();
            String delta = props.path("delta").asText(null);

            switch (partType) {
                case "text" -> {
                    if (delta == null) {
                        // text.done — 存完整文本
                        upsertPart(sessionId, partId, "text", part.path("text").asText());
                    }
                    // text.delta — 不存
                }
                case "reasoning" -> {
                    if (delta == null) {
                        upsertPart(sessionId, partId, "reasoning", part.path("text").asText());
                    }
                }
                case "tool" -> {
                    String status = part.path("state").path("status").asText();
                    if ("completed".equals(status) || "error".equals(status)) {
                        // 工具完成或出错 — 存最终态
                        upsertToolPart(sessionId, partId, part);
                    } else if ("running".equals(status) && "question".equals(part.path("tool").asText())) {
                        // question running — 存问题（等待用户回答，回答后更新 output）
                        upsertToolPart(sessionId, partId, part);
                    }
                }
                case "step-finish" -> {
                    // 更新消息级统计
                    updateMessageTokens(sessionId, part.get("tokens"), part.path("cost").asDouble());
                }
                case "file" -> {
                    upsertFilePart(sessionId, partId, part);
                }
            }
        }
        case "session.updated" -> sessionService.updateTitle(sessionId, props.path("info").path("title").asText());
        case "permission.updated" -> upsertPermissionPart(sessionId, props);
    }
}

/** Upsert: 根据 partId 插入或更新 */
void upsertPart(String sessionId, String partId, String partType, String content) {
    var existing = partRepository.findByPartId(partId);
    if (existing != null) {
        existing.setContent(content);
        existing.setUpdatedAt(LocalDateTime.now());
        partRepository.update(existing);
    } else {
        int nextSeq = partRepository.findMaxSeqByMessageId(currentMessageId) + 1;
        partRepository.insert(SkillMessagePart.builder()
            .messageId(currentMessageId)
            .sessionId(Long.valueOf(sessionId))
            .partId(partId)
            .seq(nextSeq)
            .partType(partType)
            .content(content)
            .build());
    }
}
```

## 六、前端获取历史记录 API

```
GET /api/sessions/{sessionId}/messages?page=0&size=20

Response:
{
  "data": [
    {
      "id": 1, "seq": 1, "role": "USER",
      "content": "帮我创建一个 React 项目",
      "parts": []
    },
    {
      "id": 2, "seq": 2, "role": "ASSISTANT",
      "content": "好的，我来帮你创建项目...",  // 纯文本摘要
      "tokensInput": 5000, "tokensOutput": 200, "cost": 0.01,
      "parts": [
        { "seq": 1, "partType": "reasoning",
          "content": "用户想创建 React 项目..." },
        { "seq": 2, "partType": "text",
          "content": "好的，我来帮你创建一个 React 项目。\n\n首先让我确认..." },
        { "seq": 3, "partType": "tool", "toolName": "question",
          "toolStatus": "completed",
          "toolInput": {"header":"项目配置","question":"选择模板","options":["Vite","CRA"]},
          "toolOutput": "Vite" },
        { "seq": 4, "partType": "tool", "toolName": "bash",
          "toolStatus": "completed", "toolTitle": "npx create-vite",
          "toolInput": {"command": "npx create-vite my-app"},
          "toolOutput": "Done. Now run:\n  cd my-app\n  npm install" },
        { "seq": 5, "partType": "text",
          "content": "项目创建成功！你可以..." }
      ]
    }
  ]
}
```

前端回看历史时，按 `parts` 数组的 `seq` 顺序渲染每个块，效果与实时流式接收完全一致。

## 七、迁移脚本

```sql
-- V2__message_parts.sql
CREATE TABLE skill_message_part (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id   BIGINT NOT NULL,
  session_id   BIGINT NOT NULL,
  part_id      VARCHAR(128),
  seq          INT NOT NULL,
  part_type    VARCHAR(30) NOT NULL,
  content      MEDIUMTEXT,
  tool_name    VARCHAR(50),
  tool_call_id VARCHAR(128),
  tool_status  VARCHAR(20),
  tool_input   JSON,
  tool_output  MEDIUMTEXT,
  tool_title   VARCHAR(200),
  file_mime    VARCHAR(100),
  file_name    VARCHAR(200),
  file_url     VARCHAR(500),
  tokens       JSON,
  cost         DECIMAL(10,6),
  raw_event    JSON,
  created_at   DATETIME NOT NULL DEFAULT NOW(),
  updated_at   DATETIME NOT NULL DEFAULT NOW(),
  INDEX idx_message (message_id),
  INDEX idx_session (session_id),
  INDEX idx_part_id (part_id)
);

ALTER TABLE skill_message ADD COLUMN message_id VARCHAR(128) COMMENT 'OpenCode messageID';
ALTER TABLE skill_message ADD COLUMN finished TINYINT(1) DEFAULT 0;
ALTER TABLE skill_message ADD COLUMN tokens_input INT;
ALTER TABLE skill_message ADD COLUMN tokens_output INT;
ALTER TABLE skill_message ADD COLUMN cost DECIMAL(10,6);
```
