# extParameters 嵌套化 + businessExtParam 跨入口透传 Design

**日期**：2026-04-27
**作者**：Llllviaaaa
**状态**：Draft
**模块**：`skill-server`
**相关文件**：
- `skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/PayloadBuilder.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`

---

## 1. 背景与动机

云端助理（business scope）的 HTTP 协议在 `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`（line 147–205）中规约了 `extParameters` 字段，并描述了三个扁平子字段 `isHwEmployee` / `actionParam` / `knowledgeId`。但当前 `BusinessScopeStrategy.buildInvoke` 实现**完全没有填充 `extParameters`**——也就是说：

- **协议规范文档** 描述了一种"远端期望"的扁平形态
- **skill-server 代码** 从未实现该字段的透传
- **远端云端助理** 既然没收到过 `extParameters`，扁平字段（`isHwEmployee` / `actionParam` 等）的语义实际上从未在生产链路中起作用

本次需求要给云端助理协议**首次实现 `extParameters`**，并把它定型为**两个子分组**的嵌套结构：

```json
"extParameters": {
  "businessExtParam": <业务方扩展参数>,
  "platformExtParam": <平台扩展参数>
}
```

同时，三个入站接口（IM / external / miniapp）的 `chat` / `question_reply` / `permission_reply` 三个 action 都新增 `businessExtParam` 入参字段，由业务方塞自由 JSON 对象，skill-server 不解析、纯透传到云端报文 `extParameters.businessExtParam`。`platformExtParam` 由 skill-server 自身填充，首期占位 `{}`，留作未来塞 traceId / 版本号 / 租户标识等平台元数据的扩展点。

**与 2026-04-22 senderUserAccount envelope 迁移的协同关系**：上次把 `senderUserAccount` 从 `payload.senderUserAccount` 提升到 external 信封顶层，确立了"跨 action 通用字段进信封"的先例。本次 `businessExtParam` 沿用同样的位置规则，三入口字段位置（IM record 顶级 / external 信封顶级 / miniapp `@Data` 顶级）100% 对齐。

**范围限定**：
- 仅 `chat` / `question_reply` / `permission_reply` 三个 action；`rebuild` 不动（重建语义无业务扩展参数）
- 仅 business scope 真正消费 `businessExtParam`（在 `BusinessScopeStrategy.buildInvoke` 一处生成 `extParameters`）；personal scope 路径**不在 skill-server 内消费、不剥离**——`businessExtParam` 字段会随 personal payload 原样透到下游 ai-gateway，依赖下游兼容未知字段
- 透传链路覆盖：`InboundProcessingService.dispatchChatToGateway` + `ImSessionManager.createSessionAsync`（business 助手立即发送分支）+ `SkillMessageController` 三处直发（chat / question_reply / permission_reply）
- `CloudRequestContext` / `DefaultCloudRequestStrategy` / `PersonalScopeStrategy` / `OpenCodeEventTranslator` / `CloudEventTranslator` 零改动
- 不持久化 `businessExtParam`（不入库）；不做大小 / 类型白名单校验
- 任意 scope（business / personal）的 pending / replay / rebuild / `session_created` 重发路径接受 `businessExtParam` 丢失（详见 **D19** 统一契约）。覆盖 5 类分支：①IM/external personal 助手首次 `createSessionAsync` 走 `requestToolSession`；②`InboundProcessingService.processChat` 情况 B 的自愈超时降级 / personal 降级（`requestToolSession`）；③business legacy replay（D17）；④miniapp rebuild（D18）；⑤`GatewayMessageRouter` 在 `session_created` 后用 `{text, toolSessionId}` 重发

---

## 2. 决策摘要

| # | 维度 | 决定 |
|---|------|------|
| D1 | 协议形态 | `extParameters: { businessExtParam: <obj or {}>, platformExtParam: <obj or {}> }` —— 嵌套两层，老 protocol doc 的扁平字段说明（`isHwEmployee` / `actionParam` / `knowledgeId`）整体替换 |
| D2 | `platformExtParam` 来源 | 由 skill-server 自身填，inbound 入参不开放；首期硬编码 `{}` 占位 |
| D3 | `businessExtParam` 类型 | `JsonNode`（端到端，从 DTO 到 cloud body 序列化） |
| D4 | 入参必填性 | Optional —— 不传 / null / 错型都兜底 `{}` |
| D5 | 跨 scope 处理 | Approach A：上游入口不感知 scope 一律接受；business scope 唯一消费点 `BusinessScopeStrategy.buildInvoke` 组装 `extParameters`；personal scope 路径不剥离 / 不消费，`businessExtParam` 字段原样随 payload 透到下游 ai-gateway（依赖下游兼容未知字段） |
| D6 | external 字段位置 | 信封顶层（与 `senderUserAccount` 对齐），不放 payload 内 |
| D7 | 三入口 DTO 一致性 | IM record 顶级 / external 信封顶级 / miniapp `@Data` 顶级 —— 字段名 / 类型 / 必填性 100% 对齐 |
| D8 | 适用 action | `chat` / `question_reply` / `permission_reply`；`rebuild` 不动 |
| D9 | `PayloadBuilder` 改动 | 保留旧 `buildPayload(Map<String,String>)` 不动（**11 处** 调用方零迁移：`SkillSessionController.java:129/202/236`、`SkillMessageController.java:201/214/403`、`InboundProcessingService.java:294/352/404`、`ImSessionManager.java:163/240` 等）；**新增重载** `buildPayloadWithObjects(Map<String,Object>)` 处理嵌套对象 |
| D10 | `BusinessScopeStrategy` 改动 | 保留 `extractField` 不动；**新增私有** `extractObjectField` helper；`buildInvoke` 在 `CloudRequestContext.builder()` 末位加 `.extParameters(...)` |
| D11 | 类型异常处理 | `businessExtParam` 不是 JSON object（string / array / number / bool）→ 兜底 `{}` + WARN 日志，**不返 400**（错误在最有上下文的地方处理） |
| D12 | 持久化 | 不持久化（YAGNI；`SkillMessage` schema 不动） |
| D13 | 大小 / 校验 | 不做服务端大小限制 / 字段白名单（依赖远端校验） |
| D14 | 日志 | INFO `[ENTRY]` 日志直打完整 `businessExtParam` JSON 内容（已与用户确认；业务方需注意不在该字段内放敏感数据） |
| D15 | 群聊历史消息 | `chatHistory` 内的历史消息**不带** `businessExtParam`（仅当前请求带） |
| D16 | personal scope **ready 路径**（直接 invoke）契约 | 仅当 session/toolSession 已 ready 且经 `BusinessScopeStrategy` 之外的 `GatewayRelayService.buildInvokeMessage` 直接出站时，`businessExtParam` 字段随 payload 原样透到下游 ai-gateway，**不在 skill-server 内剥离**；下游需兼容未知字段（不报错），skill-server 不做 metric。**注意**：personal scope 的 pending/replay/重发路径不属于该契约，见 D19 |
| D17 | business legacy replay（IM/external 自愈后重放历史消息） | legacy 重放路径**不携带** `businessExtParam`（pending 队列只存纯文本，无法还原原消息绑定的 ext）。`dispatchChatToGateway` 在 legacy 重放上下文显式传 `null`，云端报文兜底为 `{}`。**接受丢失旧消息 ext** 的语义换取 pending 存储不需要 schema 升级 |
| D18 | miniapp rebuild 路径（toolSessionId 未就绪） | `routeToGateway` 在 `toolSessionId == null` 时走 `rebuildToolSession(...)` → 入 pending（仅纯文本）；后续 `retryPendingMessages` 重发时**不携带** `businessExtParam`。**接受首次 ext 丢失**，调用方在 toolSession ready 后重发即可 |
| D19 ⭐ | 统一的"pending / replay / rebuild / session_created 重发"路径契约 | 任意 scope（business/personal）下，凡走 `SessionRebuildService` pending 队列、`requestToolSession`、`GatewayMessageRouter.retryPendingMessages` / `session_created` 回调重发链路的消息，均**不携带** `businessExtParam`（pending 队列纯文本设计决定）。具体覆盖：①IM/external personal 助手的 `createSessionAsync` personal 分支（走 `requestToolSession`）；②`InboundProcessingService.processChat` 情况 B 的 personal 降级（`requestToolSession`）；③business legacy replay（D17）；④miniapp rebuild（D18）；⑤`GatewayMessageRouter` 在 `session_created` 后用 `{text, toolSessionId}` 重发。**接受设计**：业务方应在 ready 路径调用，replay 路径属于异常恢复，不应依赖 ext 在此生效 |

