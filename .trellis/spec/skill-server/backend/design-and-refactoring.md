# Java 软件设计、重构与业务一致性规范

> 本规范适用于 `skill-server` 后端 Java 代码。后续 AI 修改本模块代码时，优先维护业务一致性；不要用看似健壮的技术兜底掩盖业务事实缺失。

---

## 1. Scope / Trigger

以下场景必须先读本文件，再修改代码：

- 新增或修改 Controller、WebSocket Handler、Service、Repository、Mapper、Model、DTO、策略类。
- 修改会话、消息、助手身份、路由、Redis ownership、Gateway 回源、协议翻译、埋码等业务主路径。
- 新增兜底、兼容历史数据、默认值、自动补齐、批量修复、自动重选、空对象、取第一条等逻辑。
- 重构方法、类、包、接口、策略或公共工具。

本规范是代码设计约束，不是泛泛的最佳实践。若与“让接口尽量成功返回”冲突，优先维护真实业务关系。

---

## 2. 业务一致性八荣八耻

### 总原则

后续 AI 修改本模块代码时，必须优先维护业务一致性，避免用“看似健壮”的技术兜底掩盖业务事实缺失。

1. **以主路径清晰为荣，以过度抽象为耻**
   业务特例只放在明确的业务主路径中，不包装成“通用能力”、隐式开关或到处可复用的魔法方法。

2. **以事实必填为荣，以自动乱选为耻**
   必须存在的业务事实缺失时，应暴露错误或阻断流程，不得自动选择另一个值来伪造正常状态。

3. **以边界明确为荣，以配置运行混用为耻**
   修改前先分清配置数据、主数据、单据数据、运行时状态的边界，不把运行时补偿写进配置逻辑，也不让配置兜运行时错误。

4. **以窄域兜底为荣，以宽泛兜底为耻**
   兜底必须有明确业务场景、输入条件和影响范围，不得用大范围默认值、空对象、随便取第一条等方式吞掉问题。

5. **以显式失败为荣，以延后暴雷为耻**
   上游少传、状态错乱、单据未绑定、关系不存在等问题，应在发现处显式失败，不得让错误数据继续流转。

6. **以关系一致为荣，以表面成功为耻**
   创建、更新、绑定单据时，要保证上下游 ID、业务归属、状态机和关联关系一致，不追求“接口返回成功”而牺牲真实业务关系。

7. **以局部特例为荣，以隐式副作用为耻**
   特例处理应靠近触发它的业务分支，并写清楚条件，不通过全局工具、公共转换器、默认查询逻辑制造隐式副作用。

8. **以审慎变更为荣，以自作聪明为耻**
   未确认业务含义前，不新增自动修复、批量补齐、兼容历史脏数据等机制；确需兼容时，必须说明范围、原因和失败策略。

### 修改前自检

- 这个值是业务事实，还是可以配置出来的策略？
- 缺失时应该失败，还是业务明确允许默认？
- 兜底是否只覆盖一个明确场景？
- 是否把某个业务特例伪装成了通用能力？
- 是否可能创建出“看起来成功但关系不一致”的数据？
- 错误是在源头暴露，还是被我延后到了更难排查的位置？

---

## 3. 事实、配置、单据、运行态边界

修改前必须给每个字段归类。归类不清时，不要新增兜底。

| 类型 | 示例 | 缺失时默认行为 |
| --- | --- | --- |
| 业务事实 | `senderUserAccount`, `assistantAccount`, `businessSessionDomain/type/id`, `toolSessionId`, `messageId`, `traceId` | 显式失败或保持 UNKNOWN；不得乱选另一个事实 |
| 配置策略 | cloud profile、delivery strategy、feature flag、executor size、TTL | 可按明确配置默认值处理，但默认值来源必须可查 |
| 主数据 | assistant instance、assistant info、AK/SK、assistant owner | 查询失败与不存在要区分；不得把失败当不存在 |
| 单据数据 | `SkillSession`, `SkillMessage`, `SkillMessagePart`, pending request | 创建/更新必须维护上下游 ID 和状态机一致 |
| 运行时状态 | Redis ownership、WS sender、stream buffer、active message tracker | 可以重建，但不得回写污染配置或主数据 |

Wrong:

```java
String sender = firstNonBlank(request.senderUserAccount(), session.getUserId(), ownerWelinkId);
```

Correct:

```java
if (isBlank(request.senderUserAccount())) {
    throw new ProtocolException("senderUserAccount required");
}
String sender = request.senderUserAccount();
```

---

## 4. 分层与放置契约

`skill-server` 当前是 Spring layer 分包，不要求一次性改成 DDD 目录。但新增代码必须保持边界清楚。

