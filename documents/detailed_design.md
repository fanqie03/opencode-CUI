# OpenCode-CUI 详细设计文档

> 基于 [architecture_design.md](./architecture_design.md)、[scope_definition.md](./scope_definition.md) 和 [business_analysis.md](./business_analysis.md) 产出。

---

## 一、变更概述

本文档描述 skill-server 在支持**场景二（数字分身进群）** 和 **单聊** 场景时，需要新增和修改的所有模块的详细设计。

### 1.1 核心变更清单

| 变更类型 | 模块                              | 说明                                                                                                        |
| -------- | --------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| 新增     | `ImInboundController`             | REST 消息接收端点                                                                                           |
| 新增     | `ImMessageRequest`                | 入站请求模型（仅文本消息）                                                                                  |
| 新增     | `ImTokenAuthInterceptor`          | Token 认证拦截器                                                                                            |
| 新增     | `AssistantAccountResolverService` | assistantAccount → ak 解析 + Redis 缓存                                                                     |
| 新增     | `ImSessionManager`                | findOrCreate session 核心逻辑                                                                               |
| 新增     | `ContextInjectionService`         | Prompt 模板 + 群聊历史注入（仅场景二，可开关控制）                                                          |
| 新增     | `ImOutboundService`               | IM 纯文本发送（Token 认证）                                                                                 |
| 修改     | `SkillSession`                    | 新增 `businessSessionDomain`/`businessSessionType`/`businessSessionId`/`assistantAccount`；删除 `imGroupId` |
| 修改     | `SkillSessionRepository`          | 新增三元组查询方法；修改 insert/filter 映射                                                                 |
| 修改     | `SkillSessionService`             | 新增 `findByDomainSessionIdAndAk`；修改 `createSession` 签名                                                |
| 修改     | `GatewayMessageRouter`            | 回复路由分支（按 businessSessionDomain + businessSessionType）                                              |
| 修改     | `MessagePersistenceService`       | 群聊跳过持久化                                                                                              |
| 新增     | `V6__session_chat_triple.sql`     | DB 迁移：三元组字段 + ENUM→VARCHAR + 数据迁移                                                               |

> **本期延后的功能**（后续迭代）：
> - ~~`MessageFormatConverter`~~：消息格式转换（Markdown→纯文本 等）
> - ~~`ImOutboundMessage`~~：出站消息模型（含图片、@人字段）
> - @人字段填充
> - 图片消息的输入和输出
> - `SkillMessage.ContentType` 新增 `IMAGE`

---

## 二、数据模型详细设计

### 2.1 SkillSession 实体变更

**当前结构**（V1~V5 迁移后）：

```java
public class SkillSession {
    private Long id;
    private String userId;
    private String ak;
    private String toolSessionId;
    private String title;
    private Status status;       // ACTIVE, IDLE, CLOSED
    private String imGroupId;    // ← 待删除
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
}
```

**目标结构**（V6 迁移后）：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String userId;
    private String ak;
    private String toolSessionId;
    private String title;

    @Builder.Default
    private Status status = Status.ACTIVE;

    // -------- 三元组 + assistantAccount（V6 新增）--------
    @Builder.Default
    private String businessSessionDomain = "miniapp";   // 来源场景: miniapp / im / meeting / doc
    private String businessSessionType;                   // 聊天类型: group / direct（miniapp 场景为 null）
    private String businessSessionId;                     // 平台定义的会话标识
    private String assistantAccount;                      // 数字分身的平台账号标识，出站时使用
    // -------- 原 imGroupId 字段已删除 --------

    private LocalDateTime createdAt;
    @JsonProperty("updatedAt")
    private LocalDateTime lastActiveAt;

    public enum Status {
        ACTIVE, IDLE, CLOSED
    }

    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }
}
```

**字段说明**：

| 字段                  | 类型         | 约束                     | 说明                                     |
| --------------------- | ------------ | ------------------------ | ---------------------------------------- |
| businessSessionDomain | VARCHAR(32)  | NOT NULL, 默认 `miniapp` | 来源场景维度，VARCHAR 保证扩展无需 DDL   |
| businessSessionType   | VARCHAR(32)  | NULL                     | 聊天类型维度；miniapp 场景不填           |
| businessSessionId     | VARCHAR(128) | NULL                     | 平台方的会话标识，域内唯一               |
| assistantAccount      | VARCHAR(128) | NULL                     | 数字分身的平台账号标识，出站发消息时使用 |

**唯一索引**：`idx_biz_domain_session_ak(business_session_domain, business_session_id, ak)` — 保证同一场景+会话+分身只有一个 session。

### 2.2 SkillMessage.ContentType

```java
public enum ContentType {
    MARKDOWN, CODE, PLAIN
    // IMAGE 将在后续迭代中新增
}
```

### 2.3 设计原则：数据库不用 ENUM

> **原则**：所有枚举类型字段在数据库层使用 `VARCHAR` 存储，枚举约束统一在 Java 应用层完成。
>
> **理由**：
> - 新增枚举值时无需执行 DDL 变更（`ALTER TABLE ... MODIFY COLUMN`）
> - MySQL 5.7 修改 ENUM 需要重建表，大表场景下有锁表风险
> - 与 `business_session_domain`/`business_session_type` 使用 VARCHAR 的决策保持一致
>
> **影响范围**：现有 4 个 ENUM 字段需迁移为 VARCHAR
>
> | 表                | 字段           | 原 ENUM 值                            | 迁移后类型     |
> | ----------------- | -------------- | ------------------------------------- | -------------- |
> | `skill_definition`| `status`       | `ACTIVE`, `DISABLED`                  | VARCHAR(16)    |
> | `skill_session`   | `status`       | `ACTIVE`, `IDLE`, `CLOSED`            | VARCHAR(16)    |
> | `skill_message`   | `role`         | `USER`, `ASSISTANT`, `SYSTEM`, `TOOL` | VARCHAR(16)    |
> | `skill_message`   | `content_type` | `MARKDOWN`, `CODE`, `PLAIN`           | VARCHAR(16)    |

### 2.4 数据库迁移脚本

#### V6__session_chat_triple.sql

```sql
-- ============================================================
-- V6: 会话三元组改造 + 全局 ENUM → VARCHAR 迁移
-- ============================================================

