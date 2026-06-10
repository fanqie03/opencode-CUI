# AppServiceInfo 透传 —— Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 skill-server 的 `/send-to-im` 接口添加开关可控的 `app_service_info` 透传能力，使 IM 消息体能携带业务服务名称。

**Architecture:** 新增 `AppServiceInfo` DTO（含 Jackson + Gson 双注解）→ `ImMessageService` 根据 `@Value` 注入的开关配置条件附加到 body Map → `RestTemplate` 自动序列化。Controller 层零侵入。

**Tech Stack:** Java 21, Spring Boot 3.4, Jackson, Gson, JUnit 5, Mockito

---

## File Structure

| File | Action | Responsibility |
|------|--------|--------------|
| `skill-server/src/main/java/com/opencode/cui/skill/model/AppServiceInfo.java` | **Create** | DTO，封装 `app_service_name` / `app_service_id`，带 `@JsonProperty` + `@SerializedName` |
| `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java` | **Modify** | 注入开关配置，条件附加 `app_service_info` |
| `skill-server/src/main/resources/application.yml` | **Modify** | 添加 `app-service-enabled` 和 `app-service-name` |
| `skill-server/pom.xml` | **Modify** | 引入 Gson 依赖（`@SerializedName` 所需） |
| `skill-server/src/test/java/com/opencode/cui/skill/service/ImMessageServiceTest.java` | **Create** | 单元测试：覆盖开关开启/关闭/自定义名称三种场景 |

---

## Task 1: Add Gson Dependency

**Files:**
- Modify: `skill-server/pom.xml:111-112`

- [ ] **Step 1: Add Gson dependency to pom.xml**

在 `<!-- Test -->` 依赖注释上方插入：

```xml
        <!-- Gson (for @SerializedName compatibility) -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
        </dependency>
```

- [ ] **Step 2: Verify Maven compiles**

Run:
```bash
cd skill-server && mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add skill-server/pom.xml
git commit -m "deps: add Gson for @SerializedName compatibility"
```

---

## Task 2: Create AppServiceInfo DTO

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/model/AppServiceInfo.java`

- [ ] **Step 1: Write the DTO class**

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 消息 app_service_info 元数据。
 * 用于标识消息来源的业务服务名称。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppServiceInfo {

    @JsonProperty("app_service_name")
    @SerializedName("app_service_name")
    private String appServiceName;

    @JsonProperty("app_service_id")
    @SerializedName("app_service_id")
    private String appServiceId;
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd skill-server && mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/AppServiceInfo.java
git commit -m "feat: add AppServiceInfo DTO with Jackson/Gson annotations"
```

---

## Task 3: Modify ImMessageService

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java`

- [ ] **Step 1: Add import for AppServiceInfo**

在 `ImMessageService.java` 的 import 区添加：

```java
import com.opencode.cui.skill.model.AppServiceInfo;
```

- [ ] **Step 2: Add configuration fields**

在现有字段下方（`private final String imApiUrl;` 之后）添加：

```java
    private final boolean appServiceEnabled;
    private final String appServiceName;
```

- [ ] **Step 3: Modify constructor to accept new config**

将现有构造函数：

```java
    public ImMessageService(RestTemplate restTemplate,
            @Value("${skill.im.api-url}") String imApiUrl) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
    }
```

替换为：

```java
    public ImMessageService(RestTemplate restTemplate,
            @Value("${skill.im.api-url}") String imApiUrl,
            @Value("${skill.im.app-service-enabled:false}") boolean appServiceEnabled,
            @Value("${skill.im.app-service-name:员工助手的消息}") String appServiceName) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
        this.appServiceEnabled = appServiceEnabled;
        this.appServiceName = appServiceName;
    }
```

- [ ] **Step 4: Add conditional app_service_info to body**

在 `sendMessage` 方法中，现有 `body.put("msgType", "text");` 之后、创建 `HttpHeaders` 之前，插入：

```java
        if (appServiceEnabled) {
            body.put("app_service_info", new AppServiceInfo(appServiceName, appServiceName));
        }
```

完整 `sendMessage` 方法体应如下（仅展示变更部分上下文）：

```java
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("senderAccount", senderAccount);
        body.put("content", content);
        body.put("msgType", "text");

        if (appServiceEnabled) {
            body.put("app_service_info", new AppServiceInfo(appServiceName, appServiceName));
        }

        HttpHeaders headers = new HttpHeaders();
