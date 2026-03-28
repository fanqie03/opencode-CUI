---
phase: 01
slug: gateway-source
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-29
---

# Phase 01 - Validation Strategy

> Phase 1 为设计文档 phase。验证目标是保证文档完整、术语准确、需求覆盖清晰，而不是运行应用级功能测试。

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | PowerShell + ripgrep (`rg`) 文档校验 |
| **Config file** | none - 直接使用仓库现有命令行工具 |
| **Quick run command** | `rg -n "## Implementation Decisions|## Canonical References" .planning/phases/01-gateway-source/01-CONTEXT.md` |
| **Full suite command** | `rg -n "CURR-01|CURR-02|ROUT-01|ROUT-02|ROUT-03|PROT-01|PROT-02|COMP-01|COMP-02|ACPT-01|ACPT-02" .planning/phases/01-gateway-source/*-PLAN.md` |
| **Estimated runtime** | ~5 seconds |

---

## Sampling Rate

- **After every task commit:** 运行相应文档的 `rg` 章节/术语检查
- **After every plan wave:** 运行全部 requirement coverage 和关键术语检查
- **Before `$gsd-verify-work`:** 所有文档检查命令必须为绿色
- **Max feedback latency:** 10 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 1 | CURR-01, CURR-02 | docs | `rg -n "Source -> GW -> Agent|Agent -> GW -> Source|现状" documents/routing-redesign .planning/phases/01-gateway-source` | X | pending |
| 01-02-01 | 02 | 1 | ROUT-01, ROUT-02, ROUT-03, PROT-01, PROT-02 | docs | `rg -n "route_bind|route_unbind|protocol_error|connectionId -> owningGw|toolSessionId > welinkSessionId > legacy source fallback" documents/routing-redesign .planning/phases/01-gateway-source` | X | pending |
| 01-03-01 | 03 | 2 | COMP-01, COMP-02, ACPT-01, ACPT-02 | docs | `rg -n "legacy|验收场景|后续实现 phase|压测 phase|Out of Scope" documents/routing-redesign .planning/phases/01-gateway-source` | X | pending |

*Status: pending -> green -> red*

---

## Wave 0 Requirements

- [ ] none - 本 phase 不新增测试框架，不安装运行时依赖

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 新旧叙事不冲突 | ROUT-01, COMP-01, COMP-02 | 需要人工判断最终文档是否仍混用 mesh/legacy/v3 语义 | 通读最终设计文档，确认“正式路径”和“兼容路径”章节彼此独立、没有交叉表述 |
| 后续边界清晰 | ACPT-02 | 需要人工判断本 phase 是否夹带实现任务 | 检查最终文档是否把“本 phase 只定方案”与“实现/压测在后续 phase”写成明确边界 |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 10s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
