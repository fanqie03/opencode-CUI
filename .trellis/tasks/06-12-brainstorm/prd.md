# brainstorm: 从零重建项目协议文档

## Goal

从当前代码与真实业务场景重新建立项目协议文档，替代已经过时的现有协议文档。新文档要清楚说明 externalWs 与 miniapp 面向消费者的一套协议，以及 agent 返回侧三套协议的真实来源、转换链路、字段边界和维护规则。

## What I already know

* 现有协议文档整体过时，不能继续作为权威基础增补。
* 需要“从新开始建”，即以当前代码、Trellis business spec、关键测试为事实来源重新生成文档。
* externalWs 和 miniapp 面向消费者的是一套协议。
* agent 返回侧存在三套协议，需要重新梳理清楚。
* 上一轮新增的 `documents/protocol/v3/00-protocol-atlas.md` 是基于旧文档补入口的方向，应撤销或替换。
* 当前真实协议面初步包括：
  * miniapp REST: `SkillSessionController`, `SkillMessageController`, `AgentQueryController`
  * external REST: `ExternalInboundController`, `ExternalInvokeRequest`
  * skill-server WS: `/ws/skill/stream`, `/ws/external/stream`
  * ai-gateway WS: `/ws/skill`, `/ws/agent`
  * 下行 DTO: `StreamMessage`
  * SS/GW DTO: `GatewayMessage`
  * 本地 agent 返回归一：`OpenCodeEventTranslator`
  * 云端 agent 返回归一：`DefaultSseEventDecoder`, `AssistantSquareSseEventDecoder`, `CloudEventTranslator`
  * 云端请求构造：`DefaultCloudRequestStrategy`, `AssistantSquareCloudRequestStrategy`

## Assumptions (temporary)

* 新协议文档应放在新的目录或新的版本命名下，避免读者继续误用旧 `documents/protocol/v1-v3`。
* 旧文档可以作为历史材料参考目录结构，但不能作为事实依据。
* 运行时代码暂不改变，除非梳理过程中发现协议实现与当前业务期望不一致并另开修复任务。

## Open Questions

* 无阻塞问题。

## Requirements (evolving)

* 建立新的协议文档体系，而不是在旧文档上补丁式修正。
* 事实来源优先级：当前代码 > 当前测试 > Trellis business spec > 旧协议文档。
* 删除旧 `documents/protocol` 协议文档，避免继续作为误导性入口。
* 将新协议规范放入 `.trellis/spec/`，以 Trellis spec 作为协议 source of truth。
* 明确 externalWs 与 miniapp 最终消费者协议的共享部分和传输 envelope 差异。
* 明确 agent 返回三套协议的来源、传输层、归一化层、最终消费者协议。
* 明确云端请求协议与 agent 返回协议的关系，尤其是 `cloudProfile=default` 与 `cloudProfile=assistant_square` 的职责边界。
* 给出维护规则：新增字段、类型、agent 返回协议、云端 profile 时该改哪些位置。

## Acceptance Criteria (evolving)

* [x] 新协议文档不依赖旧 v1-v3 文档作为权威事实。
* [x] 读者能从新文档看出 externalWs / miniapp 的最终消费协议是什么。
* [x] 读者能从新文档看出 agent 返回三套协议分别是什么、在哪里转换、谁消费。
* [x] 读者能从新文档看出请求协议、返回协议、最终下行协议不是同一层概念。
* [x] 旧 `documents/protocol` 文档被删除，不再作为入口。
* [x] `.trellis/spec/business/index.md` 能直接跳转到新的协议规范。
* [x] 上一轮错误 atlas / README 入口不会作为新事实入口保留。

## Definition of Done

* Docs updated from current implementation evidence.
* No runtime behavior changes unless explicitly approved.
* Link targets checked.
* Markdown whitespace checked.
* GitNexus detect_changes run before final handoff.

## Completion Evidence

* 新 canonical spec: `.trellis/spec/business/protocol-contracts.md`
* Business spec index entry: `.trellis/spec/business/index.md`
* Phase 1.3 context: `.trellis/tasks/06-12-brainstorm/implement.jsonl`, `.trellis/tasks/06-12-brainstorm/check.jsonl`
* Old protocol docs retired: `documents/protocol/README.md`, `documents/protocol/v1/*`, `documents/protocol/v2/*`, `documents/protocol/v3/*`
* Stale old-doc code reference replaced: `BusinessSessionId` Javadoc now points to `.trellis/spec/business/protocol-contracts.md`

## Out of Scope

* 暂不改运行时代码。
* 暂不重命名线上协议字段。
* 暂不删除 `docs/superpowers/specs/*` 历史调研/设计材料；只删除 `documents/protocol` 旧协议文档目录。

## Technical Notes

* Current task directory: `.trellis/tasks/06-12-brainstorm`
* Must clean up previous mistaken additions:
  * `documents/protocol/v3/00-protocol-atlas.md`
  * README links added to `documents/protocol/README.md`
  * README links added to `documents/protocol/v3/README.md`
* Initial code evidence already identified:
  * `StreamMessage.java`
  * `ExternalWsDeliveryStrategy`
  * `MiniappDeliveryStrategy`
  * `SkillStreamHandler`
  * `ExternalStreamHandler`
  * `OpenCodeEventTranslator`
  * `CloudEventTranslator`
  * `DefaultSseEventDecoder`
  * `AssistantSquareSseEventDecoder`
  * `DefaultCloudRequestStrategy`
  * `AssistantSquareCloudRequestStrategy`

## Decision (ADR-lite)

**Context**: 旧 `documents/protocol` 文档已过时，继续保留会让研发和对接方误以为它是协议权威。当前项目已经由 Trellis 管理，`.trellis/spec/` 是开发规范和跨层契约的权威入口。

**Decision**: 删除旧 `documents/protocol` 协议文档目录；新增 `.trellis/spec/business/protocol-contracts.md` 作为当前协议规范，并在 `.trellis/spec/business/index.md` 建入口。

**Consequences**: 协议 source of truth 收敛到 Trellis spec；旧链接会失效，但比保留过时契约更安全。`docs/superpowers/specs/*` 暂作为历史设计/调研材料保留，不作为当前协议规范入口。

## Anti-Entropy Declaration

* Deletion Class: code-retirement / documentation source-of-truth retirement
* Old Path/Object: `documents/protocol/`
* New Canonical Owner: `.trellis/spec/business/protocol-contracts.md`
* Expected Preserved Behavior: 当前协议知识仍可从 `.trellis/spec/business/` 读取
* Expected Retired Behavior: 旧 v1/v2/v3 协议文档不再作为入口或事实来源
* External Boundary Touched: yes, documentation contract boundary only
* Source-of-Truth Data Risk: none
* User Confirmation Required: no, user explicitly requested deleting old docs and moving to spec
