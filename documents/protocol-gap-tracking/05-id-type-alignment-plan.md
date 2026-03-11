# ID 类型对齐实施计划

> 日期：2026-03-11  
> 目标口径：
> - `welinkSessionId`: `Long`
> - `toolSessionId`: `String`
> - `userId`: `String`

## 1. 目标

本计划用于收敛当前项目中 `welinkSessionId`、`toolSessionId`、`userId` 的类型定义，确保代码、前端类型、协议返回和数据库最终结构一致。

## 2. 当前结论

- 数据库最终结构基本符合目标口径
- `toolSessionId` 当前代码与库表基本一致，为 `String`
- `userId` 当前有效代码与最终库表基本一致，为 `String`
- 主要偏差集中在 `welinkSessionId`：
  - 后端协议 DTO 仍有 `String`
  - 部分 Layer1 REST 返回仍手动转成字符串
  - 前端协议类型仍将其视为 `string` 或 `string | number`
  - Layer2 `GatewayMessage` 当前也使用 `String`

## 3. 实施顺序

### 批次 A：后端 Layer1 对外类型收口

目标：先保证 Miniapp 直接消费的 Layer1 响应全部符合目标口径。

包含事项：

1. 将 `skill-server` 对外协议 DTO 中的 `welinkSessionId` 从 `String` 改为 `Long`
2. 修改 `ProtocolMessageMapper`，不再把 `sessionId` 转成字符串
3. 清理 Layer1 REST 响应中 `welinkSessionId.toString()` 的返回
4. 确保 `snapshot.messages[]` 和历史消息 DTO 中的 `welinkSessionId` 都输出为数字
5. 补充后端测试，明确断言 `welinkSessionId` 为数字节点

涉及文件：

- `skill-server/src/main/java/com/opencode/cui/skill/model/ProtocolMessageView.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolMessageMapper.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/SkillStreamHandlerTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

完成判定：

- Layer1 REST 和 Layer1 WebSocket 对外 JSON 中的 `welinkSessionId` 不再出现字符串值

### 批次 B：Layer2 类型口径决策与收口

目标：明确 `GatewayMessage.welinkSessionId` 是否也必须统一为 `Long`，并完成代码收口。

决策原则：

- 如果要求“全项目统一”，则 Layer2 也必须改为 `Long`
- 如果允许“跨服务 JSON 层按字符串传输、Layer1 对外按 Long”，则可保留 Layer2 为 `String`

推荐口径：

- 以“全项目统一”为目标，Layer2 也改为 `Long`

包含事项：

1. 评估并修改 `GatewayMessage.welinkSessionId`
2. 清理 `GatewayRelayService`、`GatewayWSClient`、`SkillWebSocketHandler` 相关构造与解析
3. 更新 Layer2 测试用例中的 JSON 断言

涉及文件：

- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/SkillWebSocketHandlerTest.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/GatewayWSClientTest.java`

完成判定：

- Layer2 消息模型中不再存在 `String welinkSessionId`

### 批次 C：前端类型与适配层收口

目标：让 Miniapp 端不再依赖字符串型或混合型 `welinkSessionId`。

包含事项：

1. 将前端协议类型里的 `welinkSessionId` 改为 `number`
2. 将 `Session` 模型中承载会话 ID 的字段收敛为 `number`
3. 清理 `string | number | null` 兼容写法
4. 更新 API 适配逻辑，保证不会再把数字转回字符串
5. 补充类型和 API 映射测试

涉及文件：

- `skill-miniapp/src/protocol/types.ts`
- `skill-miniapp/src/utils/api.ts`

完成判定：

- 前端类型系统中不再存在 `welinkSessionId?: string`
- 前端 API 适配层不再声明 `welinkSessionId?: string | number | null`

### 批次 D：文档与回归

目标：同步协议文档与跟踪文档，形成最终基线。

包含事项：

1. 更新协议文档与附录，明确三类 ID 的标准类型
2. 更新 gap tracking 文档状态
3. 执行后端与前端回归测试

文档口径：

- `welinkSessionId`: `Long`
- `toolSessionId`: `String`
- `userId`: `String`

## 4. 风险点

### 4.1 Layer2 JSON 兼容风险

如果 `GatewayMessage.welinkSessionId` 从 `String` 改为 `Long`，需要确认：

- 现有 JSON 发送方是否存在显式字符串拼装
- 历史测试是否对 `"42"` 做了字符串断言
- Gateway/Skill Server 双方序列化后是否仍能稳定解析

### 4.2 前端旧状态兼容风险

如果前端本地状态、缓存或 URL 参数仍将 `welinkSessionId` 当成字符串，需要同步清理，否则可能出现比较失败。

## 5. 推荐执行方式

推荐按以下顺序执行：

1. 先做批次 A
2. 再决定并执行批次 B
3. 然后做批次 C
4. 最后做批次 D

理由：

- Layer1 是 Miniapp 直接消费面，收益最高
- Layer2 是否强制改 `Long` 需要单独做一次边界确认
- 前端应基于已经稳定的后端返回再收口

## 6. 实施结果

截至 2026-03-11，本计划的批次 A、B、C 已完成。

### 6.1 已完成项

- 批次 A：`skill-server` Layer1 对外 DTO、REST 返回、WebSocket 恢复态中的 `welinkSessionId` 已收敛为 `Long`
- 批次 B：Layer2 `GatewayMessage.welinkSessionId` 已收敛为 `Long`，`invoke.create_session` 的 JSON 发送也已改为数字型 `welinkSessionId`
- 批次 C：`skill-miniapp` 中 `StreamMessage.welinkSessionId`、`Session.id`、相关 hook/API 参数已收敛为 `number`
- 附带修正：`skill-miniapp` 中 `AgentInfo.userId` 已改回 `string`

### 6.2 验证结果

- `skill-server`: `mvn -q test` 通过
- `ai-gateway`: `mvn -q test` 通过
- `skill-miniapp`: `npm run typecheck` 通过

### 6.3 当前基线

- `welinkSessionId`: 后端代码 `Long`，前端类型 `number`，数据库对应 `skill_session.id BIGINT`
- `toolSessionId`: 后端代码 `String`，前端类型 `string`，数据库对应 `VARCHAR`
- `userId`: 后端代码 `String`，前端类型 `string`，数据库最终字段为 `VARCHAR(128)`

### 6.4 收尾动作

- 剩余批次 D：同步协议文档与跟踪文档状态