---

## 3. 协议形态（D1 / D2 / D3）

### 3.1 新协议结构

```json
"extParameters": {
  "businessExtParam":  { /* 业务方透传的自由 JSON 对象，缺省 {} */ },
  "platformExtParam":  { /* 平台填充，首期 {} */ }
}
```

**契约保证**：
- `extParameters` 始终为 JSON object
- `extParameters.businessExtParam` 始终为 JSON object（缺省 / 类型异常 / 解析失败 → `{}`）
- `extParameters.platformExtParam` 始终为 JSON object（首期硬编码 `{}`，未来由 skill-server 填）

### 3.2 协议文档变更

`docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md` 中：

- line 147–151（请求示例）：扁平 `isHwEmployee` / `actionParam` / `knowledgeId` 替换为嵌套 `businessExtParam` / `platformExtParam`
- line 202–205（字段表格）：删除三行扁平字段说明，新增两行嵌套子字段说明
- line 270–275 / line 1137（`actionParam` 与 markdown 链接协议的关联段落）：删除或重写为"业务方需要把 `actionParam` 等业务字段塞进 `businessExtParam` 子对象内传递"的说明

> 协议文档变更**不会破坏现网行为**，因为当前 skill-server 代码本来就没有发送扁平字段。

---

## 4. 三入口契约（D4 / D6 / D7 / D8）

### 4.1 字段位置 / 类型 / 必填性

| 入口 | DTO 类 | 字段位置 | 类型 | 必填性 |
|------|--------|---------|------|--------|
| IM Inbound | `ImMessageRequest`（record） | 顶级 record 字段 | `JsonNode` | Optional |
| External Inbound | `ExternalInvokeRequest`（@Data class） | **信封顶级**（与 `senderUserAccount` 对齐） | `JsonNode` | Optional |
| Miniapp send | `SkillMessageController.SendMessageRequest`（@Data class） | 顶级字段 | `JsonNode` | Optional |
| Miniapp permission | `SkillMessageController.PermissionReplyRequest`（@Data class） | 顶级字段 | `JsonNode` | Optional |

**字段名统一**：所有入口都叫 `businessExtParam`（驼峰单数，跟 `senderUserAccount` / `assistantAccount` 风格一致）。

### 4.2 适用 action

| Action | IM | External | Miniapp |
|--------|----|---------|---------|
| `chat` | ✅ | ✅ | ✅（`SendMessageRequest` toolCallId 缺失分支） |
| `question_reply` | ❌（IM 入口无该 action） | ✅ | ✅（`SendMessageRequest` toolCallId 非空分支） |
| `permission_reply` | ❌（IM 入口无该 action） | ✅ | ✅（`PermissionReplyRequest`） |
| `rebuild` | ❌ | ❌（不动） | ❌（miniapp 无 rebuild 接口） |

### 4.3 缺省 / 异常 处理

| 入参形态 | DTO 字段 | payload 序列化 | 汇聚点处理 | 云端报文最终值 |
|---------|---------|---------------|-----------|---------------|
| 字段缺失 | null | 跳过（key 不出现） | `extractObjectField` 返 null → 兜底 | `{}` |
| `"businessExtParam": null` | `NullNode` 或 null | 跳过（key 不出现） | 同上 | `{}` |
| `"businessExtParam": {}` | empty `ObjectNode` | 写入 | `extractObjectField` 返 `{}` | `{}` |
| `"businessExtParam": {a:1}` | `ObjectNode` | 写入 | 透传 | `{a:1}` |
| `"businessExtParam": "abc"` | `TextNode` | 写入 | 检测非 object → 返 null + WARN → 兜底 | `{}` |
| `"businessExtParam": [1,2]` | `ArrayNode` | 写入 | 同上 | `{}` |
| `"businessExtParam": 123` / `true` | `NumericNode` / `BooleanNode` | 写入 | 同上 | `{}` |
| HTTP body 整体非法 JSON | — | — | — | Spring 默认 400 |

---

## 5. 架构（D5 / D10）

### 5.1 数据流总览