-- 1. 新增三元组字段 + assistant_account
ALTER TABLE skill_session
  ADD COLUMN business_session_domain VARCHAR(32) NOT NULL DEFAULT 'miniapp'
      COMMENT '来源场景（miniapp/im/meeting/doc）',
  ADD COLUMN business_session_type VARCHAR(32) NULL
      COMMENT '聊天类型（group/direct）',
  ADD COLUMN business_session_id VARCHAR(128) NULL
      COMMENT '平台定义的会话标识',
  ADD COLUMN assistant_account VARCHAR(128) NULL
      COMMENT '数字分身的平台账号标识';

-- 2. 迁移旧数据：im_group_id → business_session_id
UPDATE skill_session
  SET business_session_id = im_group_id
  WHERE im_group_id IS NOT NULL;

-- 3. 删除旧字段
ALTER TABLE skill_session
  DROP COLUMN im_group_id;

-- 4. 添加三元组唯一索引
ALTER TABLE skill_session
  ADD UNIQUE INDEX idx_biz_domain_session_ak (business_session_domain, business_session_id, ak);

-- 5. ENUM → VARCHAR 迁移（消除所有数据库层枚举约束）
--    skill_definition.status
ALTER TABLE skill_definition
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
      COMMENT '状态（应用层枚举：ACTIVE/DISABLED）';

--    skill_session.status
ALTER TABLE skill_session
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
      COMMENT '状态（应用层枚举：ACTIVE/IDLE/CLOSED）';

--    skill_message.role
ALTER TABLE skill_message
  MODIFY COLUMN role VARCHAR(16) NOT NULL
      COMMENT '角色（应用层枚举：USER/ASSISTANT/SYSTEM/TOOL）';

--    skill_message.content_type
ALTER TABLE skill_message
  MODIFY COLUMN content_type VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN'
      COMMENT '内容类型（应用层枚举：MARKDOWN/CODE/PLAIN/IMAGE）';
```

> **注意**：ENUM → VARCHAR 迁移在 MySQL 5.7 下是原地修改（in-place），不会丢失数据。
> 现有的枚举值（如 `ACTIVE`、`USER`）会自动保留为 VARCHAR 字符串。

#### V7__image_content_type.sql

> **V7 迁移脚本**：本期无需 V7 迁移。IMAGE 类型将在后续迭代中随图片功能一起引入。

---

## 三、接口详细设计

### 3.1 REST 入站接口

#### ImInboundController

```java
@Slf4j
@RestController
@RequestMapping("/api/inbound")
public class ImInboundController {

    private final AssistantAccountResolverService resolverService;
    private final ImSessionManager sessionManager;
    private final ContextInjectionService contextService;
    private final GatewayRelayService gatewayRelayService;
    private final PayloadBuilder payloadBuilder;

    /**
     * 统一消息接收接口
     *
     * 处理流程：
     * 1. assistantAccount → ak 解析
     * 2. findOrCreate session（三元组）
     * 3. [群聊] 上下文注入
     * 4. Gateway invoke → Agent
     *
     * Agent 回复由 GatewayMessageRouter 异步路由到 ImOutboundService。
     */
    @PostMapping("/messages")
    public ApiResponse<Void> receiveMessage(@RequestBody ImMessageRequest request) {
        log.info("Inbound message received: domain={}, type={}, sessionId={}, assistantAccount={}",
                request.businessDomain(), request.sessionType(), request.sessionId(), request.assistantAccount());

        // 1. assistantAccount → ak
        String ak = resolverService.resolveAk(request.assistantAccount());
        if (ak == null) {
            log.warn("Cannot resolve ak for assistantAccount={}", request.assistantAccount());
            return ApiResponse.error("INVALID_ASSISTANT_ACCOUNT", "无法解析数字分身标识");
        }

        // 2. findOrCreate session
        SkillSession session = sessionManager.findOrCreateSession(
                request.businessDomain(), request.sessionType(),
                request.sessionId(), ak,
                request.assistantAccount());

        // 3. 构建 Prompt
        //    开关 injection-enabled=true  → 群聊历史 + 当前消息组装为 Prompt
        //    开关 injection-enabled=false → 直接透传原始消息内容
        String prompt = contextService.resolvePrompt(
                request.sessionType(), request.content(),
                request.chatHistory());

        // 4. 发送给 Agent（本期仅支持文本）
        String payload = payloadBuilder.buildChatPayload(prompt);
        InvokeCommand command = new InvokeCommand(
                ak, session.getUserId(),
                String.valueOf(session.getId()),
                GatewayActions.CHAT, payload);
        gatewayRelayService.sendInvokeToGateway(command);

        return ApiResponse.ok();
    }
}
```

#### ImMessageRequest — 入站请求模型

```java
/**
 * IM 服务端调用 skill-server 的统一消息请求体。
 *
 * 场景二（群聊）和单聊共用此模型，通过 sessionType 区分。
 * 本期仅支持文本消息，图片消息将在后续迭代中支持。
 */
