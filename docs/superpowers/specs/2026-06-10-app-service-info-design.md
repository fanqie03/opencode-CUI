# 执行 Skill 发送卡片消息优化 —— AppServiceInfo 透传设计

**日期：** 2026-06-10
**需求来源：** [docs/human-docs/004执行skill,发送卡片消息优化.md](../../human-docs/004执行skill,发送卡片消息优化.md)

---

## 1. 背景

当用户通过 MiniApp 调用 `/send-to-im` 接口将消息发送到 IM 时，IM 侧需要额外携带 `app_service_info` 元数据，以标识消息来源的业务服务名称。该能力需通过开关控制，默认关闭，避免影响现有行为。

---

## 2. 需求概述

1. **新增开关**：`skill.im.app-service-enabled`，默认 `false`。
2. **开关开启后**，`ImMessageService.sendMessage()` 向 IM 发送的消息体中附加 `app_service_info` 字段。
3. **`appServiceName` 可配置**：通过 `skill.im.app-service-name` 指定，默认值为 `"员工助手的消息"`。
4. **JSON 序列化格式**：`app_service_info` 内部字段使用 `snake_case`（如 `app_service_name`、`app_service_id`）。
5. **注解支持**：DTO 上同时标注 `@JsonProperty`（Jackson）和 `@SerializedName`（Gson），兼容两种 JSON 序列化工具。

---

## 3. 设计思路

采用**新增 DTO + 配置驱动**的方案（方案 B）：

- 新增 `AppServiceInfo` 模型类，统一封装字段和序列化规则。
- `ImMessageService` 内部根据开关状态决定是否附加该对象，对 Controller 层完全透明。
- 序列化交由 `RestTemplate` 的 `MappingJackson2HttpMessageConverter` 处理，`@JsonProperty` 确保 Jackson 输出 `snake_case`；同时标注 `@SerializedName` 以兼容潜在的 Gson 序列化场景。

---

## 4. 数据模型

### 4.1 `AppServiceInfo`

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

**说明：**
- `@JsonProperty` 控制 Jackson 序列化行为（本项目实际使用）。
- `@SerializedName` 控制 Gson 序列化行为（防御性标注，兼容多种 JSON 工具）。
- 两个注解的值保持一致，确保无论使用哪种序列化器，输出字段名均为 `snake_case`。

### 4.2 最终 IM Body 结构（开关开启时）

```json
{
  "targetType": "group",
  "targetId": "xxx",
  "senderAccount": "xxx",
  "content": "xxx",
  "msgType": "text",
  "app_service_info": {
    "app_service_name": "员工助手的消息",
    "app_service_id": "员工助手的消息"
  }
}
```

---

## 5. 接口变更

### 5.1 `ImMessageService.sendMessage`

**当前签名：**
```java
public boolean sendMessage(String targetType, String targetId, String senderAccount, String content)
```

**变更方式：** 签名保持不变，方法内部根据开关配置决定是否向 body 中附加 `app_service_info`。

**核心逻辑变更：**
```java
@Value("${skill.im.app-service-enabled:false}")
private boolean appServiceEnabled;

@Value("${skill.im.app-service-name:员工助手的消息}")
private String appServiceName;

public boolean sendMessage(String targetType, String targetId, String senderAccount, String content) {
    // ... 现有参数校验 ...

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetType", targetType);
    body.put("targetId", targetId);
    body.put("senderAccount", senderAccount);
    body.put("content", content);
    body.put("msgType", "text");

    if (appServiceEnabled) {
        body.put("app_service_info", new AppServiceInfo(appServiceName, appServiceName));
    }

    // ... 现有 RestTemplate 调用 ...
}
```

### 5.2 `SkillMessageController.sendToIm`

**无需任何改动。** 开关逻辑封装在 `ImMessageService` 内部，Controller 调用方式不变。

---

## 6. 配置项

在 `application.yml` 中新增：

```yaml
skill:
  im:
    api-url: ${IM_API_URL:http://localhost:8080}
    app-service-enabled: false           # 开关，默认关闭
    app-service-name: "员工助手的消息"     # 业务服务名称，可自定义
```

