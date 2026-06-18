# 支持 PlantUML 转换 — 需求分析与设计

> 基于 `docs/human-docs/002支持plantUml转换.md`
> 日期：2026-06-17
> 状态：已确认，实现中

---

## 一、背景与目标

skill-server 需要新增文本内容转换能力，将 PlantUML / Graphviz 文本转换为可直接展示的图片内容，供前端或外部系统调用。

目标：

1. 新增 `POST /api/content/convert/text`。
2. 使用 `plantuml-mit` 解析并输出 PNG / SVG。
3. 支持 `puml`、`plantuml`、`gv` 三类输入。
4. 支持 `png`、`svg` 两类输出。
5. 沿用现有 `ApiResponse<T>` 风格（`int code + errormsg + data`）。

---

## 二、现状分析

| 维度 | 现状 |
|------|------|
| 服务 | skill-server |
| Web 框架 | Spring Boot 3.4.6 + Spring MVC |
| Java 版本 | Java 21 |
| Controller 包 | `com.opencode.cui.skill.controller` |
| 现有响应 | `ApiResponse<T>` 为 `int code + errormsg + data` |
| 现有异常 | `ProtocolException(int code, String message)`，code 即 HTTP 状态码 |
| 全局异常处理 | `GlobalExceptionHandler` 映射 400/403/404/409 → 对应 HTTP 状态，其余 → 500 |
| PlantUML | 当前未引入依赖，未发现已有转换实现 |

---

## 三、需求范围

### 3.1 本期范围

| 能力 | 说明 |
|------|------|
| 文本转 PNG | 返回 Base64，不包含 `data:image/png;base64,` 前缀 |
| 文本转 SVG | 返回 SVG XML 字符串 |
| PlantUML 输入 | 支持完整 `@startuml ... @enduml` |
| puml 输入 | 等同 PlantUML，调用方需提供合法 PlantUML 文本，不自动补齐起止标记 |
| gv 输入 | 将内容包裹为 `@startuml ... @enduml` 后转换 |
| 长度限制 | 默认 10000 字符，可配置 |

### 3.2 非本期范围

| 能力 | 说明 |
|------|------|
| Redis 缓存 | 本期不做 |
| 总开关 | 本期不做 |
| 文件上传转换 | 只支持 JSON 文本请求 |
| 异步转换任务 | 本期同步返回 |
| 多页图处理 | 默认返回 PlantUML 输出的第一张图 |
| Mermaid 转换 | 不在本需求范围 |
| data URI 拼接 | 由前端处理 |

---

## 四、接口设计

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

沿用现有 `ApiResponse<T>` 风格，`code` 为 `int`，使用 HTTP 语义。

成功：

```json
{
  "code": 0,
  "errormsg": null,
  "data": {
    "image": "iVBORw0KGgo..."
  }
}
```

失败：

```json
{
  "code": 400,
  "errormsg": "不支持的输出类型，仅支持 png、svg",
  "data": null
}
```

### 4.3 错误码

| code | 场景 | errormsg |
|------|------|----------|
| `0` | 成功 | null |
| `400` | content 为空 / contentType 不支持 / fileType 不支持 / 内容超长 | 具体错误描述 |
| `422` | PlantUML 语法解析失败 | "内容转换失败，请检查语法" |
| `500` | 系统异常 | "Internal server error" |

---

## 五、配置设计

在 `application.yml` 的 `skill` 下新增：

```yaml
skill:
  content-convert:
    max-content-length: ${SKILL_CONTENT_CONVERT_MAX_CONTENT_LENGTH:10000}
```

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `max-content-length` | `10000` | 最大输入长度（字符数） |

---

## 六、模块设计

```
com.opencode.cui.skill.controller
  ContentConvertController.java

com.opencode.cui.skill.service.convert
  ContentConvertService.java
  PlantUmlConvertService.java

com.opencode.cui.skill.config
  ContentConvertProperties.java

com.opencode.cui.skill.model.convert
  ContentConvertRequest.java
  ContentConvertData.java
```

### 6.1 ContentConvertController

职责：接收请求、调用服务、封装 `ApiResponse`。

```java
@PostMapping("/api/content/convert/text")
public ResponseEntity<ApiResponse<ContentConvertData>> convert(@RequestBody ContentConvertRequest request)
```

### 6.2 ContentConvertService

职责：参数校验、调用转换服务、记录耗时日志。

### 6.3 PlantUmlConvertService

职责：内容标准化、调用 PlantUML、输出 PNG Base64 或 SVG 字符串、识别语法错误。

---

## 七、转换逻辑设计

### 7.1 输入标准化

| fileType | 标准化策略 |
|----------|------------|
| `plantuml` | 原样解析 |
| `puml` | 同 `plantuml` |
| `gv` | 包裹为 `@startuml\n` + `content.trim()` + `\n@enduml` |

### 7.2 转换伪代码

```java
String convert(String content, String fileType, String contentType) {
    String normalized = normalizeContent(content, fileType);
    SourceStringReader reader = new SourceStringReader(normalized);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    FileFormat format = "svg".equals(contentType) ? FileFormat.SVG : FileFormat.PNG;
    DiagramDescription desc = reader.outputImage(output, new FileFormatOption(format));

    if (desc == null || desc.getDescription().contains("(Error)")) {
        throw new ProtocolException(422, "内容转换失败，请检查语法");
    }

    if (format == FileFormat.PNG) {
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
    return output.toString(StandardCharsets.UTF_8);
}
```

---

## 八、依赖设计

`skill-server/pom.xml` 新增：

```xml
<dependency>
    <groupId>net.sourceforge.plantuml</groupId>
    <artifactId>plantuml-mit</artifactId>
    <version>1.2026.0</version>
</dependency>
```

---

## 九、安全与稳定性

1. 限制 `content` 最大长度（默认 10000 字符）。
2. 不在日志中输出原始 UML 内容，只输出长度、类型、耗时。
3. PlantUML 异常转换为业务错误，不暴露堆栈给调用方。

---

## 十、测试设计

| 测试类 | 覆盖点 |
|--------|--------|
| `PlantUmlConvertServiceTest` | puml→png、puml→svg、gv 包裹、语法错误 |
| `ContentConvertServiceTest` | 参数校验（空 content、无效 contentType、无效 fileType、超长） |
| `ContentConvertControllerTest` | 成功响应字段、400 错误、422 错误 |

回归命令：

```bash
cd skill-server
mvn test
```

---

## 十一、实施步骤

1. 从 main 拉分支 `feature-plantuml-convert`。
2. 新增 `plantuml-mit:1.2026.0` 依赖。
3. 新增 `ContentConvertProperties` 和 `application.yml` 配置。
4. 新增 `ContentConvertRequest`、`ContentConvertData` 模型。
5. 实现 `PlantUmlConvertService`。
6. 实现 `ContentConvertService`。
7. 新增 `ContentConvertController`。
8. `GlobalExceptionHandler` 新增 422 映射。
9. 补充单元测试。
10. 执行 `mvn test` 验证。

---

## 十二、已确认决策

1. `plantuml-mit` 版本：`1.2026.0`。
2. 错误码使用 `int`，沿用 HTTP 语义：0/400/422/500。
3. 响应沿用现有 `ApiResponse<T>` 风格（`int code + errormsg + data`）。
4. 不做 Redis 缓存，不做总开关。
5. `max-content-length` 默认 10000。
6. `puml` 缺少 `@startuml/@enduml` 时不自动包裹。
7. `gv` 自动包裹 `@startuml` 和 `@enduml`。
8. PNG 返回纯 Base64，不返回 data URI。