```
HTTP body                                 ▼
─────────────────────────────────────────────────────────────────────
  IM         External      Miniapp                              入口
  ImMessage  ExternalInv   SendMessage / PermissionReply         ①
  Request    Request       Request
              │                                                   
              │ Jackson 反序列化为 JsonNode                       
              ▼                                                   
─────────────────────────────────────────────────────────────────────
  ImInboundCtrl ──┐                                              中
                  ├──▶ InboundProcessingService                  间
  ExternalInboCtrl ┘     ├ processChat                           ⑤⑥⑧
                          ├ processQuestionReply                  
                          └ processPermissionReply                
                              │                                   
                              │ 组 Map<String,Object> payloadFields
                              │ 含新键 "businessExtParam"          
                              │                                   
                              ▼                                   
                          PayloadBuilder.buildPayloadWithObjects ⑨
                              │ String → put / JsonNode → set    
                              │ null / NullNode → skip            
                              ▼                                   
                          payload string                                                
                              │                                   
                              ▼                                   
  SkillMessageCtrl ─── routeToGateway / replyPermission           
                       直接组 payload（不经 service） ⑦           
                              │                                   
                              ▼                                   
                          InvokeCommand(ak, uid, sid, action, payloadStr)
                              │                                   
                              │ gatewayRelayService.sendInvokeToGateway
                              ▼                                   
─────────────────────────────────────────────────────────────────────
  GatewayRelayService.sendInvokeToGateway                        下
              │                                                  游
              │ 路由依据 = info.isBusiness()                       ⭐
        ┌─────┴─────┐
        ▼           ▼
   business      personal / 其他
        │           │
        │           │ 走 buildInvokeMessage(command)
        │           │ • 把 InvokeCommand.payload 字符串原样塞进出站消息
        │           │ • **不剥离任何字段**（businessExtParam 透到下游 ai-gateway）
        │           │ • 不调 BusinessScopeStrategy.buildInvoke
        │           ▼
        │       ai-gateway 端 personal 路径自行决定如何处理 businessExtParam
        │       （依赖下游兼容未知字段，不在 skill-server 范围）
        │
        │   ⚠ 注意：上述 "原样透下游" 仅适用于 ready 路径。
        │   pending/replay/rebuild/session_created 重发路径在进入
        │   GatewayRelayService 之前已把 ext 丢失（D19 接受丢失）。
        ▼
   BusinessScopeStrategy.buildInvoke ⭐                         
   ① extractObjectField(payload, "businessExtParam")            
      ├ payload null/blank → 返 null                             
      ├ readTree 失败 → catch 后返 null + DEBUG 日志              
      ├ 字段缺失 / NullNode → 返 null                            
      ├ 字段非 object → 返 null + WARN 日志                      
      └ object → 返该 JsonNode                                   
   ② 组装 extParameters Map                                      
      ext.put("businessExtParam",                                
              bep != null ? bep : objectMapper.createObjectNode());
      ext.put("platformExtParam", objectMapper.createObjectNode());
   ③ CloudRequestContext.builder()...extParameters(ext).build();
        │
        ▼
   DefaultCloudRequestStrategy.build（不动）                     
   node.set("extParameters", objectMapper.valueToTree(ext))      
        │
        ▼
   cloud HTTP body.extParameters
   = { "businessExtParam": <obj or {}>, "platformExtParam": {} }
```

### 5.2 三入口路径细节

#### 5.2.1 IM Inbound

```
POST /api/inbound/messages
Body: { ..., "businessExtParam": {...}, "content": "..." }
   │
   │ ImInboundController.receiveMessage
   │ ├ INFO 日志：businessExtParam={"isHwEmployee":false,...}
   │ └ validate → processingService.processChat(..., "IM", request.businessExtParam())
   ▼
InboundProcessingService.processChat(..., businessExtParam)
   │ 情况 A: session 不存在 → sessionManager.createSessionAsync(..., businessExtParam)
   │        └ business 助手分支：本地预生成 toolSessionId 后立即出站第一条 chat invoke
   │           （ImSessionManager 内部组 payload，**透传 businessExtParam**）
   │           personal 助手分支：走 requestToolSession（pending 仅纯文本，不带 ext）
   │ 情况 B: session 在但 toolSessionId 不就绪
   │        ├ 自愈成功 → dispatchChatToGateway（透**当前请求**的 businessExtParam）
   │        └ legacy 重放 for 循环 → 每条 dispatchChatToGateway 显式传 null（D17）
   │           （pending 队列只存纯文本，无法还原原消息 ext，云端兜底 {}）
   │ 情况 C: session 就绪 → dispatchChatToGateway（透**当前请求**的 businessExtParam）
   ▼
dispatchChatToGateway(..., businessExtParam)
   │ payloadFields = Map<String,Object> { text, toolSessionId, sendUserAccount,
   │                                       assistantAccount, imGroupId, messageId,
   │                                       businessExtParam }
   │ payload = PayloadBuilder.buildPayloadWithObjects(om, payloadFields)
   ▼
InvokeCommand(ak, owner, sid, "chat", payload) → gateway

ImSessionManager.createSessionAsync(..., businessExtParam)  ←⭐ 必改
   │ business 立即发送分支（line 146-166 当前代码）：
   │   payloadFields = Map<String,Object> { text, toolSessionId, assistantAccount,
   │                                         sendUserAccount, imGroupId, messageId,
   │                                         businessExtParam }
   │   payload = PayloadBuilder.buildPayloadWithObjects(om, payloadFields)
   │   gatewayRelayService.sendInvokeToGateway(InvokeCommand(..., "chat", payload))
   ▼
（首次新会话首条消息透传完成，避免 silently drop）
```

#### 5.2.2 External Inbound

```
POST /api/external/invoke
Body: {
  "action": "chat" | "question_reply" | "permission_reply" | "rebuild",
  "businessDomain": "...",
  "sessionType": "...",
  "sessionId": "...",
  "assistantAccount": "...",
  "senderUserAccount": "...",
  "businessExtParam": {...},                ← 信封顶级
  "payload": { /* action 专属 */ }
}
   │
   │ ExternalInboundController.invoke
   │ ├ INFO 日志：businessExtParam=...
   │ ├ validateEnvelope（不校验 businessExtParam）
   │ └ switch (action):
   │     case "chat" / "question_reply" / "permission_reply":
   │           processingService.processXxx(..., request.getBusinessExtParam())
   │     case "rebuild":
   │           processingService.processRebuild(...)（不传 businessExtParam）
   ▼
（与 IM 共享后续路径）
```

#### 5.2.3 Miniapp（绕过 InboundProcessingService）

