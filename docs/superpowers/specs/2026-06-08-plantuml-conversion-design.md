# PlantUML 文本转换设计

> **日期**: 2026-06-08  
> **需求来源**: `docs/human-docs/002支持plantUml转换.md`  
> **目标**: skill-server 新增文本内容转换能力，将 PlantUML / Graphviz 文本转换为可直接展示的图片内容（PNG Base64 / SVG XML）。

---

## 1. 背景与问题

skill-server 当前无 PlantUML / Graphviz 转换能力。前端或外部系统需要直接展示 UML 图时，只能依赖外部服务。

本次需求：新增同步转换接口 `POST /api/content/convert/text`，支持 `puml` / `plantuml` / `gv` 输入，输出 `png`（Base64）或 `svg`（XML 字符串）。

---

## 2. 现状分析

| 维度 | 现状 |
|------|------|
| **服务** | skill-server |
| **Web 框架** | Spring Boot 3.4.6 + Spring MVC |
| **Java 版本** | Java 21 |
| **Controller 包** | `com.opencode.cui.skill.controller` |
| **Redis** | 已引入 `spring-boot-starter-data-redis`，可使用 `StringRedisTemplate` |
| **现有响应** | `ApiResponse<T>` 为 `int code + errormsg + data` |
| **PlantUML** | 当前未引入依赖，未发现已有转换实现 |

> 本接口要求 `code` 可能为字符串，且需要 `messageCn/messageEn`。现有 `ApiResponse<T>` 不完全匹配，新增专用响应模型，避免影响存量接口。

---

## 3. 需求范围

### 3.1 本期范围

| 能力 | 说明 |
|------|------|
| 文本转 PNG | 返回 Base64，不包含 `data:image/png;base64,` 前缀 |
| 文本转 SVG | 返回 SVG XML 字符串 |
| PlantUML 输入 | 支持完整 `@startuml ... @enduml` |
| `puml` 输入 | 等同 PlantUML，不自动补齐起止标记 |
| `gv` 输入 | 按伪代码处理，将内容包裹为 `@startuml ... @enduml` 后转换 |
| 配置开关 | 总开关、最大输入长度可配置 |
| 错误信息 | 返回中文和英文错误信息 |

### 3.2 非本期范围

| 能力 | 说明 |
|------|------|
| 文件上传转换 | 只支持 JSON 文本请求 |
| 异步转换任务 | 本期同步返回 |
| 多页图处理 | 默认返回 PlantUML 输出的第一张图 |
| Mermaid 转换 | 不在本需求范围 |
| data URI 拼接 | 由前端处理 |

---

## 4. 接口文档

### 4.1 接口概述

| 项目 | 说明 |
|------|------|
| **接口名称** | 文本内容转换 |
| **请求路径** | `POST /api/content/convert/text` |
| **请求方法** | POST |
| **Content-Type** | `application/json` |
| **调用方式** | 同步调用 |
| **功能描述** | 将 PlantUML / Graphviz 文本内容转换为 PNG（Base64）或 SVG（XML）格式图片 |

### 4.2 请求参数

**请求示例：**

```http
POST /api/content/convert/text
Content-Type: application/json
```