public record ImMessageRequest(
    String businessDomain,              // 来源场景: "im" / "meeting" / "doc"
    String sessionType,                 // 会话类型: "group" / "direct"
    String sessionId,                   // 平台定义的会话标识（域内唯一）
    String assistantAccount,            // 数字分身的平台账号标识
    String content,                     // 消息文本内容
    List<ChatMessage> chatHistory       // 群聊历史（场景二可选，单聊不传）
) {
    /**
     * 群聊上下文中的历史消息条目
     */
    public record ChatMessage(
        String senderAccount,
        String senderName,
        String content,
        long timestamp
    ) {}
}
```

### 3.2 Token 认证拦截器

#### ImTokenAuthInterceptor

```java
/**
 * 校验 IM 服务端请求的 Token 合法性。
 *
 * IM 服务端在调用 /api/inbound/** 时，请求头中带 Authorization: Bearer {token}，
 * 此拦截器校验 token 是否与配置的 inbound-token 一致。
 */
@Slf4j
@Component
public class ImTokenAuthInterceptor implements HandlerInterceptor {

    @Value("${skill.im.inbound-token}")
    private String inboundToken;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            log.warn("IM request missing Authorization header: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing token\"}");
            return false;
        }

        String token = auth.substring(7);
        if (!inboundToken.equals(token)) {
            log.warn("IM request invalid token: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Invalid token\"}");
            return false;
        }

        return true;
    }
}
```

**注册拦截器**（在 WebMvcConfigurer 中）：

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ImTokenAuthInterceptor imTokenAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(imTokenAuthInterceptor)
                .addPathPatterns("/api/inbound/**");
    }
}
```

---

## 四、服务层详细设计

### 4.1 AssistantAccountResolverService — 数字分身账号解析

```java
/**
 * assistantAccount → ak 解析服务。
 *
 * 解析策略：调用第三方接口 + Redis 缓存（TTL 可配）。
 */
@Slf4j
@Service
public class AssistantAccountResolverService {

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${skill.assistant.resolve-url}")
    private String resolveUrl;

    @Value("${skill.assistant.cache-ttl-minutes:30}")
    private int cacheTtlMinutes;

    private static final String CACHE_KEY_PREFIX = "assistantAccount:ak:";

    public AssistantAccountResolverService(RestTemplate restTemplate,
                                           StringRedisTemplate redisTemplate) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 解析 assistantAccount 为 ak。
     *
     * @param assistantAccount 数字分身的平台账号标识
     * @return 对应的 ak，解析失败返回 null
     */
    public String resolveAk(String assistantAccount) {
        if (assistantAccount == null || assistantAccount.isBlank()) {
            return null;
        }

        // 1. 查 Redis 缓存
        String cacheKey = CACHE_KEY_PREFIX + assistantAccount;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("assistantAccount={} → ak={} (cached)", assistantAccount, cached);
            return cached;
        }

        // 2. 调第三方接口
        try {
            Map<String, Object> body = Map.of("assistantAccount", assistantAccount);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    resolveUrl, request, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode data = response.getBody().path("data");
                String ak = data.path("ak").asText(null);
                if (ak != null && !ak.isBlank()) {
                    // 3. 写入 Redis 缓存
                    redisTemplate.opsForValue().set(cacheKey, ak,
                            Duration.ofMinutes(cacheTtlMinutes));
                    log.info("assistantAccount={} → ak={} (resolved & cached, TTL={}min)",
                            assistantAccount, ak, cacheTtlMinutes);
                    return ak;
                }
            }
            log.warn("Failed to resolve assistantAccount={}: response={}",
                    assistantAccount, response.getBody());
            return null;
        } catch (Exception e) {
            log.error("Error resolving assistantAccount={}: {}", assistantAccount, e.getMessage(), e);
            return null;
        }
    }
}
```

### 4.2 ImSessionManager — 会话自动管理