```
POST /api/skill/sessions/{sid}/messages
Body: { "content": "...", "toolCallId": null|"...", "businessExtParam": {...} }
   │
   │ SkillMessageController.sendMessage
   │ ├ 持久化用户消息 + ws 广播
   │ └ routeToGateway(session, sid, ..., request, userIdCookie)
   ▼
routeToGateway:
   if (session.getToolSessionId() == null) {
     // ⚠ rebuild 分支：触发 toolSession 重建，仅纯文本入 pending
     gatewayRelayService.rebuildToolSession(sid, session, request.getContent());
     // ⚠ businessExtParam **在该路径丢失**（D18 接受丢失）；
     // 调用方在 toolSession ready 后重发即可
     return;
   }
   if (toolCallId == null) {        ← chat 分支
     payloadFields = Map<String,Object> { text, toolSessionId, sendUserAccount,
                                          assistantAccount, messageId, businessExtParam };
     payload = PayloadBuilder.buildPayloadWithObjects(om, payloadFields);
     action = "chat";
   } else {                         ← question_reply 分支
     qr = LinkedHashMap<String,Object> { answer, toolCallId, toolSessionId, businessExtParam };
     payload = PayloadBuilder.buildPayloadWithObjects(om, qr);
     action = "question_reply";
   }
   gatewayRelayService.sendInvokeToGateway(new InvokeCommand(ak, uid, sid, action, payload));

POST /api/skill/sessions/{sid}/permissions/{permId}
Body: { "response": "once|always|reject", "subagentSessionId": "...", "businessExtParam": {...} }
   │
   │ SkillMessageController.replyPermission
   ▼
   pr = LinkedHashMap<String,Object> { permissionId, response, toolSessionId, businessExtParam };
   payload = PayloadBuilder.buildPayloadWithObjects(om, pr);
   gatewayRelayService.sendInvokeToGateway(new InvokeCommand(ak, uid, sid, "permission_reply", payload));
   gatewayRelayService.publishProtocolMessage(sid, replyStreamMessage);  // ws 广播，不带 businessExtParam
```

### 5.3 唯一汇聚点 = `BusinessScopeStrategy.buildInvoke`

所有入口 + 所有 action 的 `businessExtParam` 最终汇入 `InvokeCommand.payload` string。`BusinessScopeStrategy.buildInvoke` 是唯一从该 string 中**读取 + 组装 + 写入** `CloudRequestContext.extParameters` 的位置。

**实现要点**：

```java
@Override
public String buildInvoke(InvokeCommand command, AssistantInfo info) {
    String appId = info.getAppId();
    String content = extractContent(command.payload());
    String toolSessionId = extractField(command.payload(), "toolSessionId");

    // 取业务扩展参数（缺省 / 异常 → null）
    JsonNode bep = extractObjectField(command.payload(), "businessExtParam");

    // 组装 extParameters（保证 key 永远存在且为 object）
    Map<String, Object> extParameters = new LinkedHashMap<>();
    extParameters.put("businessExtParam",
            bep != null ? bep : objectMapper.createObjectNode());
    extParameters.put("platformExtParam", objectMapper.createObjectNode());

    CloudRequestContext context = CloudRequestContext.builder()
            .content(content)
            .contentType("text")
            .topicId(toolSessionId)
            .assistantAccount(extractField(command.payload(), "assistantAccount"))
            .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
            .imGroupId(extractField(command.payload(), "imGroupId"))
            .messageId(extractField(command.payload(), "messageId"))
            .clientLang("zh")
            .extParameters(extParameters)                // ← 新增
            .build();

    // ... 其余构造 cloudRequest / payload / message 逻辑保持不变
}

/** 新增私有 helper：取嵌套 JSON 对象，所有异常路径返 null。 */
private JsonNode extractObjectField(String payload, String fieldName) {
    if (payload == null || payload.isBlank()) return null;
    try {
        JsonNode node = objectMapper.readTree(payload);
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) return null;
        if (!field.isObject()) {
            log.warn("[WARN] {} is not a JSON object, treating as empty: actualType={}, value={}",
                    fieldName, field.getNodeType(), field);
            return null;
        }
        return field;
    } catch (JsonProcessingException e) {
        log.debug("Failed to parse payload for object field extraction: field={}, error={}",
                fieldName, e.getMessage());
        return null;
    }
}
```

`extractField`（取字符串字段）保持不动。

### 5.4 `ImSessionManager.createSessionAsync` 透传（Issue 1 必改点）

`ImSessionManager.createSessionAsync` 在 business 助手分支会**立即出站**第一条 chat invoke（`ImSessionManager.java:146-166`），不经 `dispatchChatToGateway`。**必须改造此方法签名 + 内部 payload 组装**，否则 IM/external 首条新会话消息会 silently drop `businessExtParam`。

```java
public void createSessionAsync(String businessDomain, String sessionType,
        String sessionId, String ak, String ownerWelinkId, String assistantAccount,
        String senderUserAccount, String pendingMessage,
        JsonNode businessExtParam) {                                    // ← 末位新增
    // ...（已有锁与二次检查逻辑保持不变）

    // business 立即发送分支（line 146-166）：
    if (generatedToolSessionId != null) {
        sessionService.updateToolSessionId(created.getId(), generatedToolSessionId);
        if (pendingMessage != null && !pendingMessage.isBlank()) {
            // 与 InboundProcessingService.dispatchChatToGateway 保持同样的 effectiveSender 计算口径
            String effectiveSender = "group".equals(sessionType)
                    && senderUserAccount != null && !senderUserAccount.isBlank()
                    ? senderUserAccount : ownerWelinkId;
            Map<String, Object> payloadFields = new LinkedHashMap<>();   // ← String → Object
            payloadFields.put("text", pendingMessage);
            payloadFields.put("toolSessionId", generatedToolSessionId);
            payloadFields.put("assistantAccount", assistantAccount);
            payloadFields.put("sendUserAccount", effectiveSender);
            payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
            payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
            payloadFields.put("businessExtParam", businessExtParam);    // ← 新增
            gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                    ak, ownerWelinkId, String.valueOf(created.getId()),
                    GatewayActions.CHAT,
                    PayloadBuilder.buildPayloadWithObjects(             // ← 改用对象重载
                            objectMapper, payloadFields)));
        }
    } else {
        // personal 助手分支：requestToolSession 不带 ext（pending 仅纯文本，遵循 D19）
        requestToolSession(created, pendingMessage);
    }
}
```

**所有 `createSessionAsync` 调用点**都要因签名变更同步追加形参（避免编译失败）：

