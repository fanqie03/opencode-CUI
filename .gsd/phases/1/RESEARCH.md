---
phase: 1
level: 2
researched_at: 2026-03-05
---

# Phase 1 Research — PC Agent Plugin (Layer 0 + Layer 1)

## Questions Investigated
1. OpenCode Plugin API — Plugin 类型定义、注册方式、事件钩子、ctx.client API
2. @opencode-ai/sdk Permission API — 如何实现权限回复
3. @opencode-ai/sdk Session.abort — 中止会话 API
4. Bun 运行时兼容性 — node:crypto, node:os 是否支持

## Findings

### 1. OpenCode Plugin API

Plugin 类型从 `@opencode-ai/plugin` 包导入（非 `@opencode-ai/sdk`），格式如下：

```ts
import type { Plugin } from "@opencode-ai/plugin"

export const PlatformAgent: Plugin = async (ctx) => {
  // ctx.client = OpencodeClient 实例
  return {
    event: async ({ event }) => {
      // event 是 Event 联合类型的一个实例
      // 31 种事件类型全部在这里接收
    }
  }
}
```

**注册方式:** 在 `opencode.json` 中配置：
```json
{ "plugin": ["@opencode-cui/platform-agent"] }
```

**ctx.client** 就是 `OpencodeClient` 实例，包含以下模块：
- `session` — list, create, get, delete, abort, prompt, promptAsync, messages, fork, revert...
- `event` — subscribe (SSE 事件流)
- `global` — event (全局事件)
- `project` — list, current
- 以及 tool, config, pty, file, find 等

**关键发现:**
- Plugin 不需要自己创建 `createOpencodeClient()`，`ctx.client` 直接可用
- 事件通过 `event` 钩子自动接收，不需要自己订阅 `event.subscribe()`
- 这意味着当前 `OpenCodeBridge.ts` 中的 `subscribeEvents()` 逻辑在 Plugin 模式下完全不需要

**Sources:**
- https://opencode.ai/docs/plugins
- https://github.com/opencode-ai/opencode/tree/main/sdk
- SDK 本地 `dist/gen/sdk.gen.d.ts` 分析

**Recommendation:** 重构时移除 `OpenCodeBridge.subscribeEvents()`，改用 Plugin event hook 接收事件。保留 `ctx.client.session.prompt()` 替代 `OpenCodeBridge.chat()`。

---

### 2. Permission Reply API

**v1 SDK (当前使用):**
`OpencodeClient` 顶层有一个低级方法：
```ts
postSessionIdPermissionsPermissionId(options: {
  body: { response: "once" | "always" | "reject" },
  path: { id: string, permissionID: string }
})
```

**请求参数：**
- `path.id` = OpenCode Session ID
- `path.permissionID` = 权限请求 ID
- `body.response` = `"once"` | `"always"` | `"reject"`

**注意：** 协议文档 (Layer 0) 中使用的是 `"allow"` 作为 response，但 SDK 实际类型是 `"once" | "always" | "reject"`。需要在 Plugin 层做映射：
- `"allow"` → `"once"` (允许一次)
- `"always"` → `"always"` (始终允许)
- `"deny"` → `"reject"` (拒绝)

**v2 SDK (未来升级):**
有独立的 `Permission` 类，通过 `client.permission` 访问：
```ts
client.permission.respond({ id, permissionID }, { response })
client.permission.reply({ id, permissionID }, { response })
client.permission.list({ id })
```

**Permission 事件类型：**
```ts
EventPermissionUpdated = { type: "permission.updated", properties: Permission }
EventPermissionReplied = { type: "permission.replied", properties: { sessionID, permissionID, response } }
```

**Permission 数据结构：**
```ts
Permission = {
  id: string,
  type: string,           // 如 "bash", "file"
  pattern?: string | string[],
  sessionID: string,
  messageID: string,
  callID?: string,
  title: string,           // 如 "Run: npm install"
  metadata: Record<string, unknown>,
  time: { created: number }
}
```

**Recommendation:** 当前使用 v1 SDK 的 `postSessionIdPermissionsPermissionId()`，并做 `"allow"→"once"` 映射。

---

### 3. Session.abort API

SDK 中直接存在：
```ts
Session.abort(options: {
  path: { id: string },
  query?: { directory?: string }
}): SessionAbortResponses
```

实现方式：
```ts
await ctx.client.session.abort({
  path: { id: toolSessionId }
})
```

**Recommendation:** 直接使用，无需额外封装。

---

### 4. Bun 运行时兼容性

**node:crypto:**
- ✅ `createHmac('sha256', sk)` 完全支持
- ✅ `randomUUID()` 完全支持
- Bun 文档明确列出 `sha256` 作为支持的 HMAC 算法

**node:os:**
- ✅ 100% 通过 Node.js 测试套件
- `os.hostname()`, `os.platform()` 均可用

