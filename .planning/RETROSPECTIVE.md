# Retrospective

## Milestone: v1.1 - Connection-Aligned Consumption Fix

**Shipped:** 2026-03-12  
**Phases:** 1 | **Plans:** 3

### What Was Built

- 实时广播模型从 `session` 维度切换到 `userId` 维度
- 建立了 `gateway` 的 `ak -> userId` 校验与回流补全
- 修复了 miniapp 实时流连接误退订问题

### What Worked

- 先通过 Phase 文档把问题模型收敛清楚，再落代码，减少了返工
- 真实日志排查和自动化回归结合得比较顺，能快速锁定 root cause

### What Was Inefficient

- `.planning` 与设计文档存在编码和状态不同步问题，收口时需要额外补文档
- 多实例联调步骤还偏手工

### Key Lessons

- 多实例实时问题要把“消息归属”和“连接归属”分开建模
- 用户级订阅生命周期要特别关注连接异常和重复清理

## Milestone: v1.2 - Gateway Multi-Service Isolation

**Shipped:** 2026-03-12  
**Phases:** 4 | **Plans:** 12

### What Was Built

- 建立了多上游服务共享 `gateway` 时的 `source` 协议、握手绑定和按域回流路由
- 将 `skill-server` 与 `ai-gateway` 的会话与实体主键治理统一到 Snowflake 基础设施
- 将 `welinkSessionId` 的对外协议基线统一收口到数字型
- 通过 Phase 9 回填了 Phase 7 的正式 verification / summary 证据链

### What Worked

- 先做 `source` 隔离，再做 Snowflake 治理，再做协议收口，阶段边界比较清楚
- gap closure phase 把“工件缺失”和“实现缺陷”分开处理，避免了无效返工

### What Was Inefficient

- 既有 planning 文档存在编码噪声，导致后期 patch 和审计回读成本偏高
- Phase 8 / 08.1 的 summary frontmatter 没有在首次交付时补齐，导致 milestone audit 需要二次回填

### Key Lessons

- 如果 requirement 要参与 milestone audit，summary metadata 和 verification artifact 不能拖到收尾才补
- 数字型 ID 基线一旦在后端统一，协议层也必须同步收口，否则兼容分叉会回流到测试和前端类型层

## Cross-Milestone Trends

- 文档与实现需要更早同步，避免 milestone 收口时集中修正
- 对 WebSocket 生命周期的幂等清理值得作为后续默认准则
- 路由协议维度和 verification artifact 需要尽早产品化，否则 milestone 末期会被审计反向追债