```json
{
  "content": "@startuml\nAlice -> Bob: hello\n@enduml",
  "contentType": "png",
  "fileType": "puml"
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 | 限制 |
|------|------|------|------|------|
| `content` | string | 是 | 待转换文本 | 最大长度 **200,000 字符**（约 200KB），超限返回 `CONTENT_TOO_LARGE` |
| `contentType` | string | 是 | 输出格式 | 仅支持 `png`、`svg` |
| `fileType` | string | 是 | 输入类型 | 仅支持 `puml`、`plantuml`、`gv` |

> **注意**：`content` 字段的最大长度限制为 200,000 字符，由服务端配置 `skill.content-convert.max-content-length` 控制，防止超大内容拖垮服务。

### 4.3 响应参数

**成功响应（HTTP 200）：**

```json
{
  "code": 0,
  "messageCn": "success",
  "messageEn": "success",
  "data": {
    "image": "iVBORw0KGgoAAAANSUhEUgAA..."
  }
}
```

**失败响应（HTTP 200）：**

```json
{
  "code": "INVALID_CONTENT_TYPE",
  "messageCn": "不支持的输出类型，仅支持 png、svg",
  "messageEn": "Unsupported contentType, only png and svg are supported",
  "data": null
}
```

**响应字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int / string | 业务状态码，`0` 表示成功，其他为错误码 |
| `messageCn` | string | 中文提示信息 |
| `messageEn` | string | 英文提示信息 |
| `data` | object / null | 成功时返回转换结果，失败时为 `null` |
| `data.image` | string | 转换后的图片内容。`png` 时为纯 Base64 字符串；`svg` 时为 SVG XML 字符串 |

### 4.4 HTTP 状态码

| HTTP 状态码 | 场景 |
|-------------|------|
| 200 | 业务处理完成（无论成功或失败，均返回 200，通过响应体 `code` 判断） |
| 400 | 请求体格式非法（JSON 解析失败） |
| 500 | 未预期系统异常 |

> 业务错误沿用项目现有风格，返回 HTTP 200，由响应体 `code` 判断。若后续网关规范要求严格状态码，可调整为参数错误 400、语法错误 422、系统异常 500。

---

## 5. 配置设计

`application.yml` 的 `skill` 下新增：

```yaml
skill:
  content-convert:
    enabled: ${SKILL_CONTENT_CONVERT_ENABLED:true}
    max-content-length: ${SKILL_CONTENT_CONVERT_MAX_CONTENT_LENGTH:200000}
```

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 转换接口总开关 |
| `max-content-length` | `200000` | 最大输入长度（字符数），防止超大内容拖垮服务 |

---

## 6. 模块设计

建议新增结构：

```text
com.opencode.cui.skill.controller
  ContentConvertController.java

com.opencode.cui.skill.service.convert
  ContentConvertService.java
  PlantUmlConvertService.java

com.opencode.cui.skill.config
  ContentConvertProperties.java

com.opencode.cui.skill.model.convert
  ContentConvertRequest.java
  ContentConvertResponse.java
  ContentConvertData.java
  ConvertContentType.java
  ConvertFileType.java
  ContentConvertException.java
