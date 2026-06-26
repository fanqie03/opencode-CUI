## Summary

消除 service 层构造器 DI 反模式，将 `@Autowired` 字段注入改为显式构造器注入，并清理未使用依赖。

### Changes

| 文件 | 变更 |
|------|------|
| `SkillMessageController.java` | 移除 15 个未使用的 `@Autowired` 字段依赖，仅保留实际使用的 `ImMessageService`/`ProtocolUtils`/`SessionAccessControlService`/`SkillMessageService`/`SkillMessageFlowService`/`SysConfigService`，改用 `@RequiredArgsConstructor` 构造器注入 |
| `ImAppNotifyResponse.java` | 补充缺失字段 |
| `AssistantAccountResolverService.java` | `@Autowired` 字段注入 → 构造器注入，`RestTemplate` 改为 final |
| `ImMultiDeviceSyncService.java` | `@Autowired` 字段注入 → 构造器注入 |
| `SkillMessageControllerTest.java` | 配合 Controller 依赖变更重写 mock 字段，从 Mockito `@Mock`+`@InjectMocks` 改为显式构造器传参 |
| `AssistantAccountResolverServiceTest.java` | 配合 Service 构造器变更调整测试 |
| `ImMultiDeviceSyncServiceTest.java` | 配合 Service 构造器变更调整测试 |

### Impact

- Risk: LOW
- Affected processes: 0
- `detect_changes` 确认变更仅限被测类文件范围，无执行流程受影响