```java
/**
 * 核心会话管理服务：基于三元组 findOrCreate。
 *
 * 流程：
 * 1. 用 businessDomain + sessionId + ak 查找已有 session
 * 2. 找到 → 校验 toolSessionId 有效性 → 无效则触发 rebuild
 * 3. 找不到 → 创建新 session → 通过 Gateway 创建 ToolSession → 同步等待绑定
 *
 * 同步等待机制：
 * 使用 CompletableFuture + ConcurrentHashMap 实现。
 * ImInboundController 收到消息 → findOrCreate 触发 create_session →
 * 阻塞等待直到 GatewayMessageRouter.handleSessionCreated 回调完成绑定。
 */
@Slf4j
@Service
public class ImSessionManager {

    private final SkillSessionService sessionService;
    private final SkillSessionRepository sessionRepository;
    private final GatewayRelayService gatewayRelayService;
    private final SessionRebuildService sessionRebuildService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;

    @Value("${skill.session.auto-create-timeout-seconds:30}")
    private int autoCreateTimeoutSeconds;

    /**
     * 等待 ToolSession 创建完成的 Future 注册表。
     * Key: welinkSessionId (String), Value: CompletableFuture<String> (toolSessionId)
     */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingCreations
            = new ConcurrentHashMap<>();

    /**
     * 核心方法：findOrCreate
     *
     * @param businessDomain 来源场景（im / meeting / doc）
     * @param sessionType    会话类型（group / direct）
     * @param sessionId      平台会话标识
     * @param ak              Agent 密钥标识
     * @param assistantAccount 数字分身平台账号标识
     * @return 已存在或新创建的 SkillSession（toolSessionId 已绑定）
     * @throws RuntimeException 如果创建超时或失败
     */
    public SkillSession findOrCreateSession(String businessDomain, String sessionType,
                                             String sessionId, String ak,
                                             String assistantAccount) {
        // 1. 查找已有 session
        SkillSession existing = sessionRepository.findByDomainSessionIdAndAk(
                businessDomain, sessionId, ak);

        if (existing != null) {
            log.info("Found existing session: id={}, domain={}, sessionId={}, ak={}",
                    existing.getId(), businessDomain, sessionId, ak);

            // 校验 toolSessionId 是否有效
            if (existing.getToolSessionId() == null
                    || existing.getToolSessionId().isBlank()) {
                log.warn("Session {} has no valid toolSessionId, rebuilding", existing.getId());
                rebuildAndWait(existing);
            }

            existing.touch();
            sessionService.touchSession(existing.getId());
            return existing;
        }

        // 2. 创建新 session
        log.info("Creating new session: domain={}, type={}, sessionId={}, ak={}, assistantAccount={}",
                businessDomain, sessionType, sessionId, ak, assistantAccount);

        SkillSession session = SkillSession.builder()
                .id(snowflakeIdGenerator.nextId())
                .ak(ak)
                .businessSessionDomain(businessDomain)
                .businessSessionType(sessionType)
                .businessSessionId(sessionId)
                .assistantAccount(assistantAccount)
                .status(SkillSession.Status.ACTIVE)
                .build();

        sessionRepository.insert(session);

        // 3. 创建 ToolSession（同步等待）
        String sessionIdStr = String.valueOf(session.getId());
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingCreations.put(sessionIdStr, future);

        try {
            // 发送 create_session 到 Gateway
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", "");
            String payloadStr = objectMapper.writeValueAsString(payload);
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(ak, userId, sessionIdStr,
                            GatewayActions.CREATE_SESSION, payloadStr));

            // 等待 session_created 回调
            String toolSessionId = future.get(autoCreateTimeoutSeconds, TimeUnit.SECONDS);
            session.setToolSessionId(toolSessionId);
            log.info("ToolSession created and bound: welinkSession={}, toolSession={}",
                    sessionIdStr, toolSessionId);
            return session;
        } catch (TimeoutException e) {
            log.error("ToolSession creation timeout for session {}", sessionIdStr);
            throw new RuntimeException("会话创建超时，请稍后重试");
        } catch (Exception e) {
            log.error("ToolSession creation failed for session {}: {}",
                    sessionIdStr, e.getMessage(), e);
            throw new RuntimeException("会话创建失败：" + e.getMessage());
        } finally {
            pendingCreations.remove(sessionIdStr);
        }
    }

    /**
     * 由 GatewayMessageRouter.handleSessionCreated 调用，
     * 完成 Future 通知等待中的 findOrCreate。
     */
    public void notifySessionCreated(String sessionId, String toolSessionId) {
        CompletableFuture<String> future = pendingCreations.get(sessionId);
        if (future != null) {
            future.complete(toolSessionId);
        }
    }

    private void rebuildAndWait(SkillSession session) {
        // 复用 SessionRebuildService 的 rebuild 逻辑
        // 通过 CompletableFuture 同步等待
        // ...
    }
}
```

**Repository 新增方法**：

```java
// SkillSessionRepository.java 新增
SkillSession findByDomainSessionIdAndAk(@Param("businessDomain") String businessDomain,
                                         @Param("sessionId") String sessionId,
                                         @Param("ak") String ak);
```

**MyBatis XML 映射**：

```xml
<select id="findByDomainSessionIdAndAk" resultMap="sessionResultMap">
    SELECT * FROM skill_session
    WHERE business_session_domain = #{businessDomain}
      AND business_session_id = #{sessionId}
      AND ak = #{ak}
      AND status != 'CLOSED'
    LIMIT 1
</select>
```

### 4.3 ContextInjectionService — 上下文注入（仅场景二，可开关控制）

> **设计要点**：通过配置 `skill.context.injection-enabled`（默认 `true`）控制上下文注入行为。
> - **开启时**：将群聊历史 + 当前消息使用 Prompt 模板组装后发给 Agent。
> - **关闭时**：直接透传原始消息内容，不做任何组装。适用于 IM 平台已实现上下文管理的场景。