- `InboundProcessingService.processChat` 情况 A（line 158-159）：透传当前请求的 `businessExtParam`
- `InboundProcessingService.processRebuild` session 不存在分支（line 526-527）：传 `null`（rebuild 语义无 ext，遵循 D8）

---

## 6. 不变量（设计契约）

无论入参形态、scope、action、错误路径如何，以下不变量在本次设计下**始终成立**：

| 编号 | 不变量 | 验证用例 |
|------|--------|---------|
| I1 | `InvokeCommand.payload` 中 `businessExtParam` 键的写入行为 = `PayloadBuilder.buildPayloadWithObjects` 的 null/NullNode 跳过规则 | DTO null / NullNode → key 不出现；object 或非 object（TextNode/ArrayNode/ValueNode）→ key 出现，由 `BusinessScopeStrategy` 在汇聚点检测形态并兜底 |
| I2 | cloud body `extParameters.businessExtParam` 永远存在且为 JSON object | 缺省路径 / 类型异常 / payload 解析失败 → `{}`；正常路径 → 透传值 |
| I3 | cloud body `extParameters.platformExtParam` 永远存在且为 `{}` | 首期硬编码；未来由 skill-server 填 |
| I4 | **scope × 路径** 二维契约：① business scope ready 路径 → 云端报文 `extParameters.businessExtParam` 永远存在；② personal scope ready 路径 → `businessExtParam` 字段随 payload 透到下游 ai-gateway 不剥离（`GatewayRelayService.buildInvokeMessage` 不剥离）；③ 任意 scope 的 pending/replay/rebuild/重发路径 → 字段丢失（D19） | `GatewayRelayService.sendInvokeToGateway` 对 `info.isBusiness()==true` 才调 `BusinessScopeStrategy.buildInvoke`；其他情况走 `buildInvokeMessage(command)` 原样转发 payload。但 pending/replay/重建路径在进入 `GatewayRelayService` 之前就已经把 ext 丢掉（`SessionRebuildService` / `requestToolSession` / `retryPendingMessages` 仅纯文本） |
| I5 | "上游纯透传，下游单点汇聚"：`businessExtParam` 的语义只在 `BusinessScopeStrategy.buildInvoke` 一处定义 | 入口 / service / `PayloadBuilder` 都不解释字段语义，不做 scope 判断 |
| I6 | 三入口字段位置 / 命名 / 类型 / 必填性 100% 对齐 | IM record 顶级 / external 信封顶级 / miniapp `@Data` 顶级，全部叫 `businessExtParam: JsonNode` Optional |

---

## 7. 错误处理

### 7.1 错误来源穷举

| # | 场景 | 处理 |
|---|------|------|
| E1 | 业务方传 `"businessExtParam": null` | 兜底 `{}`，不报错 |
| E2 | 业务方传 string（非 object） | 兜底 `{}` + WARN 日志 |
| E3 | 业务方传 array | 同 E2 |
| E4 | 业务方传 number / bool | 同 E2 |
| E5 | HTTP body 整体非法 JSON | Spring 默认 400 |
| E6 | `InvokeCommand.payload` string 不是合法 JSON | catch 后返 null → 兜底 `{}` + DEBUG 日志 |
| E7 | 业务方传超大对象 | 不校验（依赖远端） |
| E8 | Personal scope 收到 `businessExtParam` | **Ready 路径**：字段随 payload 原样透到下游 ai-gateway（`GatewayRelayService.buildInvokeMessage` 不剥离），skill-server 不报错 / 不警告；下游需兼容未知字段。**Pending/replay/重建路径**：字段在进入 `GatewayRelayService` 之前就被 `requestToolSession` / `SessionRebuildService` / `retryPendingMessages` 的纯文本 schema 丢掉，与 D17/D18/D19 同性质（接受丢失） |
| E9 | `objectMapper.valueToTree` 失败 | 沿用现有 `DefaultCloudRequestStrategy` 兜底（不改） |

### 7.2 处理原则

> **入口宽容、汇聚点防御、远端可信。**

- 入口（DTO 反序列化）：不做语义校验，Jackson 直接收 `JsonNode`
- 汇聚点（`BusinessScopeStrategy`）：类型不是 object → 兜底 `{}`；非法 JSON → 兜底 `{}`；null / 缺省 → 兜底 `{}`；记 WARN 日志（非 object 时）
- 远端（cloud 协议消费）：假设永远收到合法 object（契约保证）

### 7.3 日志规约

| 级别 | 触发 | 内容 |
|------|------|------|
| INFO `[ENTRY]` | 入口收到请求 | 含完整 `businessExtParam` JSON 内容（D14） |
| WARN | E2-E4 类型异常 | `[WARN] businessExtParam is not a JSON object, treating as empty: actualType=STRING, value="abc"` |
| DEBUG | E6 payload 解析失败 | `Failed to parse payload for object field extraction: field=businessExtParam, error=...` |
| 无日志 | E1 null / 缺省 | 正常路径 |
| 无日志 | E8 personal scope | 契约：字段原样透下游，skill-server 静默不报、不剥离 |

### 7.4 NullNode 与 null 等价处理

`PayloadBuilder.buildPayloadWithObjects` 中 NullNode 跟 Java null 等价，都跳过不写入 key：

```java
fields.forEach((k, v) -> {
    if (v == null) return;
    if (v instanceof JsonNode jn) {
        if (jn.isNull()) return;       // ← NullNode 也跳过
        node.set(k, jn);
    } else if (v instanceof String s) {
        node.put(k, s);
    } else {
        node.set(k, objectMapper.valueToTree(v));
    }
});
```

避免 payload string 中出现 `"businessExtParam": null` 这种半成品。

---

## 8. 测试策略

### 8.1 测试金字塔

```
┌─────────────────────────────────────┐
│ 集成测试 (3 用例必做)                 │ E2E：business cloud body / personal ready 透传 / createSessionAsync 立即发送
├─────────────────────────────────────┤
│ 控制器测试 (8 用例)                   │ IM 2 + External 3 + Miniapp 3
├─────────────────────────────────────┤
│ 服务/汇聚层测试 (15 用例)             │ BusinessScopeStrategy 9 + InboundProcessingService 5 + ImSessionManager 1
├─────────────────────────────────────┤
│ 工具层测试 (5 用例)                   │ PayloadBuilder 5
└─────────────────────────────────────┘
```