| 位置 | 可以做 | 禁止做 |
| --- | --- | --- |
| `controller/` | 参数校验、鉴权上下文、协议转换、调用 service | 查库、写 Redis、拼路由、发 MQ/WS、决定业务状态 |
| `ws/` | 握手、连接生命周期、消息解包、MDC、转交 service | 写业务规则、选择兜底身份、直接改 DB 关系 |
| `service/` | 用例编排、事务边界、幂等、调用 repository/Redis/Gateway | 堆积无关规则成 God Service |
| `service/scope/` | personal/business/default assistant scope 差异 | 在 Controller/Router 散落 `if scope` |
| `service/delivery/` | 出站渠道选择与投递策略 | 多处直接发送 miniapp/IM/external |
| `service/cloud/` | cloud protocol 请求构建和翻译 | 污染 OpenCode 翻译或把 profile 当事实 |
| `model/` | 持久化实体、协议 DTO、内部 command/record | 混淆外部契约 DTO 与内部传输 DTO |
| `repository/` + mapper XML | MyBatis 查询与持久化 | 写业务状态机或兜底选择 |

新增代码放置规则：

- 一个入口只服务 HTTP/WS 协议时，放在 `controller/` 或 `ws/`，并尽快转交 service。
- 多入口复用的业务流程，放在 `service/{Domain}Service` 或语义化 service。
- 行为随 scope 变化，优先扩展 `service/scope/AssistantScopeStrategy`。
- 行为随出站渠道变化，优先扩展 `service/delivery/OutboundDeliveryStrategy`。
- 行为随 cloud protocol/profile 变化，优先扩展 `service/cloud/`。
- 新 DTO 若跨 Redis、内部队列或跨入口复用，必须在 Javadoc 第一行标明它是内部传输还是外部契约。

---

## 5. 用例编排规范

Service 方法应该读起来像业务流程，而不是细节堆砌。

Good:

```java
@Transactional
public InboundResult handleInboundChat(InboundCommand command) {
    command.validateRequiredFacts();
    SkillSession session = sessionService.resolveBoundSession(command.sessionKey());
    AssistantSessionIdentity identity = assistantResolver.resolve(command.assistantAccount());
    PendingChatRequest pending = PendingChatRequest.from(command, identity, session);
    gatewayRelayService.dispatch(session, pending);
    return InboundResult.accepted(session.getId());
}
```

Bad:

```java
public InboundResult handleInboundChat(Request request) {
    String sender = firstNonBlank(request.getSender(), request.getOwner(), "system");
    SkillSession session = mapper.selectAnyByAssistant(request.getAssistantAccount());
    if (session == null) {
        session = createLooseSession(request);
    }
    redisTemplate.opsForList().leftPush("pending:" + session.getId(), request);
    return InboundResult.ok();
}
```

判断标准：

- Controller/WS Handler 不直接操作 Mapper、Redis、Gateway client。
- 编排层可以长在流程上，但业务不变量要收敛到清晰方法。
- 一个 service 注入依赖超过 8 个，必须评估是否职责膨胀。
- 同一段事实校验出现 3 次，必须提取为命名方法或专用策略。

---

## 6. 模型、值对象与状态规范

优先用类型表达业务含义，不要让裸 `String` / `Integer` 承担关键语义。

建议建模的对象：

- 身份：`AssistantSessionIdentity`, `UserId`, `SessionId`, `MessageId`。
- 状态：session status、message role、stream terminal state。
- 范围：business session triple、assistant scope、delivery domain。
- 金额/数量/时间范围等未来出现的业务值。

规则：

- DTO / Command / Query / 事件对象可优先用 Java `record`。
- 有状态流转、不变量保护、延迟加载或持久化生命周期的对象，不要为了简洁强行改成 `record`。
- `Optional` 只作为返回值，不放字段、不放 DTO、不放方法参数。
- 状态判断如果在 3 个以上位置重复，必须考虑枚举行文、状态机、策略或命名 guard 方法。

Wrong:

```java
if ("business".equals(type) && "cloud".equals(protocol)) {
    // ...
}
```

Correct:

```java
if (scopeIdentity.isBusinessCloud()) {
    // ...
}
```

---

## 7. 抽象与策略规则

不要一上来就接口化。抽象必须服务真实变化点。

允许新增接口/策略的条件：

- 已有两个以上实现，或本次明确新增第二个实现。
- 依赖外部系统，需要隔离 HTTP/Redis/MQ/WS/第三方 SDK。
- 业务规则稳定地按 scope、protocol、delivery channel、assistant type、tenant/scene 分化。
- 需要把 infrastructure 细节挡在 application/domain 之外。

禁止：

- 为单实现 CRUD 新增 `XxxService` + `XxxServiceImpl`。
- 把一个业务特例包装成 `CommonUtil`、`DefaultResolver`、`MagicFallbackHelper`。
- 用全局转换器、公共 mapper、默认查询逻辑承载局部业务特例。
- 为了“健壮”在策略路由失败时随便取第一条实现。

策略路由必须 fail loudly：

```java
return strategies.stream()
        .filter(strategy -> strategy.supports(context))
        .findFirst()
        .orElseThrow(() -> new ProtocolException("delivery strategy not found"));
```