```java
/**
 * 群聊上下文注入服务。
 *
 * 核心开关：skill.context.injection-enabled
 * - true（默认）: 使用 Prompt 模板将群聊历史 + 当前消息组装为完整 Prompt
 * - false: 直接透传原始消息，不做组装（适用于 IM 已管理上下文的场景）
 *
 * 模板加载顺序：
 * 1. 配置文件指定路径 (skill.context.templates.group-chat)
 * 2. classpath 默认模板 (templates/group-chat-prompt.txt)
 *
 * Prompt 模板变量：
 * - ${chatHistory}: 格式化后的群聊历史
 * - ${currentMessage}: 当前 @消息内容
 *
 * 历史消息截断策略：
 * - 最多保留 max-history-messages 条（默认 20）
 * - 超出则只保留最近的 N 条
 */
@Slf4j
@Service
public class ContextInjectionService {

    @Value("${skill.context.injection-enabled:true}")
    private boolean injectionEnabled;

    @Value("${skill.context.max-history-messages:20}")
    private int maxHistoryMessages;

    private final String groupChatTemplate;

    public ContextInjectionService(
            @Value("${skill.context.templates.group-chat:classpath:templates/group-chat-prompt.txt}")
            Resource templateResource) throws IOException {
        this.groupChatTemplate = StreamUtils.copyToString(
                templateResource.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * 统一入口：根据 sessionType 和开关决定是否注入上下文。
     *
     * @param sessionType    会话类型（group / direct）
     * @param currentMessage 当前消息内容
     * @param chatHistory    群聊历史（可为 null）
     * @return 最终发给 Agent 的 Prompt 内容
     */
    public String resolvePrompt(String sessionType, String currentMessage,
                                List<ImMessageRequest.ChatMessage> chatHistory) {
        // 非群聊 → 直接透传
        if (!"group".equals(sessionType)) {
            return currentMessage;
        }

        // 群聊 + 开关关闭 → 直接透传
        if (!injectionEnabled) {
            log.info("Context injection disabled, passing through raw message");
            return currentMessage;
        }

        // 群聊 + 开关开启 + 无历史 → 直接透传
        if (chatHistory == null || chatHistory.isEmpty()) {
            return currentMessage;
        }

        // 群聊 + 开关开启 + 有历史 → 模板组装
        return buildPrompt(currentMessage, chatHistory);
    }

    /**
     * 使用 Prompt 模板组装群聊上下文。
     */
    private String buildPrompt(String currentMessage,
                               List<ImMessageRequest.ChatMessage> chatHistory) {
        // 截断过长的历史
        List<ImMessageRequest.ChatMessage> truncated = chatHistory;
        if (chatHistory.size() > maxHistoryMessages) {
            truncated = chatHistory.subList(
                    chatHistory.size() - maxHistoryMessages, chatHistory.size());
            log.info("Chat history truncated: {} → {} messages",
                    chatHistory.size(), maxHistoryMessages);
        }

        // 格式化历史为文本
        StringBuilder historyText = new StringBuilder();
        for (ImMessageRequest.ChatMessage msg : truncated) {
            historyText.append(String.format("[%s]: %s\n",
                    msg.senderName(), msg.content()));
        }

        // 替换模板变量
        return groupChatTemplate
                .replace("${chatHistory}", historyText.toString())
                .replace("${currentMessage}", currentMessage);
    }
}
```

**默认 Prompt 模板**（`templates/group-chat-prompt.txt`）：

```text
以下是群聊的最近对话记录：

${chatHistory}

用户当前的问题是：
${currentMessage}

请根据以上群聊上下文，回答用户的问题。如果群聊记录中有相关信息，请结合回答。
```

### 4.4 MessageFormatConverter — 消息格式转换器

> **本期延后**：消息格式转换（Markdown→纯文本、图片提取、@人字段填充）将在后续迭代中实现。
>
> 本期 Agent 回复的文本内容直接透传给 IM 平台，不做任何格式转换。

### 4.5 ImOutboundService — IM 出站服务

```java
/**
 * IM 出站消息发送服务。
 *
 * 对接 IM 平台真实 API：
 * - 单聊: POST {im-api-url}/v1/welinkim/im-service/chat/app-user-chat
 * - 群聊: POST {im-api-url}/v1/welinkim/im-service/chat/app-group-chat
 *
 * 请求体字段:
 * - appMsgId      : 消息标识（发送方生成，用 UUID）
 * - senderAccount : 数字分身平台账号（= assistantAccount）
 * - sessionId     : 目标会话 ID（= businessSessionId）
 * - contentType   : 消息类型（13 = 纯文本）
 * - content       : 消息文本内容
 * - clientSendTime: 发送时间戳（毫秒）
 *
 * 响应体字段:
 * - msgId          : IM 服务端生成的消息 ID (long)
 * - clientMsgId    : 对应入参 appMsgId
 * - serverSendTime : 服务端发送时间戳 (long)
 * - error          : { errorCode, errorMsg }（业务错误时非 null）
 */
@Slf4j
@Service
public class ImOutboundService {

    private static final int CONTENT_TYPE_TEXT = 13;

    private static final String PATH_USER_CHAT  = "/v1/welinkim/im-service/chat/app-user-chat";
    private static final String PATH_GROUP_CHAT = "/v1/welinkim/im-service/chat/app-group-chat";

    private final RestTemplate restTemplate;

    @Value("${skill.im.api-url}")
    private String imApiUrl;

    @Value("${skill.im.token}")
    private String imToken;

    public ImOutboundService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 发送纯文本消息到 IM 平台。
     * 根据 sessionType 自动选择单聊/群聊接口。
     *
     * @param sessionType      会话类型: "direct" → 单聊, "group" → 群聊
     * @param sessionId        目标会话标识 (businessSessionId)
     * @param content          消息文本内容
     * @param assistantAccount 发送者身份（数字分身的平台账号）
     * @return 发送是否成功
     */
    public boolean sendTextToIm(String sessionType, String sessionId,
                                 String content, String assistantAccount) {
        // 1. 根据 sessionType 选择端点
        String path = "group".equals(sessionType) ? PATH_GROUP_CHAT : PATH_USER_CHAT;
        String sendUrl = imApiUrl + path;

        // 2. 构建请求体
        String appMsgId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appMsgId", appMsgId);
        body.put("senderAccount", assistantAccount);
        body.put("sessionId", sessionId);
        body.put("contentType", CONTENT_TYPE_TEXT);
        body.put("content", content);
        body.put("clientSendTime", System.currentTimeMillis());

        // 3. 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(imToken);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("IM outbound sending: sessionType={}, sessionId={}, appMsgId={}, endpoint={}",
                sessionType, sessionId, appMsgId, path);

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    sendUrl, request, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode respBody = response.getBody();
                JsonNode error = respBody.path("error");

                // 检查业务错误
                if (!error.isMissingNode() && error.has("errorCode")) {
                    String errorCode = error.path("errorCode").asText();
                    String errorMsg = error.path("errorMsg").asText();
                    log.error("IM outbound biz error: sessionId={}, appMsgId={}, "
                            + "errorCode={}, errorMsg={}",
                            sessionId, appMsgId, errorCode, errorMsg);
                    return false;
                }

                long msgId = respBody.path("msgId").asLong();
                long serverSendTime = respBody.path("serverSendTime").asLong();
                log.info("IM outbound success: sessionId={}, appMsgId={}, "
                        + "msgId={}, serverSendTime={}",
                        sessionId, appMsgId, msgId, serverSendTime);
                return true;
            } else {
                log.error("IM outbound HTTP error: sessionId={}, status={}, body={}",
                        sessionId, response.getStatusCode(), response.getBody());
                return false;
            }
        } catch (Exception e) {
            log.error("IM outbound exception: sessionId={}, appMsgId={}, error={}",
                    sessionId, appMsgId, e.getMessage(), e);
            return false;
        }
    }
}
```