合计 **31 用例**。说明：删除原 ⓑ-1/2/3 共 3 个 `payloadJson` helper 测试（已废弃），新增 ⓓ-2/ⓓ-3/ⓓ-6 / ⓕ-2 / ⓕ-3 共 5 个真实分支测试。DTO 反序列化层不单独建 test class（Jackson 框架行为，由控制器测试 + service 测试间接覆盖）。

### 8.2 测试矩阵

#### 8.2.1 `PayloadBuilderTest.java`（**新建**）

| # | 用例 | 输入 | 预期 |
|---|------|------|------|
| ⓐ-1 | String value 写入 | `{"k":"v"}` | `{"k":"v"}` |
| ⓐ-2 | JsonNode object 直接 set | `{"k": ObjectNode{a:1}}` | `{"k":{"a":1}}` |
| ⓐ-3 | Map/POJO value valueToTree | `{"k": Map.of("a",1)}` | `{"k":{"a":1}}` |
| ⓐ-4 | null value 跳过 | `{"k": null, "x":"y"}` | `{"x":"y"}` |
| ⓐ-5 | NullNode value 跳过 | `{"k": NullNode, "x":"y"}` | `{"x":"y"}` |

#### 8.2.2 `ExternalInvokeRequestTest.java`（**新建**或扩展）

| # | 用例 | 预期 |
|---|------|------|
> **`ExternalInvokeRequest.payloadJson` helper 不再需要**：`businessExtParam` 是信封顶层字段（与 `senderUserAccount` 对齐），`request.getBusinessExtParam()` 直接取即可。原 ⓑ-1/2/3 用例删除。

#### 8.2.3 `BusinessScopeStrategyTest.java` ⭐（核心 9 用例）

**透传场景**：

| # | Action | 输入 payload | 期望 cloudRequest.extParameters |
|---|--------|------------|---------------------------------|
| ⓒ-1 | chat | `{text, businessExtParam:{a:1, k:[1,2]}}` | `{businessExtParam:{a:1,k:[1,2]}, platformExtParam:{}}` |
| ⓒ-2 | chat | `{text}` 缺省 | `{businessExtParam:{}, platformExtParam:{}}` |
| ⓒ-3 | question_reply | `{answer, toolCallId, businessExtParam:{q:"x"}}` | `{businessExtParam:{q:"x"}, platformExtParam:{}}` |
| ⓒ-4 | question_reply | `{answer, toolCallId}` | `{businessExtParam:{}, platformExtParam:{}}` |
| ⓒ-5 | permission_reply | `{permissionId, response, businessExtParam:{p:true}}` | `{businessExtParam:{p:true}, platformExtParam:{}}` |
| ⓒ-6 | permission_reply | `{permissionId, response}` | `{businessExtParam:{}, platformExtParam:{}}` |

**错误/边界场景**：

| # | 输入 | 预期 |
|---|------|------|
| ⓒ-7 | `{businessExtParam:"abc"}` | 兜底 `{}` + WARN 日志 |
| ⓒ-8 | `{businessExtParam:[1,2,3]}` | 兜底 `{}` + WARN 日志 |
| ⓒ-9 | `payload="not-a-json"` | 兜底 `{}` + DEBUG 日志，不抛异常 |

#### 8.2.4 `InboundProcessingServiceTest.java`（扩展）

| # | 用例 | 验证 |
|---|------|------|
| ⓓ-1 | `processChat` 情况 C 透传（session ready） | mock `gatewayRelayService`，捕 `InvokeCommand`，断言 payload 含 `businessExtParam` |
| ⓓ-2 | `processChat` 情况 A 透传 `ImSessionManager.createSessionAsync`（session 不存在） | mock `sessionManager`，断言 `createSessionAsync` 收到的形参 `JsonNode businessExtParam` 与入参等值 |
| ⓓ-3 | `processChat` 情况 B legacy replay **不带** `businessExtParam`（D17） | 模拟自愈成功路径下 legacy pending 重放，断言每条 legacy 的 InvokeCommand.payload **不含** `businessExtParam` 键（值由当前请求消息单独发送） |
| ⓓ-4 | `processQuestionReply` 透传 | 同 ⓓ-1 |
| ⓓ-5 | `processPermissionReply` 透传 | 同 ⓓ-1 |

> 现有 `processXxx` / `createSessionAsync` 用例的位置参数都末位补传 `null`（验证向后兼容路径）。

#### 8.2.4.1 `ImSessionManagerTest.java`（扩展或新建）

| # | 用例 | 验证 |
|---|------|------|
| ⓓ-6 | `createSessionAsync` business 立即发送透传 | mock `gatewayRelayService.sendInvokeToGateway`，捕 `InvokeCommand`，断言 payload 含 `businessExtParam` 且与入参等值；同时验证 `buildPayloadWithObjects` 被调用而非旧 `buildPayload` |

#### 8.2.5 控制器测试

| # | 文件 | 用例 | 验证 |
|---|------|------|------|
| ⓔ-1 | `ImInboundControllerTest` | `businessExtParam` 透到 service | mock service，断言 `processChat` 收到的 `JsonNode` 与 HTTP body 等值 |
| ⓔ-2 | `ImInboundControllerTest` | 缺省 | 断言收到 null |
| ⓔ-3 | `ExternalInboundControllerTest` | chat 透传 envelope | 信封字段 → service 收到 |
| ⓔ-4 | `ExternalInboundControllerTest` | question_reply 透传 | 同上 |
| ⓔ-5 | `ExternalInboundControllerTest` | permission_reply 透传 | 同上 |
| ⓔ-6 | `SkillMessageControllerTest` | chat 分支 | mock `gatewayRelayService`，捕 `InvokeCommand`，断言 payload 含 `businessExtParam` |
| ⓔ-7 | `SkillMessageControllerTest` | question_reply 分支 | 同上 |
| ⓔ-8 | `SkillMessageControllerTest` | permission_reply | 同上 |

#### 8.2.6 集成测试

| # | 路径 | 验证 |
|---|------|------|
| ⓕ-1（必做） | IM HTTP → BusinessScopeStrategy → cloudRequest | 完整 chat 路径，断言 `cloudRequest.extParameters` 含两个子字段且值正确 |
| ⓕ-2（必做） | personal scope 路径透传 | 断言 `GatewayRelayService.sendInvokeToGateway` 对 personal 助手的 invoke 出站消息中**保留** `payload.businessExtParam` 键且值与入参一致（即 `buildInvokeMessage` 不剥离），`BusinessScopeStrategy.buildInvoke` 不被调用（验证 I4 + D16） |
| ⓕ-3（必做） | `ImSessionManager.createSessionAsync` business 立即发送透传 | 模拟 IM 首条新会话消息，断言异步创建后立即出站的 chat invoke payload 含 `businessExtParam`（验证 D17 之外的"首次新会话"路径不丢失 ext） |