```

### 6.1 ContentConvertController

职责：接收请求、调用服务、封装响应。

```java
@PostMapping("/api/content/convert/text")
public ResponseEntity<ContentConvertResponse> convert(@RequestBody ContentConvertRequest request)
```

### 6.2 ContentConvertService

职责：总开关检查、参数校验、调用转换服务、记录耗时日志。

### 6.3 PlantUmlConvertService

职责：内容标准化、调用 PlantUML、输出 PNG Base64 或 SVG 字符串、识别语法错误。

---

## 7. 转换逻辑设计

### 7.1 输入标准化

| fileType | 标准化策略 |
|----------|------------|
| `plantuml` | 原样解析，不自动补齐 `@startuml/@enduml` |
| `puml` | 同 `plantuml` |
| `gv` | 按伪代码包裹为 `@startuml\n` + `content.trim()` + `\n@enduml` |

### 7.2 转换伪代码

```java
String convert(String content, String fileType, String contentType) {
    String normalized = normalizeContent(content, fileType);
    SourceStringReader reader = new SourceStringReader(normalized);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    FileFormat format = "svg".equals(contentType) ? FileFormat.SVG : FileFormat.PNG;
    DiagramDescription desc = reader.outputImage(output, new FileFormatOption(format));

    if (desc == null || desc.getDescription().contains("(Error)")) {
        throw new ContentConvertException("CONVERT_FAILED");
    }

    if (format == FileFormat.PNG) {
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
    return output.toString(StandardCharsets.UTF_8);
}
```

注意：SVG 分支必须使用 `FileFormat.SVG`；PNG Base64 必须基于 `output.toByteArray()`；日志不能打印完整 `content`。

---

## 8. 依赖设计

`skill-server/pom.xml` 新增 PlantUML 依赖：

```xml
<dependency>
    <groupId>net.sourceforge.plantuml</groupId>
    <artifactId>plantuml-mit</artifactId>
    <version>1.2026.0</version>
</dependency>
```

---

## 9. 错误码设计

| code | messageCn | messageEn | 场景 |
|------|-----------|------------|------|
| `0` | `success` | `success` | 成功 |
| `CONVERT_DISABLED` | `内容转换服务未开启` | `Content convert service is disabled` | 总开关关闭 |
| `CONTENT_REQUIRED` | `content 不能为空` | `content is required` | 内容为空 |
| `CONTENT_TOO_LARGE` | `content 超过最大长度限制` | `content exceeds max length` | 内容过大 |
| `INVALID_CONTENT_TYPE` | `不支持的输出类型，仅支持 png、svg` | `Unsupported contentType, only png and svg are supported` | 输出类型错误 |
| `INVALID_FILE_TYPE` | `不支持的文件类型，仅支持 puml、plantuml、gv` | `Unsupported fileType, only puml, plantuml and gv are supported` | 输入类型错误 |
| `CONVERT_FAILED` | `内容转换失败，请检查语法` | `Content conversion failed, please check syntax` | PlantUML 解析失败 |
| `INTERNAL_ERROR` | `系统异常` | `Internal server error` | 未预期异常 |

---

## 10. 安全与稳定性

1. **输入长度限制**：`content` 最大长度限制为 200,000 字符（约 200KB），由配置 `skill.content-convert.max-content-length` 控制，防止超大内容拖垮服务。
2. **日志脱敏**：不在日志中输出原始 UML 内容，只输出内容 MD5、长度、类型、耗时。
3. **PlantUML 异常处理**：PlantUML 异常转换为业务错误，不暴露堆栈给调用方。
4. **响应体大小评估**：必要时后续增加输出大小限制。
5. **超时控制**：若转换耗时高，后续可增加隔离线程池或超时控制。
6. **ALB 限流**：由开发手工在应用负载均衡（ALB）层配置限流策略，服务端代码不处理限流逻辑。

---

## 11. 压测性能

### 11.1 压测场景

| 场景 | 说明 |
|------|------|
| **最大输入压测** | 使用 200,000 字符的 PlantUML 文本进行转换，评估服务端处理超大内容的性能表现 |
| **并发压测** | 模拟多用户同时调用接口，评估并发处理能力 |
| **不同输出格式压测** | 分别对 PNG 和 SVG 输出进行压测，对比性能差异 |

### 11.2 压测指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| **最大输入处理耗时** | < 5s | 200,000 字符文本的转换耗时应在 5 秒内完成 |
| **P99 延迟** | < 3s | 99% 请求的处理延迟不超过 3 秒 |
| **吞吐量** | > 50 QPS | 单实例每秒处理请求数不低于 50 |
| **CPU 使用率** | < 80% | 压测期间 CPU 使用率不超过 80% |
| **内存使用率** | < 80% | 压测期间内存使用率不超过 80% |

### 11.3 压测方法

```bash
# 使用 JMeter 或类似工具进行压测
# 示例：使用 curl 测试最大输入处理耗时
curl -X POST http://localhost:8080/api/content/convert/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "<200000字符的PlantUML文本>",
    "contentType": "png",
    "fileType": "puml"
  }'