---

## 五、现有模块修改详细设计

### 5.1 GatewayMessageRouter — 回复路由分支

**修改点**：`handleAssistantToolEvent` / `handleToolDone` 方法内增加路由判断。

**当前逻辑**：

```
route(type, ak, userId, node)
  → handleToolEvent → translateEvent → broadcastStreamMessage
      → RedisMessageBroker → WS push 到 miniapp
      → MessagePersistenceService.persistIfFinal
```

**修改后逻辑**：

```
route(type, ak, userId, node)
  → handleToolEvent → translateEvent
      → resolveSession(sessionId) → 获取 businessSessionDomain + businessSessionType
      |
      ├─ businessSessionDomain == "miniapp"
      |     → broadcastStreamMessage (现有逻辑)
      |     → MessagePersistenceService.persistIfFinal (现有逻辑)
      |
      ├─ businessSessionDomain == "im" && businessSessionType == "group"
      |     → ImOutboundService.sendTextToIm（直接透传文本）
      |     → (不做持久化)
      |
      └─ businessSessionDomain == "im" && businessSessionType == "direct"
            → ImOutboundService.sendTextToIm（直接透传文本）
            → MessagePersistenceService.persistIfFinal (持久化)
```

**关键代码变更**：

```java
// GatewayMessageRouter.java — 在 handleAssistantToolEvent 内新增路由判断

private void handleAssistantToolEvent(String sessionId, String userId, StreamMessage msg) {
    // ... 现有的 seq/buffer 逻辑 ...

    Long sessionIdLong = ProtocolUtils.parseSessionId(sessionId);
    SkillSession session = (sessionIdLong != null)
            ? sessionService.findByIdSafe(sessionIdLong) : null;

    String domain = (session != null) ? session.getBusinessSessionDomain() : "miniapp";
    String sessionType = (session != null) ? session.getBusinessSessionType() : null;

    if ("miniapp".equals(domain)) {
        // 现有路径：WS 广播 + 持久化
        broadcastStreamMessage(sessionId, userId, msg);
        persistenceService.persistIfFinal(sessionIdLong, msg);
    } else if ("im".equals(domain)) {
        // IM 路径：直接文本透传出站（本期不做格式转换）
        handleImOutbound(session, msg);
        // 单聊需要持久化
        if ("direct".equals(sessionType)) {
            persistenceService.persistIfFinal(sessionIdLong, msg);
        }
    }
    // meeting / doc: 未来扩展点
}

/**
 * IM 出站处理（本期：直接透传 Agent 回复文本）
 */
private void handleImOutbound(SkillSession session, StreamMessage msg) {
    // 本期仅处理文本类型的消息，reasoning/step 等跳过
    String type = msg.getType();
    if ("reasoning".equals(type) || "step".equals(type)) {
        return;
    }
    String content = msg.getContent();
    if (content != null && !content.isBlank()) {
        imOutboundService.sendTextToIm(
                session.getBusinessSessionType(),
                session.getBusinessSessionId(),
                content,
                session.getAssistantAccount());
    }
}
```

### 5.2 SkillSessionService — createSession 签名变更

**当前签名**：
```java
public SkillSession createSession(String userId, String ak,
                                   String title, String imGroupId)
```

**修改后签名**：
```java
public SkillSession createSession(String userId, String ak, String title,
                                   String businessDomain, String sessionType,
                                   String sessionId, String assistantAccount)
```

