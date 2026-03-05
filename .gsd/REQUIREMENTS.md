# REQUIREMENTS.md

## Format
| ID     | Requirement                                                                                                                          | Source         | Status  |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------ | -------------- | ------- |
| REQ-01 | Layer 0: Plugin receives all 31 OpenCode event types via event hook                                                                  | SPEC goal 1    | Pending |
| REQ-02 | Layer 0: Plugin executes downlink operations via ctx.client SDK (prompt, create/abort session, permission reply)                     | SPEC goal 1, 4 | Pending |
| REQ-03 | Layer 1: WebSocket connection with AK/SK HMAC-SHA256 authentication                                                                  | SPEC goal 1    | Pending |
| REQ-04 | Layer 1: Device registration (first message after connect)                                                                           | SPEC goal 1    | Pending |
| REQ-05 | Layer 1: Heartbeat (30s interval) + exponential backoff reconnection (1s→30s cap)                                                    | SPEC goal 1    | Pending |
| REQ-06 | Layer 1: Upstream messages: tool_event, tool_done, tool_error, session_created, agent_online/offline, status_response                | SPEC goal 1    | Pending |
| REQ-07 | Layer 1: Downstream messages: invoke (chat/create_session/close_session/permission_reply), status_query                              | SPEC goal 1, 4 | Pending |
| REQ-08 | Layer 2: Gateway↔Skill Server WebSocket with internal token auth                                                                     | SPEC goal 1    | Pending |
| REQ-09 | Layer 2: Gateway injects agentId into all forwarded messages                                                                         | SPEC goal 1    | Pending |
| REQ-10 | Layer 2: agent_online/agent_offline notifications from Gateway to Skill Server                                                       | SPEC goal 1    | Pending |
| REQ-11 | Layer 3: Redis Pub/Sub with `agent:{agentId}` (downlink) and `session:{sessionId}` (uplink) channels                                 | SPEC goal 2    | Pending |
| REQ-12 | Layer 3: Sequence number mechanism with AtomicLong per session channel                                                               | SPEC goal 2    | Pending |
| REQ-13 | Layer 3: SequenceTracker gap detection (small ≤3 warn, medium 4-10 recover, large >10 reconnect)                                     | SPEC goal 2    | Pending |
| REQ-14 | Layer 4: WebSocket streaming at `/ws/skill/stream/{sessionId}`                                                                       | SPEC goal 2    | Pending |
| REQ-15 | Layer 4: Push message types: delta, done, error, agent_offline, agent_online with seq numbers                                        | SPEC goal 2    | Pending |
| REQ-16 | Layer 4: Multi-client subscription support per sessionId                                                                             | SPEC goal 2    | Pending |
| REQ-17 | Layer 5: REST API — GET /api/skill/definitions                                                                                       | SPEC goal 3    | Pending |
| REQ-18 | Layer 5: REST API — Session CRUD (POST, GET list, GET detail, DELETE)                                                                | SPEC goal 3    | Pending |
| REQ-19 | Layer 5: REST API — POST /api/skill/sessions/{id}/messages (send message)                                                            | SPEC goal 3    | Pending |
| REQ-20 | Layer 5: REST API — GET /api/skill/sessions/{id}/messages (paginated history)                                                        | SPEC goal 3    | Pending |
| REQ-21 | Layer 5: REST API — POST /api/skill/sessions/{id}/send-to-im                                                                         | SPEC goal 5    | Pending |
| REQ-22 | Layer 5: REST API — POST /api/skill/sessions/{id}/permissions/{permId} (permission reply)                                            | SPEC goal 4    | Pending |
| REQ-23 | Permission confirmation flow: end-to-end (OpenCode permission.updated → Client UI → user reply → invoke permission_reply → OpenCode) | SPEC goal 4    | Pending |
| REQ-24 | Message persistence: skill_message table with seq, role, content, content_type, meta                                                 | SPEC goal 3    | Pending |
| REQ-25 | Session idle timeout (30 min) + scheduled cleanup                                                                                    | SPEC goal 3    | Pending |
| REQ-26 | AK/SK auth: database-backed credential store (replace hardcoded TODO)                                                                | SPEC goal 1    | Pending |
