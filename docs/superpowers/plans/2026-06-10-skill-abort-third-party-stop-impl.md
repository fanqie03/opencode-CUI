# Skill Abort Third-Party Stop API - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Gateway invokes third-party assistant stop API when receiving abort_session, via remoteProperty configuration.

**Architecture:** Reuse existing remoteProperty mechanism with new type="abort", use CompletableFuture.runAsync() + dedicated thread pool for async fire-and-forget HTTP POST.

**Tech Stack:** Java 21, Spring Boot, Jackson, Java HttpClient

---

## File Changes

### New Files
| File | Purpose |
|---|---|
| `ai-gateway/.../config/CloudAgentConfig.java` | Declare cloudAbortExecutor bean |
| `ai-gateway/.../model/AbortRequest.java` | Third-party stop API request DTO |

### Modified Files
| File | Changes |
|---|---|
| `ai-gateway/.../service/CloudAgentService.java` | abilityType() abort branch; handleInvoke() calls invokeRemoteAbortIfConfigured(); new methods |
| `ai-gateway/src/main/resources/application.yml` | Add gateway.cloud.abort.executor.* config |

### Unchanged Files
| File | Reason |
|---|---|
| `SkillSessionFlowService.java` | Payload already has sufficient fields |
| `BusinessInvokeRouteStrategy.java` | abort already inline, no change |
| `WebHookExecutor.java` | Abort sent separately in CloudAgentService |
| `AssistantInstanceInfo.java` | remoteProperty structure sufficient |

---

## Task 1: Add Dedicated Thread Pool Bean

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudAgentConfig.java`
- Modify: `ai-gateway/src/main/resources/application.yml`

- [ ] **Step 1.1: Create CloudAgentConfig.java**

Create file with ThreadPoolExecutor bean named "cloudAbortExecutor", core=2, max=10, queue=100, daemon threads named "cloud-abort-N", DiscardPolicy.

- [ ] **Step 1.2: Add application.yml config**

Append under gateway.cloud node:
```yaml
abort:
  executor:
    core-size: 2
    max-size: 10
    queue-capacity: 100
```

- [ ] **Step 1.3: Compile check**

Run: `cd ai-gateway && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 1.4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudAgentConfig.java ai-gateway/src/main/resources/application.yml
git commit -m "feat(gateway): add cloudAbortExecutor bean for async abort requests"
```

---

## Task 2: Add AbortRequest DTO

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/AbortRequest.java`

- [ ] **Step 2.1: Create AbortRequest.java**

Java record with fields: topicId, assistantAccount, sendUserAccount, imGroupId, messageId, clientLang.

- [ ] **Step 2.2: Compile check**

Run: `cd ai-gateway && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2.3: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/model/AbortRequest.java
git commit -m "feat(gateway): add AbortRequest DTO for third-party stop API"
```

---

## Task 3: Modify CloudAgentService

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`

### 3.1 Inject abortExecutor

- [ ] **Step 3.1: Add field and constructor parameter**

Add `private final Executor abortExecutor;` field.
Add `@Qualifier("cloudAbortExecutor") Executor abortExecutor` to @Autowired constructor.

### 3.2 Extend abilityType

- [ ] **Step 3.2: Add abort case to abilityType**

Change switch to: chat -> "chat", abort_session -> "abort", default -> "question".

### 3.3 Modify abort_session handling

- [ ] **Step 3.3: Update handleInvoke abort branch**

Before `cancelStreamingConnection()`, call `invokeRemoteAbortIfConfigured(...)`.

### 3.4 Add invokeRemoteAbortIfConfigured

- [ ] **Step 3.4: Add new method**

Call resolveRemoteRoute() with ACTION_ABORT_SESSION. If null, log debug and return.
Build AbortRequest. Run CompletableFuture.runAsync with abortExecutor to call sendAbortRequest.

### 3.5 Add buildAbortRequest

- [ ] **Step 3.5: Add new method**

Map fields from invokeMessage/payload to AbortRequest. topicId=toolSessionId, clientLang defaults to "zh".

### 3.6 Add sendAbortRequest

- [ ] **Step 3.6: Add new method**

Serialize AbortRequest to JSON. Build HttpRequest POST with 10s timeout, X-Trace-Id header.
Send with HttpClient.newHttpClient(). Log success (INFO) or failure (WARN).

### 3.7 Compile and commit

- [ ] **Step 3.7: Compile check**

Run: `cd ai-gateway && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3.8: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "feat(gateway): integrate third-party abort stop API via remoteProperty"
```

---

## Task 4: Unit Tests

**Files:**
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java`

- [ ] **Step 4.1: Test abilityType mapping**

Verify abilityType("abort_session") returns "abort" via reflection.

- [ ] **Step 4.2: Test resolveRemoteRoute for abort**

Mock AssistantInstanceInfoService with type=abort remoteProperty. Verify resolveRemoteRoute returns non-null.

- [ ] **Step 4.3: Test buildAbortRequest field mapping**

Construct GatewayMessage, verify AbortRequest fields mapped correctly.

- [ ] **Step 4.4: Test skip when no config**

Mock resolveRemoteRoute returning null. Verify cancelStreamingConnection still called but no HTTP request made.

- [ ] **Step 4.5: Run tests**

Run: `cd ai-gateway && mvn test -Dtest=CloudAgentServiceTest -q`
Expected: ALL PASS

- [ ] **Step 4.6: Commit**

```bash
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "test(gateway): add CloudAgentService abort tests"
```

---

## Task 5: Integration Test

**Files:**
- Create/Modify: `ai-gateway/src/test/java/...` (integration test class)

- [ ] **Step 5.1: WireMock test**

Stub POST /stream_chat_stop -> 200. Configure remoteProperty type=abort to WireMock URL.
Call handleInvoke with abort_session. Verify WireMock received 1 POST with correct body fields.

- [ ] **Step 5.2: Run integration test**

Run: `cd ai-gateway && mvn test -Dtest=CloudAgentServiceIT -q`
Expected: PASS

- [ ] **Step 5.3: Commit**

```bash
git add ai-gateway/src/test/java/...
git commit -m "test(gateway): add abort integration test with WireMock"
```

---

## Task 6: Final Verification

- [ ] **Step 6.1: Full compile**

Run: `cd ai-gateway && mvn clean compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6.2: Full test suite**

Run: `cd ai-gateway && mvn test -q`
Expected: ALL TESTS PASS

- [ ] **Step 6.3: Review checklist**

- CloudAgentConfig thread pool configurable
- CloudAgentService.abilityType() has abort branch
- CloudAgentService.handleInvoke() calls third-party before local cancel
- CloudAgentService.invokeRemoteAbortIfConfigured uses CompletableFuture + abortExecutor
- CloudAgentService.buildAbortRequest maps all fields correctly
- CloudAgentService.sendAbortRequest logs success/failure
- No changes to SkillSessionFlowService or BusinessInvokeRouteStrategy

---

## Rollback Strategy

If issues found in production:
1. Revert CloudAgentService.java to previous version (abort_session logic returns to just calling cancelStreamingConnection)
2. Remove CloudAgentConfig.java and AbortRequest.java (optional, harmless if left)
3. Remove application.yml abort executor config (optional)
4. Restart gateway service

No database migrations or external state changes required.
