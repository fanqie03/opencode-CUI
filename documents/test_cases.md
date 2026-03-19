# OpenCode-CUI Phase 2 测试用例

> 基于 [detailed_design.md](./detailed_design.md) 和 [architecture_design.md](./architecture_design.md) 产出。

---

## 一、测试策略

### 1.1 测试层级

| 层级       | 覆盖范围                            | 工具                               | 占比 |
| ---------- | ----------------------------------- | ---------------------------------- | ---- |
| 单元测试   | Service/Resolver/Manager 核心逻辑   | JUnit 5 + Mockito                  | 60%  |
| 集成测试   | DB 迁移 + MyBatis 映射 + Redis 缓存 | SpringBootTest + H2/TestContainers | 25%  |
| 端到端测试 | 完整消息收发链路                    | 手动 + curl/Postman                | 15%  |

### 1.2 测试环境要求

- MySQL 5.7（或 H2 模拟）
- Redis（或 Embedded Redis）
- Mock 第三方 ak 解析接口
- Mock IM 出站 API（单聊/群聊端点）

---

## 二、数据库迁移测试

### TC-DB-001: V6 迁移脚本 — 新增三元组字段

| 项目         | 内容                                                                                                                                                                                                 |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **前置条件** | skill_session 表存在，含 im_group_id 字段                                                                                                                                                            |
| **操作步骤** | 执行 V6__session_chat_triple.sql                                                                                                                                                                     |
| **预期结果** | 新增 `business_session_domain`(VARCHAR 32, NOT NULL, DEFAULT 'miniapp')、`business_session_type`(VARCHAR 32, NULL)、`business_session_id`(VARCHAR 128, NULL)、`assistant_account`(VARCHAR 128, NULL) |
| **验证 SQL** | `DESC skill_session;` 确认 4 个新字段存在                                                                                                                                                            |

### TC-DB-002: V6 迁移脚本 — 旧数据迁移

| 项目         | 内容                                                                                   |
| ------------ | -------------------------------------------------------------------------------------- |
| **前置条件** | skill_session 中存在 im_group_id 非 NULL 的记录                                        |
| **操作步骤** | 执行 V6 迁移脚本                                                                       |
| **预期结果** | 旧 `im_group_id` 值已迁移至 `business_session_id`，`im_group_id` 列已删除              |
| **验证 SQL** | `SELECT business_session_id FROM skill_session WHERE business_session_id IS NOT NULL;` |

### TC-DB-003: V6 迁移脚本 — 唯一索引

| 项目         | 内容                                                                          |
| ------------ | ----------------------------------------------------------------------------- |
| **前置条件** | V6 迁移脚本成功执行                                                           |
| **操作步骤** | 插入两条相同 domain+session_id+ak 的记录                                      |
| **预期结果** | 第二条插入抛出唯一约束异常                                                    |
| **验证 SQL** | `SHOW INDEX FROM skill_session WHERE Key_name = 'idx_biz_domain_session_ak';` |

### TC-DB-004: ENUM → VARCHAR 迁移

| 项目         | 内容                                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------------- |
| **前置条件** | skill_definition.status / skill_session.status / skill_message.role / skill_message.content_type 为 ENUM 类型 |
| **操作步骤** | 执行 V6 迁移脚本中 ENUM → VARCHAR 部分                                                                        |
| **预期结果** | 4 个字段类型均变为 VARCHAR，已有数据值不变                                                                    |
| **验证 SQL** | `SELECT DISTINCT status FROM skill_session;` 确认值保持完整                                                   |

---

## 三、Token 认证拦截器测试

### TC-AUTH-001: 合法 Token 请求

| 项目         | 内容                                                                         |
| ------------ | ---------------------------------------------------------------------------- |
| **前置条件** | `skill.im.inbound-token` 配置为 `test-token-123`                             |
| **请求**     | `POST /api/inbound/messages` + Header `Authorization: Bearer test-token-123` |
| **预期结果** | 请求通过拦截器，到达 Controller（200 或业务响应）                            |

### TC-AUTH-002: 无 Token 请求

| 项目         | 内容                                                 |
| ------------ | ---------------------------------------------------- |
| **请求**     | `POST /api/inbound/messages` 无 Authorization Header |
| **预期结果** | 返回 401 Unauthorized                                |

### TC-AUTH-003: 错误 Token 请求

