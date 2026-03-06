# 任务进展文档

> 最后更新: 2026-03-07 00:25

## 总体进度

```
Phase 1: 后端协议转换     ██████████ 100% ✅  (2026-03-06 完成)
Phase 2: 后端持久化改造   ██████████ 100% ✅  (2026-03-06 完成)
Phase 3: 后端 Redis 累积器 ██████████ 100% ✅  (2026-03-07 完成)
Phase 4: 前端协议适配     ██████████ 100% ✅  (2026-03-07 完成)
Phase 5: 集成测试与验证   ░░░░░░░░░░   0% ⬜  待执行
```

---

## Phase 1: 后端协议转换 ✅

**目标：** 将 OpenCode 原始事件翻译为语义化 StreamMessage。

### 改动文件

| 操作     | 文件                           | 说明                                                                      |
| -------- | ------------------------------ | ------------------------------------------------------------------------- |
| **新建** | `StreamMessage.java`           | 17 种 type 的 DTO，含 text/tool/question/permission/step/session 全部字段 |
| **新建** | `OpenCodeEventTranslator.java` | 事件翻译器：switch `message.part.updated` 的 12 种 Part type + 顶层事件   |
| **改造** | `GatewayRelayService.java`     | `handleToolEvent` 调用 translator → broadcastStreamMessage                |
| **改造** | `SkillStreamHandler.java`      | 新增 `pushStreamMessage(sessionId, StreamMessage)` 方法                   |

### 验证结果

- ✅ Maven 编译通过 (exit code 0)

---

## Phase 2: 后端持久化改造 ✅

**目标：** 消息 Part 级别持久化到 MySQL。

### 改动文件

| 操作     | 文件                              | 说明                                                     |
| -------- | --------------------------------- | -------------------------------------------------------- |
| **新建** | `V2__message_parts.sql`           | 扩展 `skill_message` 表 + 新建 `skill_message_part` 表   |
| **新建** | `SkillMessagePart.java`           | Part 实体：partId/type/content/toolName/toolStatus/...   |
| **新建** | `SkillMessagePartRepository.java` | MyBatis Repository 接口                                  |
| **新建** | `SkillMessagePartMapper.xml`      | SQL 映射（upsert / findByMessageId）                     |
| **新建** | `MessagePersistenceService.java`  | 持久化服务：persistIfFinal / 按 type 判断是否写 DB       |
| **改造** | `SkillMessageService.java`        | 新增 updateMessageStats / markMessageFinished            |
| **改造** | `SkillMessageMapper.xml`          | 新增 updateStats / markFinished SQL                      |
| **改造** | `GatewayRelayService.java`        | handleToolEvent/handleToolDone 中调用 persistenceService |

### 数据库变更

```sql
-- skill_message 扩展字段
ALTER TABLE skill_message ADD COLUMN message_id VARCHAR(64);
ALTER TABLE skill_message ADD COLUMN finished BOOLEAN DEFAULT FALSE;
ALTER TABLE skill_message ADD COLUMN total_tokens INT;
ALTER TABLE skill_message ADD COLUMN cost DOUBLE;

-- 新建 skill_message_part 表
CREATE TABLE skill_message_part (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    part_id VARCHAR(64) NOT NULL,
    part_type VARCHAR(32) NOT NULL,
    content TEXT,
    tool_name VARCHAR(128),
    tool_status VARCHAR(32),
    tool_input TEXT,
    tool_output TEXT,
    seq_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 验证结果

- ✅ Maven 编译通过 (exit code 0)

---

## Phase 3: 后端 Redis 累积器 ✅

**目标：** Redis 实时缓冲 + WebSocket resume 接口。

### 改动文件

| 操作     | 文件                       | 说明                                                                       |
| -------- | -------------------------- | -------------------------------------------------------------------------- |
| **新建** | `StreamBufferService.java` | Redis 累积器：accumulate/getStreamingParts/clearPart/clearSession          |
| **改造** | `GatewayRelayService.java` | handleToolEvent/handleToolDone 中调用 bufferService.accumulate             |
| **改造** | `SkillStreamHandler.java`  | handleTextMessage 支持 `{action:"resume"}` → 查 Redis 返回 streaming parts |

### Redis Key 设计

| Key                                | Value               | TTL |
| ---------------------------------- | ------------------- | --- |
| `stream:{sessionId}:status`        | `{"status":"busy"}` | 1h  |
| `stream:{sessionId}:part:{partId}` | Part JSON           | 1h  |
| `stream:{sessionId}:parts_order`   | List of partId      | 1h  |

### 累积规则

| StreamMessage type                            | Redis 操作     |
| --------------------------------------------- | -------------- |
| `text.delta` / `thinking.delta`               | APPEND content |
| `text.done` / `thinking.done`                 | DEL (已持久化) |
| `tool.update` / `question` / `permission.ask` | SET 覆盖       |
| `step.done` / `session.status=idle`           | DEL 全部       |

### 验证结果

- ✅ Maven 编译通过 (exit code 0)

---

## Phase 4: 前端协议适配 ✅

**目标：** 前端适配新的 StreamMessage 协议，渲染结构化 UI 组件。

### 改动文件

| 操作     | 文件                     | 说明                                                                       |
| -------- | ------------------------ | -------------------------------------------------------------------------- |
| **重写** | `types.ts`               | StreamMessageType（17种）、StreamMessage、MessagePart（6种部件）           |
| **重写** | `StreamAssembler.ts`     | 多 Part 管理器，按 partId 跟踪 text/thinking/tool/question/permission/file |
| **重写** | `useSkillStream.ts`      | handleStreamMessage switch 处理 17 种 type，驱动 assembler                 |
| **重写** | `MessageBubble.tsx`      | 支持 Parts 渲染：Type→Component 映射                                       |
| **简化** | `OpenCodeEventParser.ts` | 保留分类/状态工具函数（后端已处理翻译）                                    |
| **适配** | `ToolUseRenderer.ts`     | 改用 MessagePart 代替旧 ParsedEvent                                        |
| **新建** | `ToolCard.tsx`           | 🔧 工具调用卡片：状态色指示 + 可折叠输入/输出                               |
| **新建** | `ThinkingBlock.tsx`      | 💭 思维链：可折叠显示 + streaming 动画                                      |
| **新建** | `QuestionCard.tsx`       | ❓ AI 提问卡片：选项按钮 + 自由输入 + 提交                                  |
| **新建** | `PermissionCard.tsx`     | 🔐 权限审批：类型标签 + 允许/拒绝按钮                                       |
| **扩展** | `index.css`              | 新增 460+ 行组件样式（暗色主题）                                           |

### 验证结果

- ✅ TypeScript 编译通过 (tsc --noEmit, 0 errors)

---

## Phase 5: 集成测试与验证 ⬜

**状态：** 待执行

**计划内容：**
- 端到端联调：OpenCode → Gateway → Skill Server → Frontend
- 验证所有 17 种 StreamMessage type 的渲染效果
- 验证断线重连 resume 流程
- 验证 MySQL 持久化数据完整性
