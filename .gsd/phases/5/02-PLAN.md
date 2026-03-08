---
phase: 5
plan: 2
wave: 1
---

# Plan 5.2: REST API 统一响应格式

## Objective
Skill Server 的 REST API 目前直接返回实体对象，需统一为 `{code, errormsg, data}` 格式，以便前端统一处理成功/错误状态。

## Context
- [SkillSessionController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java)
- [SkillMessageController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java)
- [SkillDefinitionController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillDefinitionController.java)
- [AgentQueryController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/AgentQueryController.java)

## Tasks

<task type="auto">
  <name>创建统一响应包装类 ApiResponse</name>
  <files>skill-server/src/main/java/com/opencode/cui/skill/model/ApiResponse.java</files>
  <action>
    新建 `ApiResponse<T>` 泛型类：
    ```java
    @Data @Builder @AllArgsConstructor @NoArgsConstructor
    public class ApiResponse<T> {
        private int code;       // 0 = success, 非零 = 错误码
        private String errormsg; // null on success
        private T data;          // 成功时的数据
        
        public static <T> ApiResponse<T> ok(T data) { ... }
        public static <T> ApiResponse<T> error(int code, String msg) { ... }
    }
    ```
  </action>
  <verify>编译通过：mvn compile -pl skill-server -q</verify>
  <done>ApiResponse 类创建，含 ok/error 静态工厂方法</done>
</task>

<task type="auto">
  <name>改造 Controller 返回 ApiResponse 包装</name>
  <files>
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java
    skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
  </files>
  <action>
    将所有 `ResponseEntity<T>` 返回改为 `ResponseEntity<ApiResponse<T>>`：
    - 成功响应：`ResponseEntity.ok(ApiResponse.ok(data))`
    - 错误响应：`ResponseEntity.status(xxx).body(ApiResponse.error(code, msg))`
    
    注意：
    - 保持 HTTP status code 不变（前端可能也依赖）
    - `code` 字段使用 HTTP status code 对应的值（200=0, 400=400, 404=404 等）
    - 仅改造面向前端的核心 API（Session + Message），SkillDefinitionController 和 AgentQueryController 为内部 API 暂不改造
  </action>
  <verify>mvn test -pl skill-server -q → 全部通过（需同步更新测试中的 response body 断言）</verify>
  <done>Session 和 Message Controller 的返回值统一为 ApiResponse 格式</done>
</task>

## Success Criteria
- [ ] ApiResponse 类存在并可编译
- [ ] Session/Message API 响应格式为 `{code, errormsg, data}`
- [ ] 现有测试通过（更新断言）