**ws 包:**
- ⚠️ Bun 有内置 WebSocket 支持
- 但 `ws` npm 包在 Bun 下也能工作（通过 Node.js compatibility layer）
- 如果 Plugin 运行在 OpenCode 进程内（Bun），建议考虑使用 Bun 内置 WebSocket

**SDK 自身：**
- @opencode-ai/sdk v1.2.15 是用 `bun ./script/build.ts` 构建的
- 说明 OpenCode 生态本身就基于 Bun

**Recommendation:** 当前代码无需修改即可在 Bun 下运行。`ws` 包可保留，但如遇问题可切换为 Bun 内置 WebSocket。

---

### 5. OpenCode Event Types（31 种）

完整 `Event` 联合类型：

| 类别             | 事件类型                                                                                                                                        |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| **Server**       | `server.instance.disposed`, `server.connected`                                                                                                  |
| **Installation** | `installation.updated`, `installation.update-available`                                                                                         |
| **LSP**          | `lsp.client.diagnostics`, `lsp.updated`                                                                                                         |
| **Message**      | `message.updated`, `message.removed`, `message.part.updated`, `message.part.removed`                                                            |
| **Permission**   | `permission.updated`, `permission.replied`                                                                                                      |
| **Session**      | `session.status`, `session.idle`, `session.compacted`, `session.created`, `session.updated`, `session.deleted`, `session.diff`, `session.error` |
| **File**         | `file.edited`, `file.watcher.updated`                                                                                                           |
| **VCS**          | `vcs.branch.updated`                                                                                                                            |
| **Todo**         | `todo.updated`                                                                                                                                  |
| **Command**      | `command.executed`                                                                                                                              |
| **TUI**          | `tui.prompt.append`, `tui.command.execute`, `tui.toast.show`                                                                                    |
| **PTY**          | `pty.created`, `pty.updated`, `pty.exited`, `pty.deleted`                                                                                       |

**需上行中继的事件（与对话相关）：**
- `message.*` — AI 回复内容流
- `permission.*` — 权限请求/回复
- `session.*` — 会话生命周期
- `file.edited` — 文件操作结果
- `todo.updated` — 任务列表变化
- `command.executed` — 命令执行

**不需上行中继的事件（本地 TUI/IDE 相关）：**
- `tui.*`, `pty.*`, `installation.*`, `server.*`, `lsp.*`, `vcs.*`

---

## Decisions Made

| Decision                 | Choice                      | Rationale                            |
| ------------------------ | --------------------------- | ------------------------------------ |
| SDK 版本                 | v1 (当前)                   | v2 API 更好但需升级依赖，MVP 先用 v1 |
| 事件接收                 | Plugin event hook           | 不再自行订阅 event.subscribe()       |
| Permission response 映射 | `allow→once`, `deny→reject` | SDK 类型与协议文档不完全一致         |
| WebSocket 库             | 保留 `ws`                   | Bun 兼容，如遇问题再切换             |
| 事件过滤                 | Plugin 层过滤               | 只中继对话相关事件到 Gateway         |

## Patterns to Follow
- Plugin 格式: `export const PlatformAgent: Plugin = async (ctx) => { ... }`
- 使用 `ctx.client.session.*` 替代自建 Bridge
- 事件通过 `return { event: async ({ event }) => { ... } }` 接收
- 所有 async 操作用 try/catch 包裹，错误通过 `tool_error` 上报

## Anti-Patterns to Avoid
- ❌ 在 Plugin 中创建独立 `createOpencodeClient()` — ctx.client 已提供
- ❌ 自行订阅 `event.subscribe()` — Plugin hook 已自动接收
- ❌ 直接使用 `"allow"/"deny"` 作为 permission response — 需映射为 SDK 枚举值
- ❌ 中继全部 31 种事件 — 过滤掉 TUI/PTY/LSP 等本地事件

## Dependencies Identified

| Package               | Version  | Purpose                                  |
| --------------------- | -------- | ---------------------------------------- |
| `@opencode-ai/sdk`    | ^1.2     | OpencodeClient API (session, permission) |
| `@opencode-ai/plugin` | (需添加) | Plugin 类型定义                          |
| `ws`                  | ^8       | WebSocket connection to AI-Gateway       |
| `node:crypto`         | built-in | HMAC-SHA256 for AK/SK authentication     |
| `node:os`             | built-in | hostname, platform detection             |

## Risks
- **@opencode-ai/plugin 包可能不存在或需要从 OpenCode 核心导入:** 需确认包名，可能是从 opencode 主包导入 Plugin 类型
- **SDK v1 的 permission API 命名冗长且不直观:** `postSessionIdPermissionsPermissionId` 名称差，但功能完整
- **ws 包在 Bun 中的长连接稳定性:** 未经长时间验证，可能需要 fallback

## Ready for Planning
- [x] Questions answered
- [x] Approach selected (Plugin refactor + v1 SDK)
- [x] Dependencies identified
- [x] Event filtering strategy defined
- [x] Permission response mapping clarified
