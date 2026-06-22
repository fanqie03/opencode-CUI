# Skill-Server REST API 文档

> skill-server 对外 HTTP 接口完整规范，覆盖 6 个 Controller 共 16 个端点。

---

## 通用约定

### 响应信封

所有接口返回统一 JSON 结构 `ApiResponse<T>`（`@JsonInclude(NON_NULL)`）：

```json
{
  "code": 0,
  "errormsg": null,
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | `0` = 成功，非零 = 错误 |
| `errormsg` | string | 错误描述（成功时为 null） |
| `data` | T | 响应载荷（失败时为 null） |

### 认证方式

| 路径前缀 | 认证方式 | 说明 |
|----------|----------|------|
| `/api/skill/**` | Cookie `userId` | 由 `SessionAccessControlService.requireUserId()` 解析；缺失则返回 400 |
| `/api/inbound/**` | Bearer Token | `Authorization: Bearer <token>`，token 由 `skill.im.inbound-token` 配置 |
| `/api/external/**` | Bearer Token | 同上，共用 inbound token |
| `/api/admin/**` | 无 | 无认证拦截器 |

### 全局错误码

| HTTP Status | code | 说明 |
|-------------|------|------|
| 200 | 0 | 成功 |
| 200 | 400 | 参数校验失败（业务层返回 200 + 非零 code） |
| 200 | 403 | 权限不足 |
| 200 | 404 | 资源不存在 |
| 200 | 409 | 状态冲突（如会话已关闭） |
| 200 | 500 | 服务端内部错误 |
| 400 | — | 框架层参数绑定失败 |
| 401 | — | IM/External 接口 Token 校验失败 |
| 500 | — | 未捕获异常 |

### 全局 Headers

| Header | 说明 |
|--------|------|
| `X-Trace-Id` | 请求级 trace ID（可选，未传则自动生成） |
| `Cookie: userId=<value>` | 用户身份（skill 接口使用） |
| `Authorization: Bearer <token>` | IM/External 接口鉴权 |

---

## 1. 会话接口 — SkillSessionController

**Base**: `/api/skill/sessions` · **Auth**: Cookie `userId`

### 1.1 创建会话

```
POST /api/skill/sessions
```

**Request Body** (`CreateSessionRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ak` | string | 否 | Agent 应用密钥 |
| `title` | string | 否 | 会话标题（缺省由 AI 自动生成） |
| `businessSessionDomain` | string | 否 | 业务域（默认 `miniapp`） |
| `businessSessionType` | string | 否 | 会话类型：`group` / `direct`（IM 场景） |
| `businessSessionId` | string | 否 | 业务侧会话 ID |
| `assistantAccount` | string | 否 | 助手账号（IM 场景） |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": {
    "welinkSessionId": "1234567890123456789",
    "userId": "user001",
    "ak": "agent_xxx",
    "toolSessionId": null,
    "title": null,
    "status": "ACTIVE",
    "businessSessionDomain": "miniapp",
    "businessSessionType": null,
    "businessSessionId": null,
    "assistantAccount": null,
    "createdAt": "2026-06-10T10:30:00",
    "updatedAt": "2026-06-10T10:30:00"
  }
}
```

### 1.2 查询会话列表

```
GET /api/skill/sessions?status=ACTIVE&ak=agent_xxx&page=0&size=20
```

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `status` | string | 否 | — | 状态过滤 |
| `ak` | string | 否 | — | Agent AK 过滤 |
| `businessSessionDomain` | string | 否 | — | 业务域过滤 |
| `businessSessionType` | string | 否 | — | 会话类型过滤 |
| `businessSessionId` | string | 否 | — | 业务会话 ID 过滤 |
| `assistantAccount` | string | 否 | — | 助手账号过滤 |
| `page` | int | 否 | 0 | 页码（0-based） |
| `size` | int | 否 | 20 | 每页条数 |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": {
    "content": [ { "welinkSessionId": "...", ... } ],
    "total": 42,
    "totalPages": 3,
    "page": 0,
    "size": 20
  }
}
```

### 1.3 查询单个会话

```
GET /api/skill/sessions/{id}
```

**Path Variables**: `id` — 会话 ID（welinkSessionId）

**错误**: 400（无效 ID）、403（无权限）、404（不存在）

**成功响应**: 同 1.1 的 `data` 结构

### 1.4 关闭会话

```
DELETE /api/skill/sessions/{id}
```

**Path Variables**: `id` — 会话 ID

> **注意**：当前为 soft close（status → CLOSED，数据保留）。

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": { "status": "closed", "welinkSessionId": "1234567890123456789" }
}
```

### 1.5 中止会话

```
POST /api/skill/sessions/{id}/abort
```

中止正在进行的 AI 操作，发送 `abort_session` 到 Gateway。会话保留可复用。

**错误**: 400（无效 ID）、409（会话已关闭）

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": { "status": "aborted", "welinkSessionId": "1234567890123456789" }
}
```

---

## 2. 消息接口 — SkillMessageController

**Base**: `/api/skill/sessions/{sessionId}` · **Auth**: Cookie `userId`

### 2.1 发送消息

```
POST /api/skill/sessions/{sessionId}/messages
```

**Request Body** (`SendMessageRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | string | **是** | 消息文本内容（非空） |
| `toolCallId` | string | 否 | 工具调用 ID（存在时路由到 `question_reply`） |
| `subagentSessionId` | string | 否 | Subagent 真实 toolSessionId |
| `questionId` | string | 否 | OpenCode question request ID（新版快路径） |
| `businessExtParam` | object | 否 | 业务扩展参数 |

**错误**: 400（content 为空、sessionId 无效）、409（会话已关闭）

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": {
    "id": "msg_001",
    "welinkSessionId": "1234567890123456789",
    "seq": 1,
    "messageSeq": 1,
    "role": "user",
    "content": "帮我写一段代码",
    "contentType": "plain",
    "createdAt": "2026-06-10T10:30:01",
    "meta": null,
    "parts": null
  }
}
```

### 2.2 查询消息历史（分页）

```
GET /api/skill/sessions/{sessionId}/messages?page=0&size=50
```

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `page` | int | 否 | 0 | 页码 |
| `size` | int | 否 | 50 | 每页条数（1 ~ 200） |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": {
    "content": [ { "id": "msg_001", ... }, ... ],
    "total": 100,
    "totalPages": 2,
    "page": 0,
    "size": 50
  }
}
```

### 2.3 查询消息历史（游标）

```
GET /api/skill/sessions/{sessionId}/messages/history?beforeSeq=100&size=50
```

用于 WebSocket 重连后拉取增量消息。以序列号为游标向前翻页。

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `beforeSeq` | int | 否 | — | 游标（消息序号），不传则从最新开始 |
| `size` | int | 否 | 50 | 每页条数（1 ~ 200） |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": {
    "content": [ { "id": "msg_001", ... }, ... ],
    "size": 50,
    "hasMore": true,
    "nextBeforeSeq": 50
  }
}
```

### 2.4 发送到 IM

```
POST /api/skill/sessions/{sessionId}/send-to-im
```

将选定的文本发送到当前会话关联的 IM 聊天。目标和发送人由后端从 `businessSessionId` + cookie `userId` 解析。

**Request Body** (`SendToImRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | string | **是** | 文本内容（最大 4000 字符） |

**错误**: 400（content 为空/超长、sessionId 无效、businessSessionId 格式无效）、403（发送人不匹配）

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": { "success": true }
}
```

### 2.5 回复权限请求

```
POST /api/skill/sessions/{sessionId}/permissions/{permId}
```

**Path Variables**: `permId` — 权限请求 ID

**Request Body** (`PermissionReplyRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `response` | string | **是** | 合法值：`once`、`always`、`reject` |
| `subagentSessionId` | string | 否 | Subagent 真实 toolSessionId |
| `businessExtParam` | object | 否 | 业务扩展参数 |

**错误**: 400（response 为空或无效值、sessionId 无效）、409（会话已关闭）

**成功响应 (200)**: `ApiResponse<Map<String, Object>>`

---

## 3. Agent 查询接口 — AgentQueryController

**Base**: `/api/skill/agents` · **Auth**: Cookie `userId`

### 3.1 查询在线 Agent 列表

```
GET /api/skill/agents
```

Agent 列表代理接口，MiniApp 通过此接口获取在线 Agent，而非直连 Gateway。

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": [
    {
      "ak": "agent_xxx",
      "status": "ONLINE",
      "deviceName": "My PC",
      "os": "Windows 11",
      "toolType": "opencode",
      "toolVersion": "1.2.3",
      "connectedAt": "2026-06-10T09:00:00"
    }
  ]
}
```

---

## 4. IM 入站接口 — ImInboundController

**Base**: `/api/inbound` · **Auth**: Bearer Token

### 4.1 接收 IM 消息

```
POST /api/inbound/messages
```

接收 IM 平台（WeLink）的入站消息。当前仅接受文本消息。

**Request Body** (`ImMessageRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `businessDomain` | string | **是** | 必须为 `"im"` |
| `sessionType` | string | **是** | `"group"` 或 `"direct"` |
| `sessionId` | string | **是** | IM 侧会话 ID |
| `assistantAccount` | string | **是** | 助手账号（用于反查 AK） |
| `senderUserAccount` | string | **是** | 发送者账号 |
| `content` | string | **是** | 消息文本 |
| `msgType` | string | 否 | 消息类型（null/空/`"text"` = 文本，其他拒绝） |
| `imageUrl` | string | 否 | 图片 URL（msgType=image 时） |
| `chatHistory` | array | 否 | 聊天上下文历史 |
| `businessExtParam` | object | 否 | 业务扩展参数 |

**chatHistory 数组元素**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `senderAccount` | string | 发送者账号 |
| `senderName` | string | 发送者显示名 |
| `content` | string | 消息内容 |
| `timestamp` | long | 时间戳（毫秒） |

**校验规则**（按顺序）：
1. body 非 null
2. `businessDomain` = `"im"`
3. `sessionType` = `"group"` 或 `"direct"`
4-7. `sessionId`、`assistantAccount`、`senderUserAccount`、`content` 均非空
8. `msgType` 为 null / 空 / `"text"`

**错误 (400)**:

```json
{ "code": 400, "errormsg": "content is required", "data": null }
```

**成功响应 (200)**（注意：`code=0` 仅表示消息已接收，AI 回复异步推送）：

```json
{ "code": 0, "data": null }
```

---

## 5. External 入站接口 — ExternalInboundController

**Base**: `/api/external` · **Auth**: Bearer Token

### 5.1 外部统一调用

```
POST /api/external/invoke
```

统一外部入站端点。通过 `action` 字段分发到不同的处理逻辑。

**Request Body** (`ExternalInvokeRequest`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | string | **是** | `chat` / `question_reply` / `permission_reply` / `rebuild` |
| `businessDomain` | string | **是** | 业务域（需与 WS source 匹配） |
| `sessionType` | string | **是** | `group` / `direct` |
| `sessionId` | string | **是** | 业务侧会话 ID |
| `assistantAccount` | string | **是** | 助手账号 |
| `senderUserAccount` | string | **是** | 发送者账号 |
| `businessExtParam` | object | 否 | 业务扩展参数 |
| `payload` | object | 否 | action 特定数据 |

**payload 按 action**:

| action | payload 必填字段 | 可选字段 |
|--------|------------------|----------|
| `chat` | `content` (string) | `msgType`、`imageUrl`、`chatHistory` |
| `question_reply` | `content` (string)、`toolCallId` (string) | — |
| `permission_reply` | `permissionId` (string)、`response` (string: `once`/`always`/`reject`) | — |
| `rebuild` | — | — |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": { "welinkSessionId": "1234567890123456789" }
}
```

---

## 6. 系统配置接口 — SysConfigController

**Base**: `/api/admin/configs` · **Auth**: 无

### 6.1 按类型查询配置列表

```
GET /api/admin/configs?type=allowed_slash_commands
```

**Query Parameters**: `type` (string, 必填) — 配置类型

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": [
    {
      "id": 1,
      "configType": "allowed_slash_commands",
      "configKey": "personal_default",
      "configValue": "[\"plan\",\"ask\",\"run\"]",
      "description": "个人助手默认允许的斜杠命令",
      "status": 1,
      "sortOrder": 1,
      "createdAt": "2026-06-01T00:00:00",
      "updatedAt": "2026-06-01T00:00:00"
    }
  ]
}
```

### 6.2 查询单个配置值

```
GET /api/admin/configs/value?type=allowed_slash_commands&key=personal_default
```

供跨服务读取（如 Gateway 查询配置）。

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | **是** | 配置类型 |
| `key` | string | **是** | 配置键 |

**成功响应 (200)**:

```json
{
  "code": 0,
  "data": { "configValue": "[\"plan\",\"ask\",\"run\"]" }
}
```

**未找到时** `configValue` 为 null。

### 6.3 创建配置

```
POST /api/admin/configs
```

**Request Body** (`SysConfig`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `configType` | string | 是 | 配置类型 |
| `configKey` | string | 是 | 配置键 |
| `configValue` | string | 否 | 配置值 |
| `description` | string | 否 | 描述 |
| `status` | int | 否 | `1` = 启用，`0` = 禁用 |
| `sortOrder` | int | 否 | 排序权重 |

**成功响应 (200)**: `{ "code": 0, "data": null }`

### 6.4 更新配置

```
PUT /api/admin/configs/{id}
```

**Path Variables**: `id` (long) — 配置主键

**Request Body**: 同 6.3

**成功响应 (200)**: `{ "code": 0, "data": null }`

### 6.5 删除配置

```
DELETE /api/admin/configs/{id}
```

**Path Variables**: `id` (long) — 配置主键

**成功响应 (200)**: `{ "code": 0, "data": null }`

---

## 附录 A: 公共数据结构

### A.1 SkillSession

| 字段 | 类型 | 说明 |
|------|------|------|
| `welinkSessionId` | string | 会话 ID（Long 序列化为 String，防 JS 精度丢失） |
| `userId` | string | 用户 ID |
| `ak` | string | Agent 应用密钥 |
| `toolSessionId` | string | Agent 侧会话 ID（创建后回填） |
| `title` | string | 会话标题 |
| `status` | string | `ACTIVE` / `IDLE` / `CLOSED` |
| `businessSessionDomain` | string | 业务域名（默认 `miniapp`） |
| `businessSessionType` | string | 会话类型（`group` / `direct`） |
| `businessSessionId` | string | 业务侧会话 ID |
| `assistantAccount` | string | 助手账号 |
| `createdAt` | string | 创建时间 (ISO 8601) |
| `updatedAt` | string | 最后活跃时间 |

### A.2 ProtocolMessageView

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 消息 ID |
| `welinkSessionId` | string | 所属会话 ID |
| `seq` | int | 全局序号 |
| `messageSeq` | int | 消息内部序号 |
| `role` | string | 角色：`user` / `assistant` / `system` / `tool` |
| `content` | string | 文本内容 |
| `contentType` | string | 内容类型：`markdown` / `code` / `plain` |
| `createdAt` | string | 创建时间 (ISO 8601) |
| `meta` | object | 元数据（token 用量、成本、reason 等） |
| `parts` | array | `ProtocolMessagePart[]`，消息分片列表 |

### A.3 ProtocolMessagePart

| 字段 | 类型 | 说明 |
|------|------|------|
| `partId` | string | 分片唯一标识 |
| `partSeq` | int | 分片序号 |
| `type` | string | 分片类型：`text` / `reasoning` / `tool` / `file` / `step-start` / `step-finish` |
| `content` | string | 文本内容（text/reasoning 类型） |
| `toolName` | string | 工具名称 |
| `toolCallId` | string | 工具调用 ID |
| `status` | string | 工具状态：`pending` / `running` / `completed` / `error` |
| `input` | object | 工具输入参数 |
| `output` | string | 工具输出结果 |
| `error` | string | 工具错误信息 |
| `title` | string | 工具标题 |
| `header` | string | 问答头部说明 |
| `question` | string | 问题内容 |
| `questionId` | string | 问题 ID |
| `options` | string[] | 选项列表 |
| `answered` | boolean | 是否已回答 |
| `permissionId` | string | 权限请求 ID |
| `permType` | string | 权限类型 |
| `metadata` | object | 权限元数据 |
| `response` | string | 权限应答 |
| `fileName` | string | 文件名 |
| `fileUrl` | string | 文件 URL |
| `fileMime` | string | 文件 MIME 类型 |
| `subagentSessionId` | string | Subagent 会话 ID |
| `subagentName` | string | Subagent 名称 |

### A.4 PageResult\<T\>

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | T[] | 当前页数据 |
| `total` | long | 总记录数 |
| `totalPages` | int | 总页数 |
| `page` | int | 当前页码（0-based） |
| `size` | int | 每页条数 |

### A.5 MessageHistoryResult\<T\>

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | T[] | 当前页数据 |
| `size` | int | 返回条数 |
| `hasMore` | boolean | 是否有更多数据 |
| `nextBeforeSeq` | int | 下一页游标（作 `beforeSeq` 传入） |

### A.6 AgentSummary

| 字段 | 类型 | 说明 |
|------|------|------|
| `ak` | string | Agent 应用密钥 |
| `status` | string | 在线状态：`ONLINE` |
| `deviceName` | string | 设备名称 |
| `os` | string | 操作系统 |
| `toolType` | string | 工具类型（小写，如 `opencode`） |
| `toolVersion` | string | 工具版本 |
| `connectedAt` | string | 连接时间 |

---

## 附录 B: 端点速查表

| # | 方法 | 路径 | Controller | 认证 | 说明 |
|---|------|------|------------|------|------|
| 1 | POST | `/api/skill/sessions` | SkillSession | Cookie | 创建会话 |
| 2 | GET | `/api/skill/sessions` | SkillSession | Cookie | 会话列表（分页+过滤） |
| 3 | GET | `/api/skill/sessions/{id}` | SkillSession | Cookie | 查询单个会话 |
| 4 | DELETE | `/api/skill/sessions/{id}` | SkillSession | Cookie | 关闭会话（soft close） |
| 5 | POST | `/api/skill/sessions/{id}/abort` | SkillSession | Cookie | 中止会话 |
| 6 | POST | `/api/skill/sessions/{id}/messages` | SkillMessage | Cookie | 发送消息 |
| 7 | GET | `/api/skill/sessions/{id}/messages` | SkillMessage | Cookie | 消息历史（分页） |
| 8 | GET | `/api/skill/sessions/{id}/messages/history` | SkillMessage | Cookie | 消息历史（游标） |
| 9 | POST | `/api/skill/sessions/{id}/send-to-im` | SkillMessage | Cookie | 转发到 IM |
| 10 | POST | `/api/skill/sessions/{id}/permissions/{permId}` | SkillMessage | Cookie | 回复权限请求 |
| 11 | GET | `/api/skill/agents` | AgentQuery | Cookie | 在线 Agent 列表 |
| 12 | POST | `/api/inbound/messages` | ImInbound | Bearer | IM 入站消息 |
| 13 | POST | `/api/external/invoke` | ExternalInbound | Bearer | 外部统一调用 |
| 14 | GET | `/api/admin/configs` | SysConfig | 无 | 按类型查配置列表 |
| 15 | GET | `/api/admin/configs/value` | SysConfig | 无 | 查单个配置值 |
| 16 | POST | `/api/admin/configs` | SysConfig | 无 | 创建配置 |
| 17 | PUT | `/api/admin/configs/{id}` | SysConfig | 无 | 更新配置 |
| 18 | DELETE | `/api/admin/configs/{id}` | SysConfig | 无 | 删除配置 |
