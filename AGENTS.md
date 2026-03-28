## 语言规则

- 所有面向用户的输出必须使用 **简体中文**，包括：回答、文档、注释、commit message 摘要、artifact 内容
- 代码中的标识符、技术术语和命令保持 **英文**
- 内部推理可使用英文，但默认优先保持中文表达

## GSD 上下文

- 当前仓库已完成 GSD 初始化，主入口文档位于 `.planning/PROJECT.md`
- 当前 milestone 为 `v1.0 Gateway/Source Routing Redesign`
- 当前首个 phase 为 `Phase 1: Gateway/Source 路由方案定稿`

## 协作约定

- 处理 Gateway/Source 路由相关工作前，优先阅读 `.planning/PROJECT.md`、`.planning/REQUIREMENTS.md`、`.planning/ROADMAP.md` 和 `.planning/STATE.md`
- 设计 phase 与实现 phase 分开推进；在 `Phase 1` 中不要混入生产代码改造
- 方案讨论和文档更新要显式区分新版 Source 必选能力与 legacy 兼容路径