#### 8.2.7 现有测试更新

| 文件 | 改动 |
|------|------|
| `ImInboundControllerTest` | 修复 **7 处** `new ImMessageRequest(...)` positional 构造（`ImInboundControllerTest.java:42/66/86/113/126/139/152`），全部末位补 `null` |
| `BusinessScopeStrategyTest` | 现有用例如断言完整 cloudRequest JSON，需要补 `extParameters` 字段 |
| `CloudRequestBuilderTest` | 新增 1 用例：`extParameters` 含嵌套 `businessExtParam`/`platformExtParam` 时序列化正确 |

### 8.3 测试覆盖追踪

| 不变量/错误场景 | 覆盖用例 |
|---|----------|
| I1 (payload key 存在性) | ⓒ-1..6 + ⓓ-1/2/4/5/6 + ⓔ-1..8 |
| I2 (cloud businessExtParam 永远存在) | ⓒ-1..9 + ⓕ-1 |
| I3 (cloud platformExtParam 永远 `{}`) | ⓒ-1..9 + ⓕ-1 |
| I4 (business 消费 / personal 透下游不剥离) | ⓕ-2 |
| I5 (单点汇聚) | ⓒ-1..9 |
| I6 (三入口一致) | ⓔ-1..8 |
| D17 (business legacy replay 不带 ext) | ⓓ-3 |
| D18 (miniapp rebuild 路径丢失 ext，接受) | 不写显式断言（YAGNI），仅在风险章节记录 |
| D19 (任意 scope pending/replay/重发路径丢失 ext) | **仅部分覆盖**：D17 的 ⓓ-3 覆盖 business legacy replay 不带 ext；D18 接受丢失策略覆盖 miniapp rebuild；ⓕ-2 验证的是 **personal ready 路径** 透传（不属于 D19 范围）。**未做显式断言的分支**：①personal `createSessionAsync` 走 `requestToolSession`；②`InboundProcessingService.processChat` 情况 B `requestToolSession` 降级；③`GatewayMessageRouter.retryPendingMessages` / `session_created` 重发——按 YAGNI 接受丢失，仅在风险章节记录，实施阶段如发现真实业务影响再补断言 |
| E1 null/缺省 | ⓒ-2,4,6 + ⓔ-2 + ⓐ-4,5 |
| E2-E4 类型异常 | ⓒ-7,8（覆盖 string/array；number/bool 同分支） |
| E6 payload 非法 JSON | ⓒ-9 |
| Issue 1 (createSessionAsync 立即发送) | ⓓ-2 + ⓓ-6 + ⓕ-3 |
| E8 personal 透下游不剥离 | ⓕ-2 |

---

## 9. 实施 PR 顺序

### 9.1 PR 拆分建议

| PR | 内容 | 文件数 |
|----|------|--------|
| PR1 — 基础工具层 | `PayloadBuilder.buildPayloadWithObjects` 重载 + 工具单测 | 1 + 1 测试 |
| PR2 — DTO + 三入口透传 + ImSessionManager | `ImMessageRequest` / `SendMessageRequest` / `PermissionReplyRequest` 字段 + 3 个 controller + `InboundProcessingService` 3 方法签名 + `ImSessionManager.createSessionAsync` business 立即发送分支透传 | 7 + 5 测试 |
| PR3 — 汇聚层 + 文档 | `BusinessScopeStrategy.buildInvoke` + `extractObjectField` + 协议文档替换 + 集成测试 | 1 + 3 测试 + 1 文档 |

> 也可单 PR 提交（diff 大但原子性强）。最终拆分由 review 决定。

### 9.2 不动清单（明确写出避免越界）

- `CloudRequestContext`（`extParameters: Map<String,Object>` 已支持嵌套）
- `DefaultCloudRequestStrategy`（`valueToTree(ext)` 已正确处理嵌套）
- `PersonalScopeStrategy` / `OpenCodeEventTranslator` / `CloudEventTranslator`
- `GatewayRelayService.buildInvokeMessage`（personal 路径不剥离 `businessExtParam`，原样透下游 ai-gateway）
- `SkillSessionController`（create/close/abort 不属于 chat/question/permission）
- `processRebuild` 主体逻辑（rebuild 语义不引入 `businessExtParam` 入参 / 不透传到 cloud body）；**唯一例外**：因 `createSessionAsync` 签名变更，`processRebuild` 内部对 `createSessionAsync` 的调用（`InboundProcessingService.java:526-527`）需末位补 `null` 以保持编译，详见 §5.4
- `BusinessScopeStrategy.extractField` 私有方法（保留不动；新增 `extractObjectField`）
- `PayloadBuilder.buildPayload(Map<String,String>)` 旧重载本身**保留不删**；本次会把其中 **7 处** 涉及 `businessExtParam` 透传的调用切到新重载 `buildPayloadWithObjects`（`SkillMessageController` 3 处、`InboundProcessingService` 3 处、`ImSessionManager` 1 处），剩余 **4 处**（`SkillSessionController` 3 处 + `ImSessionManager` 的 `CREATE_SESSION` 1 处）继续走 String-only 旧重载，零迁移
- `InvokeCommand` record（不动签名，新字段塞进 payload string）
- `SessionRebuildService` / pending 队列存储（保持纯文本，不升级 schema；接受 legacy replay 的 `businessExtParam` 丢失，详见 D17）
- `GatewayMessageRouter.retryPendingMessages`（仅重发纯文本，对应 miniapp rebuild 路径 D18）
- `ImSessionManager.requestToolSession` / `findOrCreateSession` / `ensureToolSession`（rebuild/同步等待路径不带 ext）
- 数据库 schema（不持久化）
- WS `PERMISSION_REPLY` 广播 StreamMessage（不带 `businessExtParam`，前端展示用）

### 9.3 YAGNI 列表（不引入的"防御"，避免日后纠结）

