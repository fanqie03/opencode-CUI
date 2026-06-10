# PlantUML 文本转换设计

> **日期**: 2026-06-08  
> **需求来源**: `docs/human-docs/002支持plantUml转换.md`  
> **目标**: skill-server 新增文本内容转换能力，将 PlantUML / Graphviz 文本转换为可直接展示的图片内容（PNG Base64 / SVG XML）。

---

## 1. 背景与问题

skill-server 当前无 PlantUML / Graphviz 转换能力。前端或外部系统需要直接展示 UML 图时，只能依赖外部服务。

本次需求：新增同步转换接口 `POST /api/content/convert/text`，支持 `puml` / `plantuml` / `gv` 输入，输出 `png`（Base64）或 `svg`（XML 字符串），带 Redis 缓存。

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
| Redis 缓存 | 相同输入、相同输出类型优先返回缓存 |
| 配置开关 | 总开关、缓存开关、缓存 TTL 可配置 |
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

## 4. 接口设计

### 4.1 请求

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

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | string | 是 | 待转换文本 |
| `contentType` | string | 是 | `png` 或 `svg` |
| `fileType` | string | 是 | `puml`、`plantuml` 或 `gv` |

### 4.2 响应

成功：

```json
{
  "code": 0,
  "messageCn": "success",
  "messageEn": "success",
  "data": {
    "image": "xxx"
  }
}
```

失败：

```json
{
  "code": "INVALID_CONTENT_TYPE",
  "messageCn": "不支持的输出类型，仅支持 png、svg",
  "messageEn": "Unsupported contentType, only png and svg are supported",
  "data": null
}
```

### 4.3 HTTP 状态码

业务错误仍返回 HTTP 200，由响应体 `code` 判断。若后续网关规范要求严格状态码，可调整为参数错误 400、语法错误 422、系统异常 500。

---

## 5. 配置设计

`application.yml` 的 `skill` 下新增：

```yaml
skill:
  content-convert:
    enabled: ${SKILL_CONTENT_CONVERT_ENABLED:true}
    max-content-length: ${SKILL_CONTENT_CONVERT_MAX_CONTENT_LENGTH:200000}
    cache:
      enabled: ${SKILL_CONTENT_CONVERT_CACHE_ENABLED:true}
      ttl-seconds: ${SKILL_CONTENT_CONVERT_CACHE_TTL_SECONDS:1800}
      key-prefix: ${SKILL_CONTENT_CONVERT_CACHE_KEY_PREFIX:contentConvert}
```

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 转换接口总开关 |
| `max-content-length` | `200000` | 最大输入长度，防止超大内容拖垮服务 |
| `cache.enabled` | `true` | 缓存开关 |
| `cache.ttl-seconds` | `1800` | 缓存 30 分钟 |
| `cache.key-prefix` | `contentConvert` | Redis key 前缀 |

---

## 6. 缓存设计

Redis key 格式：

```text
contentConvert:{contentType}:{fileType}:{md5(normalizedContent)}
```

示例：

```text
contentConvert:png:puml:5d41402abc4b2a76b9719d911017c592
```

流程：

1. 校验参数。
2. 标准化 `contentType`、`fileType` 和内容。
3. 计算 MD5。
4. 缓存开启时读取 Redis。
5. 命中则返回。
6. 未命中则转换。
7. 转换成功后写入 Redis，TTL 使用配置值。
8. Redis 失败只记录 warn，不影响转换主链路。

---

## 7. 模块设计

建议新增结构：

```text
com.opencode.cui.skill.controller
  ContentConvertController.java

com.opencode.cui.skill.service.convert
  ContentConvertService.java
  PlantUmlConvertService.java
  ContentConvertCacheService.java

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

### 7.1 ContentConvertController

职责：接收请求、调用服务、封装响应。

```java
@PostMapping("/api/content/convert/text")
public ResponseEntity<ContentConvertResponse> convert(@RequestBody ContentConvertRequest request)
```

### 7.2 ContentConvertService

职责：总开关检查、参数校验、缓存编排、调用转换服务、记录耗时日志。

### 7.3 PlantUmlConvertService

职责：内容标准化、调用 PlantUML、输出 PNG Base64 或 SVG 字符串、识别语法错误。

### 7.4 ContentConvertCacheService

职责：生成 key、读取缓存、写入缓存、屏蔽 Redis 异常。

---

## 8. 转换逻辑设计

### 8.1 输入标准化

| fileType | 标准化策略 |
|----------|------------|
| `plantuml` | 原样解析，不自动补齐 `@startuml/@enduml` |
| `puml` | 同 `plantuml` |
| `gv` | 按伪代码包裹为 `@startuml\n` + `content.trim()` + `\n@enduml` |

### 8.2 转换伪代码

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

## 9. 依赖设计

`skill-server/pom.xml` 新增 PlantUML 依赖：

```xml
<dependency>
    <groupId>net.sourceforge.plantuml</groupId>
    <artifactId>plantuml-mit</artifactId>
    <version>1.2026.0</version>
</dependency>
```

---

## 10. 错误码设计

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

## 11. 安全与稳定性

1. 限制 `content` 最大长度。
2. 不在日志中输出原始 UML 内容，只输出 md5、长度、类型、耗时。
3. Redis 异常不阻断主流程。
4. PlantUML 异常转换为业务错误，不暴露堆栈给调用方。
5. 评估响应体大小，必要时后续增加输出大小限制。
6. 若转换耗时高，后续可增加隔离线程池或超时控制。

---

## 12. 测试设计

| 测试类 | 覆盖点 |
|--------|--------|
| `PlantUmlConvertServiceTest` | puml 转 png、puml 转 svg、gv 包裹、语法错误 |
| `ContentConvertCacheServiceTest` | key 生成、命中、写入、Redis 异常降级 |
| `ContentConvertServiceTest` | 参数校验、总开关、缓存命中、缓存未命中 |
| `ContentConvertControllerTest` | 成功响应、错误响应字段、HTTP 200 策略 |

回归命令：

```bash
cd skill-server
mvn test
```

---

## 13. 实施步骤

1. 新增 `net.sourceforge.plantuml:plantuml-mit:1.2026.0` 依赖。
2. 新增 `ContentConvertProperties` 和 `application.yml` 配置。
3. 新增请求/响应/枚举/异常模型。
4. 实现 `PlantUmlConvertService`。
5. 实现 `ContentConvertCacheService`。
6. 实现 `ContentConvertService`。
7. 新增 `ContentConvertController`。
8. 补充单元测试和 Controller 测试。
9. 执行 `mvn test` 验证。

---

## 14. 已确认问题

1. `plantuml-mit` Maven 坐标和版本：`net.sourceforge.plantuml:plantuml-mit:1.2026.0`。
2. 业务错误沿用项目现有风格，HTTP 状态码返回 200，通过响应体 `code` 判断。
3. 本接口成功时 `code` 使用数字 `0`，错误时可使用字符串错误码。
4. `puml` 缺少 `@startuml/@enduml` 时不自动包裹，由调用方保证内容合法。
5. Graphviz `gv` 按原始伪代码处理，即在内容前后包裹 `@startuml` 和 `@enduml`。
6. PNG 返回纯 Base64 字符串，不返回 data URI。