| 项目         | 内容                                                                      |
| ------------ | ------------------------------------------------------------------------- |
| **请求**     | `POST /api/inbound/messages` + Header `Authorization: Bearer wrong-token` |
| **预期结果** | 返回 401 Unauthorized                                                     |

### TC-AUTH-004: 非 inbound 路径不拦截

| 项目         | 内容                                         |
| ------------ | -------------------------------------------- |
| **请求**     | `GET /api/sessions` 无 Authorization Header  |
| **预期结果** | 正常通过（拦截器仅作用于 `/api/inbound/**`） |

---

## 四、AssistantAccountResolverService 测试

### TC-RESOLVER-001: 首次解析 — 缓存未命中

| 项目         | 内容                                                                                      |
| ------------ | ----------------------------------------------------------------------------------------- |
| **前置条件** | Redis 中无对应缓存，Mock 第三方接口返回 `{"code":"200","data":{"ak":"ak-001"}}`           |
| **输入**     | `resolveAk("assistant-abc")`                                                              |
| **预期结果** | 返回 `"ak-001"`；Redis 中写入 `assistantAccount:ak:assistant-abc` → `ak-001`（TTL=30min） |

### TC-RESOLVER-002: 缓存命中

| 项目         | 内容                                                        |
| ------------ | ----------------------------------------------------------- |
| **前置条件** | Redis 中存在 `assistantAccount:ak:assistant-abc` → `ak-001` |
| **输入**     | `resolveAk("assistant-abc")`                                |
| **预期结果** | 直接从缓存返回 `"ak-001"`，不调用第三方接口                 |

### TC-RESOLVER-003: 第三方接口返回失败

| 项目         | 内容                             |
| ------------ | -------------------------------- |
| **前置条件** | 第三方接口返回 500 或空 data     |
| **输入**     | `resolveAk("unknown-assistant")` |
| **预期结果** | 返回 `null`，日志输出 WARN 级别  |

### TC-RESOLVER-004: 空输入

| 项目         | 内容                                |
| ------------ | ----------------------------------- |
| **输入**     | `resolveAk(null)` / `resolveAk("")` |
| **预期结果** | 返回 `null`，不调用第三方接口       |

---

## 五、ImSessionManager 测试

### TC-SESSION-001: 已有 session — 正常返回

| 项目         | 内容                                                                               |
| ------------ | ---------------------------------------------------------------------------------- |
| **前置条件** | DB 中存在 domain=im, session_id=chat-001, ak=ak-001 的 session，toolSessionId 有效 |
| **输入**     | `findOrCreateSession("im", "group", "chat-001", "ak-001", "assistant-abc")`        |
| **预期结果** | 返回已有 session，`last_active_at` 被 touch 更新                                   |

### TC-SESSION-002: 已有 session — toolSessionId 无效

| 项目         | 内容                                               |
| ------------ | -------------------------------------------------- |
| **前置条件** | DB 中 session 存在但 toolSessionId 为 null         |
| **输入**     | `findOrCreateSession(...)`                         |
| **预期结果** | 触发 rebuildAndWait，重新绑定 toolSessionId 后返回 |

### TC-SESSION-003: 新建 session — 完整流程

| 项目         | 内容                                                                                                                                                    |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **前置条件** | DB 中无匹配 session                                                                                                                                     |
| **输入**     | `findOrCreateSession("im", "group", "chat-001", "ak-001", "assistant-abc")`                                                                             |
| **预期结果** | 1. 创建新 session 记录(domain=im, type=group, session_id=chat-001) 2. 发送 CREATE_SESSION 到 Gateway 3. 阻塞等待 toolSessionId 绑定 4. 返回完整 session |

### TC-SESSION-004: 新建 session — 创建超时

| 项目         | 内容                                        |
| ------------ | ------------------------------------------- |
| **前置条件** | Gateway 不回调 session_created（模拟超时）  |
| **输入**     | `findOrCreateSession(...)`                  |
| **预期结果** | 30s 后抛出 RuntimeException，日志输出 ERROR |

### TC-SESSION-005: 并发创建 — 唯一索引保护

| 项目         | 内容                                                     |
| ------------ | -------------------------------------------------------- |
| **操作**     | 两个线程同时以相同三元组 + ak 调用 `findOrCreateSession` |
| **预期结果** | 只有一个 session 被创建，另一个线程查到已有 session      |

---