---

## 8. 重构触发阈值

以下阈值用于 Code Review。达到阈值时，必须拆分或在 PR 中解释原因。

| 信号 | 处理要求 |
| --- | --- |
| 方法超过 50 行 | 解释原因，优先 Extract Method |
| 方法超过 80 行 | 必须拆分 |
| 嵌套超过 2 层 | 优先 guard clause / early return |
| 参数超过 4 个 | 改 Command / Query / Context object |
| 类超过 500 行 | 评估拆分职责 |
| 类超过 800 行 | 禁止继续加功能，先拆主路径 |
| service 注入依赖超过 8 个 | 评估是否拆 use case、policy、gateway、repository |
| 同一业务判断重复 3 次 | 抽领域方法、策略、枚举行为或状态机 |
| 同一 `status/type/scope/protocol` 分支重复 3 次 | 收敛到策略/状态机/scope dispatcher |
| 新增 `Util/Helper/Common` | 先 `rg` 搜索现有能力，并证明不是业务规则伪装 |

---

## 9. 重构流程

重构必须小步、可验证。

1. 先补 characterization test，锁住当前行为。
2. Rename 只改名，不改逻辑。
3. Extract Method 只抽表达业务意图的片段。
4. Extract Class 只移动同一变化原因的一组字段/方法。
5. Move Method 当方法主要操作另一个对象数据时才移动。
6. Replace Primitive with Object 只包关键业务值，不包所有字段。
7. Replace Conditional with Strategy/Polymorphism 只处理稳定且会扩展的分支。
8. 删除旧路径，不保留新旧双实现超过一个迭代。

禁止在同一 PR 中同时做大重构和业务语义变更。确实无法拆分时，PR 必须说明：

- 哪些提交是行为保持重构。
- 哪些提交改变业务行为。
- 哪些测试证明重构前后语义一致。

---

## 10. Validation & Error Matrix

| 场景 | 必须行为 |
| --- | --- |
| 必填业务事实缺失 | 在发现处显式失败；不得使用默认用户、默认 assistant、第一条 session、任意 link |
| 配置缺失但业务允许关闭 | soft-disable，并记录一次 WARN；不得伪造业务事实 |
| 主数据查询失败 | 返回 UNKNOWN / retryable failure；不得当作 NOT_EXISTS |
| 主数据明确不存在 | 返回 NOT_EXISTS；不得继续创建松散关系 |
| 单据关系不一致 | 阻断创建/更新/绑定；不得只追求接口成功 |
| Redis 运行态缺失 | 可重建运行态，但不得回写污染配置、主数据或历史单据 |
| 多实例投递找不到 owner | 按明确路由规则失败或进入死信；不得广播或随机重选 |
| 历史脏数据兼容 | 必须限定范围、说明原因、定义失败策略和删除计划 |

---

## 11. Good / Base / Bad Cases

Good：事实缺失时失败，策略缺失时按明确错误返回。

```java
if (isBlank(command.senderUserAccount())) {
    throw new ProtocolException("senderUserAccount required");
}
AssistantScopeStrategy strategy = scopeDispatcher.requireStrategy(command.scope());
```

Base：局部兼容旧数据，但范围、条件、日志都清楚。

```java
if (pending.allowedSlashCommands() == null) {
    log.info("[SKIP] allowedSlashCommands absent on legacy pending request: sessionId={}", sessionId);
    return PlatformExtParamBuilder.withoutAllowedSlashCommands(base);
}
```

Bad：宽泛兜底吞掉事实缺失。

```java
String sender = Optional.ofNullable(request.senderUserAccount())
        .orElseGet(() -> session.getUserId());
SkillSession session = sessions.isEmpty() ? createDefaultSession() : sessions.get(0);
```

---

## 12. Tests Required

修改设计、重构或兜底逻辑时，至少补以下测试之一：

- 必填事实缺失测试：断言明确失败，而不是自动选择默认值。
- 关系一致性测试：断言 session/message/toolSession/business triple/assistantAccount 绑定一致。
- 策略路由测试：断言匹配不到策略时失败，不取第一条。
- 历史兼容测试：断言仅兼容指定旧格式，其他坏数据失败。
- 重构保护测试：锁住重构前行为，尤其是 sender、assistant、routing key、messageId/traceId。
- 多实例/Redis 运行态测试：断言运行态缺失不会污染配置或主数据。

---

## 13. Review Checklist

- 业务事实是否被显式校验？
- 缺失事实是失败，还是被默认值掩盖？
- 新增兜底是否只有一个明确场景？
- 是否把局部业务特例包装成通用工具？
- Controller/WS Handler 是否直接操作 DB、Redis、Gateway client？
- Service 是否只是在编排，还是变成 God Service？
- 新接口/策略是否有真实变化点？
- 重构是否与业务行为变更分离？
- 测试是否覆盖错误路径和关系一致性，而不只是 happy path？