**影响范围**：
- `SkillStreamController`：调用处需适配新签名，传入 `businessDomain="miniapp"`，其余三元组字段传 null 或从请求中获取
- `ImSessionManager`：新增调用处，传入完整三元组 + assistantAccount

### 5.3 SkillSessionRepository — MyBatis 映射变更

**insert 映射修改**：

```xml
<insert id="insert">
    INSERT INTO skill_session (id, user_id, ak, tool_session_id, title, status,
                               business_session_domain, business_session_type,
                               business_session_id, assistant_account,
                               created_at, last_active_at)
    VALUES (#{id}, #{userId}, #{ak}, #{toolSessionId}, #{title}, #{status},
            #{businessSessionDomain}, #{businessSessionType},
            #{businessSessionId}, #{assistantAccount},
            NOW(), NOW())
</insert>
```

**findByUserIdFiltered 映射修改**：
- 将 `im_group_id` 过滤条件替换为 `business_session_id`
- 所有结果映射增加 `businessSessionDomain`、`businessSessionType`、`businessSessionId`、`assistantAccount` 字段

### 5.4 MessagePersistenceService — IMAGE 类型支持

**变更点**：
- `persistIfFinal` 方法中识别图片类型消息，设置 `contentType = IMAGE`
- 图片消息的 content 存储 URL 字符串

---

## 六、上下文超限自动重建详细设计

### 6.1 触发条件与协议分析

> **协议依据**：以下方案基于 OpenCode SDK 源码确认（`packages/opencode/src/provider/error.ts`）。

**错误传递链路**：

```
LLM 提供商返回 context overflow 错误
  → OpenCode 内部 OVERFLOW_PATTERNS 正则匹配，识别为 context overflow
  → 生成 EventSessionError { type: "session.error", properties: { error: { name: "ContextOverflowError", data: { message } } } }
  → message-bridge 将 session.error 事件封装为 tool_event 透传
  → ai-gateway 中继
  → skill-server GatewayMessageRouter 收到 tool_event
```

**关键发现**：Context overflow 错误由 OpenCode 通过 **`session.error` SSE 事件** 发出，
在 message-bridge 中被封装为 **`tool_event`**（非 `tool_error`）。
错误对象是结构化的，通过 `error.name` 判别器区分错误类型。

**OpenCode SDK 定义的错误类型（v2）**：

| 错误类型                   | `name` 值                    | 说明                      |
| -------------------------- | ---------------------------- | ------------------------- |
| `ProviderAuthError`        | `"ProviderAuthError"`        | 供应商认证失败            |
| `UnknownError`             | `"UnknownError"`             | 未知错误                  |
| `MessageOutputLengthError` | `"MessageOutputLengthError"` | 输出长度超限              |
| `MessageAbortedError`      | `"MessageAbortedError"`      | 消息中断                  |
| `StructuredOutputError`    | `"StructuredOutputError"`    | 结构化输出错误（v2 新增） |
| **`ContextOverflowError`** | **`"ContextOverflowError"`** | **上下文超限**（v2 新增） |
| `ApiError`                 | `"APIError"`                 | API 调用错误              |

**OpenCode 内部使用的 OVERFLOW_PATTERNS**（仅供参考，skill-server 无需自行匹配）：

```
/prompt is too long/i                             // Anthropic
/input is too long for requested model/i          // Amazon Bedrock
/exceeds the context window/i                     // OpenAI
/input token count.*exceeds the maximum/i         // Google Gemini
/maximum prompt length is \d+/i                   // xAI (Grok)
/reduce the length of the messages/i              // Groq
/maximum context length is \d+ tokens/i           // OpenRouter / DeepSeek / vLLM
/exceeds the limit of \d+/i                       // GitHub Copilot
/exceeds the available context size/i             // llama.cpp
/greater than the context length/i                // LM Studio
/context window exceeds limit/i                   // MiniMax
/exceeded model token limit/i                     // Kimi / Moonshot
/context[_ ]length[_ ]exceeded/i                  // 通用后备
/request entity too large/i                       // HTTP 413
/context length is only \d+ tokens/i              // vLLM
/input length.*exceeds.*context length/i          // vLLM
```

### 6.2 检测方式

**检测入口**：`GatewayMessageRouter.handleAssistantToolEvent`（处理 `tool_event`），
**不是** `handleToolError`（那里处理的是 `tool_error`）。

当 `tool_event` 的内嵌事件类型为 `session.error`，且 `error.name == "ContextOverflowError"` 时触发。

### 6.3 处理流程

```
GatewayMessageRouter.handleAssistantToolEvent
  │
  ├─ 事件类型是 session.error 且 error.name == "ContextOverflowError"?
  │   → 否: 按现有事件处理（路由/持久化等）
  │   → 是:
  │
  ├─ businessSessionDomain == "miniapp"?
  │     → 通知前端展示提示（现有逻辑）
  │
  └─ businessSessionDomain == "im" / "meeting" / "doc"?
        → 自动重建:
          1. SessionRebuildService.rebuildToolSession
             - 清除旧 toolSessionId
             - 发送 create_session 到 Gateway
          2. 等待 session_created 回调完成
          3. 重发被拒消息
          4. 通过 ImOutboundService 发送系统提示
             → "对话上下文已超出限制，已自动重置。"
```

**在 handleAssistantToolEvent 中新增判断**：