## 六、ImInboundController 端到端测试

### TC-INBOUND-001: 群聊消息 — 正常链路

| 项目         | 内容                                                                                                                                                                                                                               |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **请求**     | `POST /api/inbound/messages`                                                                                                                                                                                                       |
| **请求体**   | `{"businessDomain":"im","sessionType":"group","sessionId":"grp-001","assistantAccount":"assist-001","content":"你好","chatHistory":[{"senderAccount":"user1","senderName":"张三","content":"之前的消息","timestamp":1710000000}]}` |
| **预期结果** | 200 OK + Agent 被调用 + IM 群聊端点收到回复                                                                                                                                                                                        |

### TC-INBOUND-002: 单聊消息 — 正常链路

| 项目         | 内容                                                                                                                         |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| **请求**     | `POST /api/inbound/messages`                                                                                                 |
| **请求体**   | `{"businessDomain":"im","sessionType":"direct","sessionId":"dm-001","assistantAccount":"assist-001","content":"帮我写代码"}` |
| **预期结果** | 200 OK + Agent 被调用 + IM 单聊端点收到回复 + 消息持久化到 DB                                                                |

### TC-INBOUND-003: 无效 assistantAccount

| 项目         | 内容                                                      |
| ------------ | --------------------------------------------------------- |
| **请求体**   | `{"assistantAccount":"invalid-xxx","content":"test",...}` |
| **预期结果** | 返回错误 `INVALID_ASSISTANT_ACCOUNT`                      |

### TC-INBOUND-004: 缺少必要字段

| 项目         | 内容                                                                    |
| ------------ | ----------------------------------------------------------------------- |
| **请求体**   | `{"content":"test"}` （缺少 businessDomain/sessionId/assistantAccount） |
| **预期结果** | 返回 400 Bad Request                                                    |

---

## 七、ImOutboundService 测试

### TC-OUTBOUND-001: 群聊发送成功

| 项目         | 内容                                                                                                                                                                   |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **输入**     | `sendTextToIm("group", "grp-001", "Agent回复内容", "assist-001")`                                                                                                      |
| **预期结果** | POST 到 `/v1/welinkim/im-service/chat/app-group-chat`，请求体含 `appMsgId`/`senderAccount=assist-001`/`sessionId=grp-001`/`contentType=13`/`clientSendTime`，返回 true |

### TC-OUTBOUND-002: 单聊发送成功

| 项目         | 内容                                                              |
| ------------ | ----------------------------------------------------------------- |
| **输入**     | `sendTextToIm("direct", "dm-001", "Agent回复内容", "assist-001")` |
| **预期结果** | POST 到 `/v1/welinkim/im-service/chat/app-user-chat`，返回 true   |

### TC-OUTBOUND-003: IM 返回业务错误

| 项目         | 内容                                                                              |
| ------------ | --------------------------------------------------------------------------------- |
| **前置条件** | Mock IM API 返回 `{"error":{"errorCode":"40001","errorMsg":"session not found"}}` |
| **预期结果** | 返回 false，日志输出 ERROR 含 errorCode/errorMsg                                  |

### TC-OUTBOUND-004: IM 网络超时

| 项目         | 内容                                |
| ------------ | ----------------------------------- |
| **前置条件** | Mock IM API 超时不响应              |
| **预期结果** | 返回 false，日志输出 Exception 信息 |

---

## 八、GatewayMessageRouter 路由测试

### TC-ROUTE-001: miniapp 场景路由

| 项目         | 内容                                                                |
| ------------ | ------------------------------------------------------------------- |
| **前置条件** | session.businessSessionDomain = "miniapp"                           |
| **输入**     | Agent 回复消息                                                      |
| **预期结果** | 走 WS 广播路径（Redis pub/sub → WS push），不调用 ImOutboundService |

### TC-ROUTE-002: IM 群聊路由

| 项目         | 内容                                                                          |
| ------------ | ----------------------------------------------------------------------------- |
| **前置条件** | session.businessSessionDomain = "im", businessSessionType = "group"           |
| **输入**     | Agent 回复消息                                                                |
| **预期结果** | 调用 `ImOutboundService.sendTextToIm("group", ...)` + 跳过 MessagePersistence |

### TC-ROUTE-003: IM 单聊路由