**配置说明：**
- `app-service-enabled`：`true` 时开启，`false` 时关闭。默认 `false`，保证向后兼容。
- `app-service-name`：透传到 IM 的 `app_service_name` 和 `app_service_id` 的值。默认 `"员工助手的消息"`，支持通过环境变量或配置文件覆盖。

---

## 7. 序列化说明

### 7.1 Jackson（本项目实际使用）

`RestTemplate` 默认使用 `MappingJackson2HttpMessageConverter`，读取 `@JsonProperty` 注解，将 Java 字段名映射为注解中指定的 `snake_case` 名称。

### 7.2 Gson（防御性兼容）

`@SerializedName` 是 Gson 库的标准注解。若未来某些场景使用 Gson 进行序列化，该注解可确保字段名一致性。

### 7.3 输出示例

无论 Jackson 还是 Gson，最终输出均为：

```json
{
  "app_service_name": "员工助手的消息",
  "app_service_id": "员工助手的消息"
}
```

---

## 8. 测试策略

### 8.1 单元测试（`ImMessageServiceTest`）

新增或补充以下测试用例：

1. **开关关闭时**：`sendMessage` 的 body 中不包含 `app_service_info` 字段。
2. **开关开启时**：`sendMessage` 的 body 中包含 `app_service_info`，且内部字段为 `app_service_name` 和 `app_service_id`。
3. **配置自定义名称时**：`appServiceName` 使用配置值而非默认值。

### 8.2 集成测试

如有 `SkillMessageControllerTest` 中涉及 `/send-to-im` 的测试，验证开关开启/关闭时的端到端行为。

---

## 9. 风险与回滚

| 风险 | 缓解措施 |
|------|----------|
| 开关误开启导致 IM 侧解析异常 | 默认关闭；IM 侧需具备忽略未知字段的能力 |
| Gson 依赖缺失导致编译失败 | 若项目中未引入 Gson，需检查 `pom.xml` 并补充依赖（`com.google.code.gson:gson`） |
| 配置项命名冲突 | 使用 `skill.im.` 前缀，与现有 `skill.im.api-url` 保持一致 |

**回滚方式：** 将 `skill.im.app-service-enabled` 设为 `false`，或移除配置项（默认即关闭）。

---