```

> **注意**：压测需在测试环境进行，避免影响生产环境。压测结果需记录并作为后续优化依据。

---

## 12. 测试设计

| 测试类 | 覆盖点 |
|--------|--------|
| `PlantUmlConvertServiceTest` | puml 转 png、puml 转 svg、gv 包裹、语法错误 |
| `ContentConvertServiceTest` | 参数校验、总开关、正常转换流程 |
| `ContentConvertControllerTest` | 成功响应、错误响应字段、HTTP 200 策略 |

回归命令：

```bash
cd skill-server
mvn test
```

---

## 12.1 测试建议（供测试人员参考）

> 以下测试用例面向手工测试 / 接口测试人员，建议结合 Postman / JMeter / 自定义脚本执行。

### 12.1.1 功能测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| FUNC-001 | puml 转 PNG | 服务启动，开关开启 | POST `/api/content/convert/text`，`contentType=png`，`fileType=puml`，`content` 为合法 PlantUML 文本 | 返回 `code=0`，`data.image` 为纯 Base64 字符串（不含 `data:image/png;base64,` 前缀），可解码为有效 PNG 图片 |
| FUNC-002 | puml 转 SVG | 服务启动，开关开启 | 同上，仅 `contentType=svg` | 返回 `code=0`，`data.image` 为合法 SVG XML 字符串，可用浏览器直接渲染 |
| FUNC-003 | plantuml 类型等同 puml | 服务启动，开关开启 | `fileType=plantuml`，内容含完整 `@startuml...@enduml`，转 PNG | 结果与 `fileType=puml` 一致 |
| FUNC-004 | gv 类型自动包裹 | 服务启动，开关开启 | `fileType=gv`，`content` 为纯 Graphviz 文本（不含 `@startuml`） | 返回 `code=0`，服务端自动将内容包裹为 `@startuml\n{content}\n@enduml` 后转换 |
| FUNC-005 | gv 类型保留已有标记 | 服务启动，开关开启 | `fileType=gv`，`content` 已包含 `@startuml...@enduml` | 正常转换，不重复包裹 |

### 12.1.2 边界与异常测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| BND-001 | content 为空字符串 | 服务启动 | `content=""` 或 `content=null`（JSON 中省略） | `code="CONTENT_REQUIRED"`，`messageCn="content 不能为空"` |
| BND-002 | content 超过最大长度 | `max-content-length=200000` | `content` 长度为 200001 字符 | `code="CONTENT_TOO_LARGE"`，`messageCn="content 超过最大长度限制"` |
| BND-003 | content 恰为最大长度 | `max-content-length=200000` | `content` 长度为 200000 字符 | 正常转换，`code=0` |
| BND-004 | 不支持的 contentType | 服务启动 | `contentType=pdf` | `code="INVALID_CONTENT_TYPE"`，HTTP 状态码 200 |
| BND-005 | 不支持的 fileType | 服务启动 | `fileType=mermaid` | `code="INVALID_FILE_TYPE"`，HTTP 状态码 200 |
| BND-006 | PlantUML 语法错误 | 服务启动 | `content` 为非法 PlantUML 文本（如缺少 `@enduml`） | `code="CONVERT_FAILED"`，`messageCn="内容转换失败，请检查语法"` |
| BND-007 | 超大合法内容转换 | 服务启动 | `content` 为接近 200000 字符的合法 PlantUML 文本 | `code=0`，转换耗时 < 5s |
| BND-008 | 请求体非 JSON | 服务启动 | 发送 `text/plain` 或格式错误的 JSON | HTTP 400，不进入业务逻辑 |

### 12.1.3 配置与开关测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| CFG-001 | 总开关关闭 | `skill.content-convert.enabled=false` | 发送任意合法转换请求 | `code="CONVERT_DISABLED"`，`messageCn="内容转换服务未开启"` |
| CFG-002 | 动态调整最大长度 | 修改配置后重启 / 热刷新 | `max-content-length=100`，发送 101 字符内容 | `code="CONTENT_TOO_LARGE"` |
| CFG-003 | 配置默认值 | 不配置 `max-content-length` | 发送 200000 字符内容 | 正常处理，说明默认值为 200000 |

### 12.1.4 安全与稳定性测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| SEC-001 | 特殊字符输入 | 服务启动 | `content` 包含 HTML 标签、SQL 语句、脚本代码 | 正常转换或返回 `CONVERT_FAILED`，不触发 XSS / 注入，服务端不报错崩溃 |
| SEC-002 | 日志脱敏验证 | 服务启动 | 发送包含敏感信息的 PlantUML 文本 | 日志中不输出完整 content，仅记录 MD5、长度、类型、耗时 |
| SEC-003 | 并发请求稳定性 | 服务启动 | JMeter 模拟 50 并发，持续 1 分钟 | 无内存泄漏，无 OOM，响应正常 |
| SEC-004 | 非法 Unicode / 二进制数据 | 服务启动 | `content` 包含非法 UTF-8 序列或控制字符 | 返回业务错误码，不触发 500 未预期异常 |

### 12.1.5 输出格式验证

| 用例编号 | 用例名称 | 验证方法 |
|----------|----------|----------|
| FMT-001 | PNG Base64 无 data URI 前缀 | 断言返回字符串不以 `data:image/png;base64,` 开头 |
| FMT-002 | PNG 可解码 | 将 Base64 解码为字节数组，用图片库验证为合法 PNG（含 PNG 文件头 `89 50 4E 47`） |
| FMT-003 | SVG 为合法 XML | 用 XML 解析器解析 `data.image`，验证根节点为 `<svg>` |
| FMT-004 | 错误响应结构完整 | 验证失败时 `code` 为字符串、`messageCn` 和 `messageEn` 均非空、`data` 为 `null` |

### 12.1.6 回归测试 checklist

- [ ] `mvn test` 全量通过（含 `PlantUmlConvertServiceTest`、`ContentConvertServiceTest`、`ContentConvertControllerTest`）。
- [ ] 存量接口 `ApiResponse<T>` 行为未受影响（抽样测试其他 `/api/**` 接口）。
- [ ] 不引入新依赖冲突（`mvn dependency:tree` 检查 `plantuml-mit` 传递依赖）。

---

## 13. 工作项

| 序号 | 工作项 | 负责人 | 状态 |
|------|--------|--------|------|
| 1 | 新增 `net.sourceforge.plantuml:plantuml-mit:1.2026.0` 依赖 | 后端开发 | 待开始 |
| 2 | 新增 `ContentConvertProperties` 和 `application.yml` 配置 | 后端开发 | 待开始 |
| 3 | 新增请求/响应/枚举/异常模型 | 后端开发 | 待开始 |
| 4 | 实现 `PlantUmlConvertService` | 后端开发 | 待开始 |
| 5 | 实现 `ContentConvertService` | 后端开发 | 待开始 |
| 6 | 新增 `ContentConvertController` | 后端开发 | 待开始 |
| 7 | 补充单元测试和 Controller 测试 | 后端开发 | 待开始 |
| 8 | 执行 `mvn test` 验证 | 后端开发 | 待开始 |
| 9 | 最大输入压测（200,000 字符） | 后端开发 | 待开始 |
| 10 | ALB 限流配置（开发手工处理） | 运维/开发 | 待开始 |
| 11 | 接口文档评审 | 技术负责人 | 待开始 |

---

## 14. 实施步骤

1. 新增 `net.sourceforge.plantuml:plantuml-mit:1.2026.0` 依赖。
2. 新增 `ContentConvertProperties` 和 `application.yml` 配置。
3. 新增请求/响应/枚举/异常模型。
4. 实现 `PlantUmlConvertService`。
5. 实现 `ContentConvertService`。
6. 新增 `ContentConvertController`。
7. 补充单元测试和 Controller 测试。
8. 执行 `mvn test` 验证。
9. 进行最大输入压测，记录性能指标。
10. 开发手工配置 ALB 限流策略。

---

## 15. 已确认问题

1. `plantuml-mit` Maven 坐标和版本：`net.sourceforge.plantuml:plantuml-mit:1.2026.0`。
2. 业务错误沿用项目现有风格，HTTP 状态码返回 200，通过响应体 `code` 判断。
3. 本接口成功时 `code` 使用数字 `0`，错误时可使用字符串错误码。
4. `puml` 缺少 `@startuml/@enduml` 时不自动包裹，由调用方保证内容合法。
5. Graphviz `gv` 按原始伪代码处理，即在内容前后包裹 `@startuml` 和 `@enduml`。
6. PNG 返回纯 Base64 字符串，不返回 data URI。
7. 已移除 Redis 缓存和缓存开关，简化实现。
8. ALB 限流由开发手工处理，服务端代码不处理限流逻辑。