```java
private void handleAssistantToolEvent(String sessionId, String userId, StreamMessage msg) {
    // ... 现有的 seq/buffer 逻辑 ...

    // 检测是否为 session.error 中的 ContextOverflowError
    JsonNode eventNode = msg.getRawEvent();  // tool_event 内嵌的原始 SSE 事件
    if (isContextOverflowEvent(eventNode)) {
        SkillSession session = resolveSession(sessionId);
        if (session != null && !"miniapp".equals(session.getChatDomain())) {
            log.warn("Context overflow detected for session {}, initiating auto-rebuild", sessionId);
            handleAutoRebuild(sessionId, userId, session);
            return;
        }
        // miniapp 场景走现有前端通知逻辑
    }

    // ... 现有路由/持久化逻辑 ...
}

/**
 * 检测 tool_event 内嵌事件是否为 ContextOverflowError。
 *
 * OpenCode session.error 事件结构：
 * {
 *   "type": "session.error",
 *   "properties": {
 *     "sessionID": "...",
 *     "error": {
 *       "name": "ContextOverflowError",   ← 判别器
 *       "data": { "message": "..." }
 *     }
 *   }
 * }
 */
private boolean isContextOverflowEvent(JsonNode eventNode) {
    if (eventNode == null) return false;
    String eventType = eventNode.path("type").asText("");
    if (!"session.error".equals(eventType)) return false;

    String errorName = eventNode.path("properties").path("error").path("name").asText("");
    return "ContextOverflowError".equals(errorName);
}

---

## 七、配置项汇总

```yaml
skill:
  im:
    api-url: ${IM_API_URL}                    # IM 服务端 API 基础 URL
    token: ${IM_TOKEN}                        # 出站 Token
    inbound-token: ${IM_INBOUND_TOKEN}        # 入站 Token（校验 IM 请求）

  assistant:
    resolve-url: ${ASSISTANT_RESOLVE_URL}    # assistantAccount → ak 第三方接口
    cache-ttl-minutes: 30                     # ak 缓存 TTL（分钟）

  context:
    injection-enabled: true                   # 上下文注入开关（true=模板组装 / false=直接透传）
    templates:
      group-chat: classpath:templates/group-chat-prompt.txt
    max-history-messages: 20                  # 最大注入历史消息数（仅 injection-enabled=true 时生效）

  session:
    auto-create-timeout-seconds: 30           # 自动创建等待超时
    idle-timeout-minutes: 30                  # 会话空闲超时
    cleanup-interval-minutes: 10              # 空闲清理间隔
    # 注：上下文超限检测基于 OpenCode session.error 事件中的
    # ContextOverflowError 结构化字段，无需配置正则模式（见第六章）
```

---

## 八、持久化策略总结

| 场景               | businessSessionDomain | businessSessionType | 消息持久化 | 说明                              |
| ------------------ | --------------------- | ------------------- | ---------- | --------------------------------- |
| 场景一 miniapp     | miniapp               | —                   | ✅ 全量     | 现有逻辑，支持历史恢复            |
| 场景二 群聊        | im                    | group               | ❌ 不持久化 | 聊天记录由 IM 平台管理            |
| 单聊               | im                    | direct              | ✅ 全量     | 保持与 miniapp 一致的历史记录能力 |
| 会议（未来扩展）   | meeting               | —                   | 待定       |                                   |
| 云文档（未来扩展） | doc                   | —                   | 待定       |                                   |

---

## 九、新增文件清单

### Java 类（~8 个）

| 包路径     | 类名                              | 职责                              |
| ---------- | --------------------------------- | --------------------------------- |
| controller | `ImInboundController`             | REST 消息接收端点                 |
| model      | `ImMessageRequest`                | 入站请求模型（record）            |
| config     | `ImTokenAuthInterceptor`          | Token 认证拦截器                  |
| config     | `WebMvcConfig`                    | 注册拦截器                        |
| service    | `AssistantAccountResolverService` | assistantAccount → ak 解析 + 缓存 |
| service    | `ImSessionManager`                | findOrCreate 核心逻辑             |
| service    | `ContextInjectionService`         | Prompt 模板 + 历史注入            |
| service    | `ImOutboundService`               | IM 纯文本发送                     |

### 资源文件（2 个）

| 路径                                       | 说明                          |
| ------------------------------------------ | ----------------------------- |
| `db/migration/V6__session_chat_triple.sql` | 三元组 + ENUM→VARCHAR DB 迁移 |
| `templates/group-chat-prompt.txt`          | 群聊 Prompt 模板              |

### 修改文件（~5 个）

| 文件                          | 修改内容                                                 |
| ----------------------------- | -------------------------------------------------------- |
| `SkillSession.java`           | 新增 4 个字段，删除 imGroupId                            |
| `SkillSessionRepository.java` | 新增 findByDomainSessionIdAndAk 方法                     |
| `SkillSessionMapper.xml`      | 修改 insert/select 映射，新增三元组查询                  |
| `SkillSessionService.java`    | 修改 createSession 签名，新增 findByDomainSessionIdAndAk |
| `GatewayMessageRouter.java`   | 增加 IM 路由分支 + context overflow 自动重建             |

### 后续迭代新增（本期不做）

| 模块                       | 职责                            |
| -------------------------- | ------------------------------- |
| `MessageFormatConverter`   | Agent 输出 → IM 纯文本/图片转换 |
| `ImOutboundMessage`        | 出站消息模型（含图片、@人字段） |
| `SkillMessage.ContentType` | 新增 IMAGE 枚举值               |
| `V7__image_content_type`   | 图片类型迁移（如需）            |