## 10. 改动文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `skill-server/src/main/java/com/opencode/cui/skill/model/AppServiceInfo.java` | **新增** | DTO，含 `@JsonProperty` + `@SerializedName` |
| `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java` | **修改** | 注入配置、条件附加 `app_service_info` |
| `skill-server/src/main/resources/application.yml` | **修改** | 添加 `app-service-enabled` 和 `app-service-name` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/ImMessageServiceTest.java`（如有） | **修改** | 补充开关相关测试 |
| `skill-server/pom.xml` | **检查/修改** | 确认 Gson 依赖存在（若使用 `@SerializedName`） |

---

## 11. 验收标准

- [ ] 开关默认关闭，现有 `/send-to-im` 行为不变。
- [ ] 开关开启后，`/send-to-im` 调用 IM 接口时 body 中包含 `app_service_info`。
- [ ] `app_service_info` 中 `app_service_name` 和 `app_service_id` 的值等于配置项 `skill.im.app-service-name`。
- [ ] JSON 序列化后字段名为 `snake_case`（`app_service_name`、`app_service_id`）。
- [ ] `SkillMessageController.sendToIm` 签名和方法体无需改动。
- [ ] 单元测试覆盖开关开启/关闭两种场景。

---

## 12. 测试建议（供测试人员参考）

> 以下测试用例面向手工测试 / 接口测试人员，建议结合 Postman / Mock Server / 实际 IM 环境执行。

### 12.1 开关功能测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| SW-001 | 默认关闭时不附加字段 | `skill.im.app-service-enabled` 未配置或显式 `false` | 调用 `/send-to-im` 发送消息 | IM 接收到的请求体中**不包含** `app_service_info` 字段，其余字段正常 |
| SW-002 | 开启后附加字段 | `skill.im.app-service-enabled=true` | 调用 `/send-to-im` 发送消息 | IM 接收到的请求体中**包含** `app_service_info` 对象，且内部有两个字段 |
| SW-003 | 关闭后移除字段 | 先开启后修改为 `false` 并重启 | 调用 `/send-to-im` 发送消息 | IM 请求体中**不包含** `app_service_info`，与 SW-001 一致 |
| SW-004 | 开关热刷新（如有） | 使用 Spring Cloud Config / Actuator refresh | 运行时修改开关值 | 新请求按最新开关状态执行（若不支持热刷新则需在文档中注明需重启） |

### 12.2 配置值测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| CFG-001 | 默认名称 | `skill.im.app-service-name` 未配置，`app-service-enabled=true` | 调用 `/send-to-im` | `app_service_name` 和 `app_service_id` 均为 `"员工助手的消息"` |
| CFG-002 | 自定义名称 | `skill.im.app-service-name=自定义服务名`，`app-service-enabled=true` | 调用 `/send-to-im` | `app_service_name` 和 `app_service_id` 均为 `"自定义服务名"` |
| CFG-003 | 名称为空字符串 | `skill.im.app-service-name=""`，`app-service-enabled=true` | 调用 `/send-to-im` | `app_service_name` 和 `app_service_id` 为空字符串（或按业务需求处理） |
| CFG-004 | 名称含特殊字符 | `skill.im.app-service-name="Service@123_测试"`，`app-service-enabled=true` | 调用 `/send-to-im` | 特殊字符原样透传，IM 侧正确接收，JSON 格式合法 |

### 12.3 序列化格式测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| SER-001 | Jackson 输出 snake_case | 服务使用 Jackson 序列化 | 抓取 IM 请求实际 body | 字段名为 `app_service_name` 和 `app_service_id`，而非驼峰 `appServiceName` |
| SER-002 | Gson 兼容（如有） | 服务引入 Gson 依赖 | 使用 Gson 序列化 `AppServiceInfo` | 字段名同样为 `app_service_name` 和 `app_service_id` |
| SER-003 | JSON 结构完整 | `app-service-enabled=true` | 解析 IM 请求 body | `app_service_info` 为对象类型，非字符串/数组；内部字段为字符串类型 |
| SER-004 | 无 null 字段 | `@JsonInclude(Include.NON_NULL)` 生效 | `appServiceId` 为 null（如构造时未设置） | 序列化后的 JSON 中不输出 `app_service_id` 字段 |

### 12.4 IM 端到端测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| E2E-001 | IM 正常接收文本消息（开关关） | `app-service-enabled=false` | 通过 `/send-to-im` 发送文本到群组 | 群组成员正常收到文本消息，无异常报错 |
| E2E-002 | IM 正常接收文本消息（开关开） | `app-service-enabled=true` | 通过 `/send-to-im` 发送文本到群组 | 群组成员正常收到文本消息，IM 侧若识别 `app_service_info` 则展示对应服务标识 |
| E2E-003 | IM 忽略未知字段 | `app-service-enabled=true`，IM 旧版本不支持 `app_service_info` | 发送消息到旧版 IM | IM 正常显示文本内容，不因未知字段而解析失败 |
| E2E-004 | 不同 msgType 场景 | `app-service-enabled=true` | 分别发送 text、markdown、card 等类型消息 | 所有类型消息的 body 中均包含 `app_service_info`（如设计如此） |

### 12.5 兼容性回归测试

| 用例编号 | 用例名称 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|
| REG-001 | 存量接口行为不变 | 调用 `/send-to-im` 及其他 `SkillMessageController` 接口 | 所有接口返回码、响应结构、业务逻辑与改动前完全一致 |
| REG-002 | Controller 签名未变 | 反射检查 `SkillMessageController.sendToIm` 方法签名 | 参数列表、返回值类型、注解均无变化 |
| REG-003 | 无 Gson 依赖时不编译失败 | 若项目未引入 Gson，检查编译结果 | 因 `@SerializedName` 来自 Gson，若未引入依赖需确认编译通过（或已添加依赖） |

### 12.6 回归测试 checklist

- [ ] `mvn test` 全量通过，新增 `ImMessageServiceTest` 用例覆盖开关开启/关闭。
- [ ] 使用 WireMock / Mock Server 验证实际发送的 HTTP body 结构正确。
- [ ] 开关默认关闭时，现有生产环境行为 100% 兼容。
- [ ] JSON 序列化后字段名严格为 `snake_case`，无驼峰残留。
- [ ] 配置项命名无冲突，与现有 `skill.im.api-url` 等前缀一致。
