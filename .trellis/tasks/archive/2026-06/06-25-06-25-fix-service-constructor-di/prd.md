# PRD: 修复 service 构造函数 new 依赖反模式及 ImAppNotifyResponse 字段补全

## 背景

三处代码问题需要修复：

1. **ImAppNotifyResponse 字段缺失** — `client_notify_id` / `server_notify_id` / `Invalid_account` 未建模  
2. **ImMultiDeviceSyncService 过度判断 error** — 对 IM 返回的 body.error 做阻断式判断，应只打印不拦截  
3. **SkillMessageController + AssistantAccountResolverService 构造器反模式** — 构造函数内 `new XxxService()` 绕过了 Spring DI，仅用于测试便利

## 变更清单

### A. ImAppNotifyResponse — 补全响应字段

**文件**: `skill-server/src/main/java/.../model/ImAppNotifyResponse.java`

补全三个字段：

| 字段 | 类型 | 必填 | JSON key |
|------|------|------|----------|
| clientNotifyId | String | Y | client_notify_id |
| serverNotifyId | Long | Y | server_notify_id |
| invalidAccount | List\<String\> | N | Invalid_account |

### B. ImMultiDeviceSyncService — 不判断 error，仅打印

**文件**: `skill-server/src/main/java/.../sync/ImMultiDeviceSyncService.java`

当前 `push()` 方法在收到 2xx 后，仍检查 `body.error.errorCode` 是否非空，非空则 **return 不记录成功日志**。改为：有 error 打 warn（含 clientNotifyId / serverNotifyId），无 error 打 info（含 clientNotifyId / serverNotifyId），均不阻断。

### C. SkillMessageController — 删除 new SkillMessageFlowService 的构造器

**文件**: `skill-server/src/main/java/.../controller/SkillMessageController.java`

删除 15 参数的辅助构造器（其内部 `new SkillMessageFlowService(...)`），保留 `@Autowired` 主构造器。测试跟进改造为注入 `@Mock SkillMessageFlowService`。

### D. AssistantAccountResolverService — 删除 new AssistantInstanceInfoService 的构造器

**文件**: `skill-server/src/main/java/.../service/AssistantAccountResolverService.java`

删除 9 参数的辅助构造器（其内部 `new AssistantInstanceInfoService(...)`），保留 `@Autowired` 主构造器。测试跟进改造为注入 `@Mock AssistantInstanceInfoService`。

## 影响范围

- `ImAppNotifyResponse` — 纯增量字段，Jackson 反序列化兼容
- `ImMultiDeviceSyncService` — 行为变更：不再因 body.error 阻断成功分支
- `SkillMessageController` — 删除辅助构造器，测试适配
- `AssistantAccountResolverService` — 删除辅助构造器，测试适配

## 风险

- **低风险**：仅 test 侧构造方式变化，运行时路径不变（Spring 始终走 `@Autowired` 主构造器）
