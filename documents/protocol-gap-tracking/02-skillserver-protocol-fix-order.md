# SkillServer 协议拉齐整改顺序

> 日期：2026-03-11
> 依据文档：`documents/protocol-gap-tracking/01-skillserver-protocol-gap-list.md`
> 目的：将已确认的协议差异整理为可执行的代码整改顺序，降低返工和交叉影响

---

## 1. 总体原则

整改顺序按以下原则编排：

1. 先处理全局约束，再处理具体接口
2. 先处理身份认证和协议基础字段，再处理业务 DTO
3. 先统一错误返回风格，再收敛各接口错误分支
4. 先统一状态机和消息结构，再处理恢复流和兼容逻辑

---

## 2. 推荐整改顺序

### 第一阶段：打基础约束

优先级最高，先统一所有后续改造共同依赖的规则。

1. Layer1 全局认证与访问控制
   - 对应条目：`4`
   - 目标：所有 Miniapp 接口统一从 Cookie 解析 `userId`
   - 目标：所有带 `welinkSessionId` 的接口执行“本地 session 归属校验 + gateway 侧 ak/userId 校验”

2. Layer1 REST 错误返回风格
   - 对应条目：`4a`
   - 目标：所有 Layer1 REST 接口统一返回 HTTP `200`
   - 目标：业务错误统一通过 `code` 和 `errormsg` 表达

3. Layer2 命名收口
   - 对应条目：`23`、`24`
   - 目标：Layer2 只保留 `welinkSessionId` 和 `toolSessionId`
   - 目标：移除 `sessionId` 兼容逻辑

### 第二阶段：收敛 Layer2 通信基础协议

在基础约束稳定后，处理 SkillServer 与 Gateway 的协议主链路。

4. Layer2 WebSocket 建联认证切换到子协议
   - 对应条目：`1`
   - 目标：不再使用 URL `token`
   - 目标：改为 `Sec-WebSocket-Protocol` 子协议认证

5. Layer2 `permission_request` 文档补齐
   - 对应条目：`2`
   - 目标：补充真实存在的 Gateway → Skill 消息

6. Layer2 invoke 参数严格化
   - 对应条目：`21`、`22`
   - 目标：`permission_reply`、`question_reply` 发送前必须保证 `toolSessionId` 存在
   - 目标：缺失时在 SkillServer 本层失败，不发出半残请求

### 第三阶段：收敛 Layer1 会话与消息 API

在认证、错误风格和 Layer2 基础协议稳定后，统一 Miniapp 侧 REST 接口。

7. 创建会话入参口径
   - 对应条目：`3`
   - 目标：文档明确 `imGroupId` 为可选

8. 发送消息接口响应 DTO
   - 对应条目：`5`、`6`
   - 目标：`POST /messages` 返回 Layer1 专用 DTO
   - 目标：文档删除消息持久化含 `userId` 的描述

9. 历史消息接口 DTO
   - 对应条目：`10`、`11`
   - 目标：`GET /messages` 返回协议专用历史消息 DTO
   - 目标：tool part 字段统一为 `status / input / output`

10. 发送消息、权限回复、发 IM 的错误语义统一
    - 对应条目：`7`、`8`、`12`
    - 目标：统一走 HTTP `200 + code/errormsg`
    - 目标：补齐各接口的错误场景文档

11. 在线 Agent 列表协议收敛
    - 对应条目：`13`、`14`
    - 目标：以实现为准更新字段集合
    - 目标：统一 `toolType` 使用小写值

### 第四阶段：收敛 Layer1 流式协议

这部分依赖前面的状态机、DTO 和命名规则，建议放在后面集中收敛。

12. `resume` 行为文档补齐
    - 对应条目：`15`
    - 目标：明确客户端上行 `resume` 的语义

13. `session.status` 状态机统一
    - 对应条目：`17`
    - 目标：对外状态值统一为 `busy / idle / retry`

14. `snapshot.seq` 补齐
    - 对应条目：`16`
    - 目标：所有 WS 消息统一带 `seq`

15. `snapshot` 与历史消息 DTO 复用
    - 对应条目：`18`
    - 目标：`snapshot.messages[]` 复用统一历史消息结构

16. `streaming.parts[]` 改为聚合快照
    - 对应条目：`19`
    - 目标：恢复态表达累计状态，不再暴露事件列表

17. `streaming.sessionStatus` 作为已关闭项保持观察
    - 对应条目：`20`
    - 说明：不单独整改，依赖第 `17` 条状态机统一后的结果验证

---

## 3. 建议按批次提交

为了减少一次改动过大，建议按 4 个批次落代码：

### 批次 A：安全与接口基础

- `4`
- `4a`
- `23`
- `24`

文档同步：

- 无

### 批次 B：Layer2 主链路

- `1`
- `21`
- `22`

文档同步：

- `2`

### 批次 C：Layer1 REST API

- `3`
- `5`
- `6`
- `7`
- `8`
- `10`
- `11`
- `12`
- `13`
- `14`

文档同步：

- `3`
- `6`
- `11`
- `13`
- `14`

### 批次 D：Layer1 WebSocket 流协议

- `15`
- `16`
- `17`
- `18`
- `19`

文档同步：

- `15`

验证观察：

- `20`

---

## 4. 每阶段完成标准

### 第一阶段完成标准

- 所有 Miniapp 接口都接入 Cookie 身份认证
- 所有会话类接口都接入会话归属校验
- 所有 Layer1 REST 错误都统一为 HTTP `200 + code/errormsg`

### 第二阶段完成标准

- SkillServer 与 Gateway 的建联不再依赖 URL `token`
- `permission_reply`、`question_reply` 不再允许缺少 `toolSessionId`
- Layer2 不再继续接受旧字段 `sessionId`

### 第三阶段完成标准

- `POST /messages` 和 `GET /messages` 都不再直接暴露内部模型
- Agent 列表返回字段与文档一致
- `imGroupId`、错误场景、字段命名都完成协议收敛

### 第四阶段完成标准

- `session.status` 值域完成统一
- `snapshot` 和 `streaming` 的结构都与协议一致
- 恢复态不再暴露事件级结构

---

## 5. 备注

- 第 `9` 条已关闭，可不纳入执行批次
- 第 `20` 条当前不单独整改，但在流式协议收敛完成后需要回归确认
- 后续如果开始实际编码，建议先补测试，再逐批落代码，避免多个协议面同时破坏