- ❌ 入口 `validate()` 不校验 `businessExtParam` 类型 / 大小 / 字段名白名单
- ❌ `BusinessScopeStrategy` 不做 `businessExtParam` 内容 schema 校验
- ❌ 不新增异常类型（`InvalidExtParamException` 等）
- ❌ 不新增 metric / counter
- ❌ 不引入 `ExtParameters` 值对象 / `PlatformExtParamProvider` 接口（按 P1 透传式架构，未来需要时再做）
- ❌ 不持久化 `businessExtParam` 到数据库
- ❌ `chatHistory` 中历史消息不带 `businessExtParam`
- ❌ 不升级 pending/replay 存储 schema（不把 `SessionRebuildService` 的纯文本队列改为结构化对象；接受 legacy replay 与 miniapp rebuild 路径的 `businessExtParam` 丢失，详见 D17 / D18）
- ❌ 不在 `GatewayRelayService.buildInvokeMessage` 内对 personal 路径剥离 `businessExtParam`（依赖下游 ai-gateway 兼容未知字段，详见 D16）

---

## 10. 风险与回滚

### 10.1 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 远端云端助理消费 `extParameters.businessExtParam` 与 skill-server 透传值不匹配 | 业务逻辑异常 | 远端团队联调；本次 skill-server 仅保证字段恒在为 object |
| 业务方在 `businessExtParam` 内塞敏感数据 + INFO 日志直打 → 落 ELK 合规风险 | 数据保护 | 与业务方约定：不在该字段放敏感数据；建议大小不超 4KB（超大对象走附件协议）；远期可考虑切到 DEBUG（届时另开工单） |
| `record ImMessageRequest` 加字段 → 所有外部 positional 构造编译失败 | 编译 | 实测共 **7 处** 测试代码调用（`ImInboundControllerTest.java:42/66/86/113/126/139/152`），全部末位补 `null`，工作量小 |
| `Map.of(...)` 改 `LinkedHashMap` → 序列化顺序变化 | 字段顺序 | Jackson 默认按 put 顺序输出，与原 `Map.of` 顺序一致；测试用 `assertJsonEquals` 不依赖顺序 |
| Personal scope 出站 payload 含 `businessExtParam` 字段透到下游 ai-gateway | 协议冗余 / 下游兼容性 | 接受设计：依赖 ai-gateway 对未知字段宽容；否则需在 `GatewayRelayService.buildInvokeMessage` 显式剥离（已通过 D16 决策放弃此剥离）；联调阶段验证下游容忍 |
| 任意 scope 的 pending/replay/rebuild/session_created 重发路径丢失 `businessExtParam` | 业务体验 | 已通过 D17 / D18 / **D19** 显式接受丢失；覆盖：①IM/external personal 助手首次 `createSessionAsync` 走 `requestToolSession`；②`InboundProcessingService.processChat` 情况 B personal 降级；③business legacy replay；④miniapp rebuild；⑤`GatewayMessageRouter` 在 `session_created` 后重发。调用方需在 toolSession ready 后重发；这条路径属于异常恢复，业务方不应依赖 ext 在 replay 路径生效 |
| `ImSessionManager.createSessionAsync` 改造未到位 → IM/external 首条新会话消息丢失 ext | 业务体验 | PR2 必须改 `createSessionAsync` business 立即发送分支；ⓕ-3 集成测试覆盖 |

### 10.2 回滚

本次改动**完全向后兼容**——老调用方不传 `businessExtParam` 字段时，系统行为与现状等价（cloud body 多了空的 `extParameters` 但不影响远端处理）。

回滚操作：单 commit revert 即可，无数据迁移。

---

## 11. 与既有设计的关系

- **2026-04-22 senderUserAccount envelope 迁移**：本次 `businessExtParam` 沿用相同的"信封顶级"位置规则，三入口字段位置一致
- **2026-04-07 cloud-agent-protocol**：本次替换 `extParameters` 章节的扁平字段说明为嵌套子字段说明
- **scope 分发架构**（`AssistantScopeDispatcher` / `BusinessScopeStrategy` / `PersonalScopeStrategy`）：本次仅在 `BusinessScopeStrategy` 内部新增组装逻辑，不动分发架构
- **`StreamMessageEmitter`**：本次不涉及（`businessExtParam` 仅出现在 `InvokeCommand.payload` 出站方向，不在 `StreamMessage` 入站方向）

---

## 12. 待办（实施阶段处理）

- [ ] PR1：`PayloadBuilder.buildPayloadWithObjects` 实现 + 5 用例测试
- [ ] PR2：4 个 DTO 字段新增（`ImMessageRequest` 末位 record / 3 个 `@Data` 字段）
- [ ] PR2：3 个 controller 透传逻辑（IM 1 处、external 3 处、miniapp 3 处）
- [ ] PR2：`InboundProcessingService` 3 方法签名 + `dispatchChatToGateway` 私有方法签名（legacy 重放调用点显式传 `null`，遵循 D17）
- [ ] PR2：`SkillMessageController` 三处 payload 构造改用 `LinkedHashMap<String,Object>`：chat 分支当前是 `LinkedHashMap<String,String>` 改泛型；question_reply 分支与 `replyPermission` 当前是 `Map.of(...)` 静态构造，改为可变 `LinkedHashMap<String,Object>`
- [ ] PR2：**`ImSessionManager.createSessionAsync` 签名末位追加 `JsonNode businessExtParam`**；business 立即发送分支（`ImSessionManager.java:146-166`）改用 `LinkedHashMap<String,Object>` + `buildPayloadWithObjects`；`InboundProcessingService.processChat` 调用点透传 ext
- [ ] PR2：4 个 controller 测试 + 3 个 service 测试 + `ImSessionManager.createSessionAsync` business 立即发送透传测试
- [ ] PR3：`BusinessScopeStrategy.extractObjectField` + `buildInvoke` 修改
- [ ] PR3：`BusinessScopeStrategyTest` 9 用例
- [ ] PR3：`CloudRequestBuilderTest` 1 用例
- [ ] PR3：集成测试 ⓕ-1（IM E2E business chat → cloud body）+ ⓕ-2（personal scope `GatewayRelayService.buildInvokeMessage` 不剥离）+ ⓕ-3（`createSessionAsync` 立即发送透传）
- [ ] PR3：协议文档 `2026-04-07-cloud-agent-protocol.md` line 147–205 / 270–275 / 1137 替换
- [ ] 修复 `ImInboundControllerTest` 中 **7 处** `new ImMessageRequest(...)` positional 构造（line 42/66/86/113/126/139/152），末位补 `null`
- [ ] `mvn -pl skill-server test -DfailIfNoTests=false` 全绿