| 项目         | 内容                                                                           |
| ------------ | ------------------------------------------------------------------------------ |
| **前置条件** | session.businessSessionDomain = "im", businessSessionType = "direct"           |
| **输入**     | Agent 回复消息                                                                 |
| **预期结果** | 调用 `ImOutboundService.sendTextToIm("direct", ...)` + 执行 MessagePersistence |

### TC-ROUTE-004: reasoning/step 类型跳过

| 项目         | 内容                                                   |
| ------------ | ------------------------------------------------------ |
| **输入**     | type="reasoning" 或 type="step" 的 StreamMessage       |
| **预期结果** | handleImOutbound 直接 return，不调用 ImOutboundService |

---

## 九、上下文注入测试

### TC-CTX-001: 群聊开启注入

| 项目         | 内容                                                        |
| ------------ | ----------------------------------------------------------- |
| **前置条件** | `skill.context.injection-enabled=true`，sessionType="group" |
| **输入**     | content="新消息" + chatHistory=[3 条历史]                   |
| **预期结果** | 使用 Prompt 模板组装最终 prompt，包含历史消息和当前消息     |

### TC-CTX-002: 群聊关闭注入

| 项目         | 内容                                    |
| ------------ | --------------------------------------- |
| **前置条件** | `skill.context.injection-enabled=false` |
| **预期结果** | 直接透传 content，不使用模板            |

### TC-CTX-003: 单聊无注入

| 项目         | 内容                                   |
| ------------ | -------------------------------------- |
| **前置条件** | sessionType="direct"，chatHistory=null |
| **预期结果** | 直接透传 content，跳过历史注入         |

### TC-CTX-004: 历史消息超限截断

| 项目         | 内容                                            |
| ------------ | ----------------------------------------------- |
| **前置条件** | `max-history-messages=20`，chatHistory 有 50 条 |
| **预期结果** | 只取最近 20 条注入 Prompt                       |

---

## 十、上下文超限重建测试

### TC-OVERFLOW-001: IM 场景自动重建

| 项目         | 内容                                                                       |
| ------------ | -------------------------------------------------------------------------- |
| **前置条件** | session.businessSessionDomain = "im"                                       |
| **输入**     | Agent 返回 `session.error` 事件，error.name = "ContextOverflowError"       |
| **预期结果** | 1. 自动重建 toolSession 2. 重发被拒消息 3. IM 收到「上下文已重置」系统提示 |

### TC-OVERFLOW-002: miniapp 场景通知前端

| 项目         | 内容                                      |
| ------------ | ----------------------------------------- |
| **前置条件** | session.businessSessionDomain = "miniapp" |
| **输入**     | Agent 返回 ContextOverflowError           |
| **预期结果** | 通过 WS 通知前端展示提示，不自动重建      |

### TC-OVERFLOW-003: 非 overflow 错误不触发

| 项目         | 内容                                                  |
| ------------ | ----------------------------------------------------- |
| **输入**     | Agent 返回 session.error，error.name = "UnknownError" |
| **预期结果** | 不触发重建逻辑，正常错误处理                          |

---

## 十一、回归测试

### TC-REG-001: 场景一 miniapp WS 连接

| 项目         | 内容                                         |
| ------------ | -------------------------------------------- |
| **操作**     | miniapp 通过 WS 连接 skill-server 并发送消息 |
| **预期结果** | 功能不受影响，正常会话创建和消息交互         |

### TC-REG-002: 场景一 createSession 兼容

| 项目         | 内容                                                             |
| ------------ | ---------------------------------------------------------------- |
| **操作**     | 通过 miniapp 创建新会话                                          |
| **预期结果** | session.businessSessionDomain = "miniapp"，其他三元组字段为 null |

### TC-REG-003: 历史会话查询兼容

| 项目         | 内容                               |
| ------------ | ---------------------------------- |
| **操作**     | 查询用户历史会话列表               |
| **预期结果** | 旧会话（已迁移）和新会话均正常显示 |

---

## 用例统计

| 模块                     | 用例数 |
| ------------------------ | ------ |
| 数据库迁移               | 4      |
| Token 认证               | 4      |
| AssistantAccountResolver | 4      |
| ImSessionManager         | 5      |
| ImInboundController      | 4      |
| ImOutboundService        | 4      |
| 路由分支                 | 4      |
| 上下文注入               | 4      |
| 上下文超限               | 3      |
| 回归测试                 | 3      |
| **合计**                 | **39** |