```

- [ ] **Step 5: Verify compilation**

Run:
```bash
cd skill-server && mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java
git commit -m "feat: conditionally attach app_service_info to IM message body"
```

---

## Task 4: Update application.yml

**Files:**
- Modify: `skill-server/src/main/resources/application.yml:63-68`

- [ ] **Step 1: Add two new config entries under skill.im**

将现有：

```yaml
  im:
    # IM platform API base URL for sending messages
    api-url: ${IM_API_URL:http://localhost:9999}
    token: ${IM_TOKEN:}
    inbound-token: ${IM_INBOUND_TOKEN:e2e-test-token}
    reply-state-ttl-minutes: 30
```

替换为：

```yaml
  im:
    # IM platform API base URL for sending messages
    api-url: ${IM_API_URL:http://localhost:9999}
    token: ${IM_TOKEN:}
    inbound-token: ${IM_INBOUND_TOKEN:e2e-test-token}
    reply-state-ttl-minutes: 30
    # Switch for appending app_service_info to outbound IM messages
    app-service-enabled: ${SKILL_IM_APP_SERVICE_ENABLED:false}
    # Business service name passed as app_service_name / app_service_id
    app-service-name: ${SKILL_IM_APP_SERVICE_NAME:员工助手的消息}
```

- [ ] **Step 2: Commit**

```bash
git add skill-server/src/main/resources/application.yml
git commit -m "config: add app-service-enabled and app-service-name for IM outbound"
```

---

## Task 5: Write Unit Tests

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/ImMessageServiceTest.java`

- [ ] **Step 1: Write the test class**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImMessageServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private static final String IM_API_URL = "http://localhost:9999";

    @BeforeEach
    void stubRestTemplate() {
        when(restTemplate.postForEntity(
                eq(IM_API_URL + "/messages/send"),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("ok"));
    }

    @Test
    @DisplayName("when disabled, body does NOT contain app_service_info")
    void whenDisabled_bodyDoesNotContainAppServiceInfo() {
        ImMessageService service = new ImMessageService(
                restTemplate, IM_API_URL, false, "员工助手的消息");

        service.sendMessage("group", "grp-001", "user-001", "hello");

        Map<?, ?> body = captureBody();
        assertFalse(body.containsKey("app_service_info"));
    }

    @Test
    @DisplayName("when enabled, body contains app_service_info with snake_case fields")
    void whenEnabled_bodyContainsAppServiceInfo() throws Exception {
        ImMessageService service = new ImMessageService(
                restTemplate, IM_API_URL, true, "员工助手的消息");

        service.sendMessage("group", "grp-001", "user-001", "hello");

        Map<?, ?> body = captureBody();
        assertTrue(body.containsKey("app_service_info"));

        // Verify Jackson serializes to snake_case by round-tripping through ObjectMapper
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(body);
        Map<?, ?> parsed = mapper.readValue(json, Map.class);
        Map<?, ?> appServiceInfo = (Map<?, ?>) parsed.get("app_service_info");

        assertNotNull(appServiceInfo);
        assertEquals("员工助手的消息", appServiceInfo.get("app_service_name"));
        assertEquals("员工助手的消息", appServiceInfo.get("app_service_id"));
    }

    @Test
    @DisplayName("when enabled with custom name, custom value is used")
    void whenEnabledWithCustomName_customValueIsUsed() throws Exception {
        ImMessageService service = new ImMessageService(
                restTemplate, IM_API_URL, true, "自定义服务");

        service.sendMessage("direct", "user-002", "user-001", "hi");

        Map<?, ?> body = captureBody();
        assertTrue(body.containsKey("app_service_info"));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(body);
        Map<?, ?> parsed = mapper.readValue(json, Map.class);
        Map<?, ?> appServiceInfo = (Map<?, ?>) parsed.get("app_service_info");

        assertEquals("自定义服务", appServiceInfo.get("app_service_name"));
        assertEquals("自定义服务", appServiceInfo.get("app_service_id"));
    }

    private Map<?, ?> captureBody() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq(IM_API_URL + "/messages/send"), captor.capture(), eq(String.class));
        return (Map<?, ?>) captor.getValue().getBody();
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run:
```bash
cd skill-server && mvn test -Dtest=ImMessageServiceTest -q
```

Expected: `BUILD SUCCESS`，3 tests passed

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/ImMessageServiceTest.java
git commit -m "test: add ImMessageService tests for app_service_info toggle"
```

---

## Self-Review Checklist

**1. Spec coverage:**
- [x] 新增开关 `skill.im.app-service-enabled` → Task 4 + Task 3 Step 3
- [x] 开关开启后附加 `app_service_info` → Task 3 Step 4
- [x] `appServiceName` 可配置 → Task 3 Step 3 + Task 4
- [x] JSON 字段 snake_case → Task 2 (`@JsonProperty`) + Task 5 Step 1 test
- [x] `@JsonProperty` + `@SerializedName` → Task 2
- [x] Controller 无侵入 → 无 Task（ intentionally unchanged ）

**2. Placeholder scan:**
- [x] 无 "TBD", "TODO", "implement later"
- [x] 每个代码步骤包含完整代码块
- [x] 测试代码包含具体断言
- [x] 无 "Similar to Task N" 引用

**3. Type consistency:**
- [x] 构造函数参数顺序：`RestTemplate`, `String imApiUrl`, `boolean appServiceEnabled`, `String appServiceName`
- [x] `@Value` 默认值：`false` / `员工助手的消息`
- [x] 字段名：`appServiceEnabled`, `appServiceName`
- [x] Map key：`app_service_info`

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-10-app-service-info-impl.md`.**

**Two execution options:**

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints for review.

**Which approach?**
