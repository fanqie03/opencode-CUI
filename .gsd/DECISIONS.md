# DECISIONS.md

## Phase 3 Decisions

**Date:** 2026-03-06

### Scope
- 先补全缺失功能 (REQ-22/23 权限流 + TODO)，再做全面单元测试，最后审查重构
- 代码库已有 22 个 Java 文件，大部分已完整实现

### Approach
- **权限流:** 选择 Option A (最小化) — 无持久化表，纯内存路由 + REST endpoint
- **测试:** 全面覆盖所有核心 service + controller + handler
- **重连逻辑:** 本阶段完成 GatewayRelayService 的 2 个 TODO

### Constraints
- Skill Server 已能编译 (BUILD SUCCESS)，无需从零开始
- 本地 Java 21 + Maven 环境
- 单元测试为主 (无法做端到端集成测试，需要 MariaDB + Redis + PCAgent 在线)
