# Phase 2 Plan — AI-Gateway 完善

## 背景
Gateway 代码已基本完成（10 个 Java 文件），Phase 2 工作为增量改进。

## Plan 2.1: 编译验证 + 协议格式对齐
- `mvn compile` 确认编译通过
- 对比 PCAgent ProtocolAdapter 输出格式与 GatewayMessage 接收格式
- 修复差异

## Plan 2.2: REQ-26 数据库 AK/SK 凭证存储
- V2 migration: `ak_sk_credential` 表 (ak, sk, user_id, status, created_at)
- Model: `AkSkCredential.java`
- Repository: `AkSkCredentialRepository.java` + mapper XML
- 重构 `AkSkAuthService.lookupByAk()` → DB 查询
- 测试数据 migration

## Plan 2.3: 单元测试
- AkSkAuthService: 签名验证、时间窗口、nonce 重放
- GatewayMessage: JSON 序列化/反序列化
- AgentRegistryService: 注册、心跳、超时

## Plan 2.4: 文档 + 提交
- 更新 AGENTS.md、STATE.md、ROADMAP.md
- Git commit

## 验证计划
- `mvn compile` 通过
- `mvn test` 通过
- AK/SK DB 查询逻辑正确
