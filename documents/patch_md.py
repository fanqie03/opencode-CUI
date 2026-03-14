import os
import re

MD_PATH = r"d:\02_Lab\Projects\sandbox\opencode-CUI\documents\04-测试用例文档-生产环境.md"

with open(MD_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. TC-GW-U15 + TC-INT-03: 连接策略 (踢旧保新 -> 保旧拒新)
content = content.replace('旧 session 被 close()；新 session 替换到 agentSessions；', 
                          '新连接被拒绝并返回 register_rejected(duplicate_connection)；旧 session 保持存活；')
content = content.replace('Agent A 被踢下线（WS 关闭）；Agent B 变为活跃',
                          'Agent B 被拒绝连接；Agent A 保持活跃')
content = content.replace('旧连接踢下线', '保留旧连接，拒绝新连接')
content = content.replace('踢旧保新', '保旧拒新')

# 2. TC-SK-U57: POST /close -> DELETE
content = content.replace('POST /api/skill/sessions/{id}/close', 'DELETE /api/skill/sessions/{id}')

# 3. TC-SK-U64: permission-reply Path & Body
content = content.replace('POST /api/skill/sessions/{id}/permission-reply', 
                          'POST /api/skill/sessions/{sessionId}/permissions/{permId}')
content = content.replace('{toolCallId: "tc1", approved: true}', 
                          '{"response": "once"} (可选值: once, always, reject)')

# 4. TC-SK-U63: messages/send-to-im -> send-to-im
content = content.replace('POST /api/skill/sessions/{id}/messages/send-to-im', 
                          'POST /api/skill/sessions/{id}/send-to-im')
content = content.replace('{messageId: 1, chatId: "chat_123"}', 
                          '{"content": "...", "chatId": "chat_123"}')

# 5. TC-SK-U12: 倒序 -> 升序
content = content.replace('按 seq 倒序', '按 seq 升序')

# 6. TC-SK-U05: activateIdleSession -> activateSession
content = content.replace('activateIdleSession()', 'activateSession()')

# 7. TC-SK-U07: checkIdleSessions -> cleanupIdleSessions
content = content.replace('checkIdleSessions()', 'cleanupIdleSessions()')

# 8. TC-SK-U03: ProtocolException -> IllegalArgumentException
content = content.replace('ProtocolException(404, "Session not found")', 'IllegalArgumentException("Session not found")')

# 9. TC-SK-U06: listSessions 参数
content = content.replace('listSessions(userId, status=ACTIVE, page=0, size=10)', 'listSessions(SessionListQuery query)')

# 10. TC-SK-U12: getMessages -> getMessageHistory
content = content.replace('getMessages()', 'getMessageHistory()')

# 11. TC-SK-U01: createSession 参数
content = content.replace('createSession(userId, ak, title)', 'createSession(userId, ak, title, imGroupId)')

# 12. TC-SK-U55: createSession Body
content = content.replace('{ak: "ak1", title: "Test"}', '{"ak": "ak1", "title": "Test", "imGroupId": "g1"}')

# 13. TC-GW-U25: Invoke API 返回格式
content = content.replace('{ "success": true }', 'ApiResponse.ok(InvokeResult)')

# 14. TC-GW-U11: toolType 默认值在 handler 中处理
content = content.replace('AgentRegistryService.register()', 'AgentWebSocketHandler.handleRegister()')

# 15. TC-SK-U10: finished 默认值
content = content.replace('finished=false（流式未完成）', 'finished由DB默认值控制 (false)')

# 16. TC-GW-U02: nonce 清除
content = content.replace('Redis 中 nonce 键被清理（防止占用 nonce 空间）', 'Redis 中 nonce 键保留（已存入Redis，等待过期）')

# 17. TC-SK-U39: buildAuthProtocol 返回
content = content.replace('"auth.{Base64URL({token:"test123", source:"skill-server"})}"', 'auth协议前缀拼接Base64URL编码的JSON字符串 (包含token和source)')

# 18. TC-SK-U40: 告警文案
content = content.replace('"insecure for production"', '"This is insecure for production. Please set a proper token."')

# 19. TC-SK-U42: 停止重连文案
content = content.replace('"Stop reconnecting due to authentication failure"', '"Stop reconnecting to gateway due to authentication failure"')

# 20. TC-SK-U41: 重连退避策略
content = content.replace('（1s, 2s, 4s, 8s, 16s）', '（初始 1s，指数增长，最大 30s）')

# 21. TC-GW-U42: 注册加入 macAddress (在内容中替换)
content = content.replace('device_name, os, toolType, toolVersion', 'device_name, os, toolType, toolVersion, macAddress')

print("Applying regex replacements...")

# Append new test case for device binding validation and register timeout
missing_cases = """
#### TC-GW-U48：设备绑定验证（补充）

| 项目 | 内容 |
| --- | --- |
| 模块 | `AgentWebSocketHandler.handleRegister()` -> `DeviceBindingService.validate()` |
| 测试步骤 | 1. Agent发送注册消息，携带不匹配的 macAddress |
| 预期结果 | `DeviceBindingService` 验证失败，连接被拒绝并抛出 `device_binding_failed` |

#### TC-GW-U49：Agent 注册超时（补充）

| 项目 | 内容 |
| --- | --- |
| 模块 | `AgentWebSocketHandler` |
| 测试步骤 | 1. 建立WebSocket连接但不发送 register 消息 |
| 预期结果 | 10秒后连接因超时被服务器主动关闭 (close 4408 register_timeout) |
"""

if 'TC-GW-U48' not in content:
    # insert before TC-GW-U40 or end of TC-GW list
    content = content.replace('#### TC-GW-U40：', missing_cases + '\n#### TC-GW-U40：')

with open(MD_PATH, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to markdown successfully.")
